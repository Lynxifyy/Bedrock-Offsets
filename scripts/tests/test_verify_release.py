"""Tests for the release-artefact verifier."""

from __future__ import annotations

import os
import struct
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import dex  # noqa: E402
import verify_release  # noqa: E402


def _uleb128(value: int) -> bytes:
    """Encode ``value`` as unsigned LEB128."""
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def build_dex(classes: list[tuple[str, int, tuple[str, ...]]]) -> bytes:
    """Assemble a minimal but valid ``classes.dex``.

    Only the header, the string, type and method tables and the class
    definitions are written, which is exactly what the verifier reads.

    Args:
        classes: one ``(descriptor, access_flags, method_names)`` per class.

    Returns:
        The bytes of a dex file.
    """
    strings: list[str] = []

    def intern(text: str) -> int:
        if text not in strings:
            strings.append(text)
        return strings.index(text)

    for descriptor, _, _ in classes:
        intern(descriptor)
    intern("V")
    intern("Ljava/lang/Object;")
    for _, _, method_names in classes:
        for name in method_names:
            intern(name)

    object_index = strings.index("Ljava/lang/Object;")
    void_index = strings.index("V")

    method_ids = [
        (strings.index(descriptor), void_index, strings.index(name))
        for descriptor, _, method_names in classes
        for name in method_names
    ]

    header_size = 0x70
    string_ids_off = header_size
    type_ids_off = string_ids_off + 4 * len(strings)
    method_ids_off = type_ids_off + 4 * len(strings)
    class_defs_off = method_ids_off + 8 * len(method_ids)
    data_off = (class_defs_off + 32 * len(classes) + 3) & ~3

    blob = bytearray()
    string_offsets: list[int] = []
    for text in strings:
        encoded = text.encode()
        string_offsets.append(data_off + len(blob))
        blob += _uleb128(len(encoded)) + encoded + b"\x00"

    class_data_offsets = [0] * len(classes)
    for index, (descriptor, _, method_names) in enumerate(classes):
        if not method_names:
            continue
        class_data_offsets[index] = data_off + len(blob)
        indices = sorted(
            method_ids.index((strings.index(descriptor), void_index, strings.index(name)))
            for name in method_names
        )
        blob += _uleb128(0) + _uleb128(0) + _uleb128(len(indices)) + _uleb128(0)
        previous = 0
        for position, method_index in enumerate(indices):
            delta = method_index if position == 0 else method_index - previous
            previous = method_index
            blob += _uleb128(delta) + _uleb128(0) + _uleb128(0)

    total = data_off + len(blob)
    out = bytearray(total)
    out[0:8] = b"dex\n035\x00"
    struct.pack_into("<I", out, 0x20, total)
    struct.pack_into("<I", out, 0x24, header_size)
    struct.pack_into("<I", out, 0x28, 0x12345678)
    struct.pack_into("<I", out, 0x38, len(strings))
    struct.pack_into("<I", out, 0x3C, string_ids_off)
    struct.pack_into("<I", out, 0x40, len(strings))
    struct.pack_into("<I", out, 0x44, type_ids_off)
    struct.pack_into("<I", out, 0x58, len(method_ids))
    struct.pack_into("<I", out, 0x5C, method_ids_off)
    struct.pack_into("<I", out, 0x60, len(classes))
    struct.pack_into("<I", out, 0x64, class_defs_off)
    struct.pack_into("<I", out, 0x68, len(blob))
    struct.pack_into("<I", out, 0x6C, data_off)

    for index, offset in enumerate(string_offsets):
        struct.pack_into("<I", out, string_ids_off + 4 * index, offset)
    for index in range(len(strings)):
        struct.pack_into("<I", out, type_ids_off + 4 * index, index)
    for index, (class_index, proto_index, name_index) in enumerate(method_ids):
        base = method_ids_off + 8 * index
        struct.pack_into("<H", out, base, class_index)
        struct.pack_into("<H", out, base + 2, proto_index)
        struct.pack_into("<I", out, base + 4, name_index)
    for index, (descriptor, flags, _) in enumerate(classes):
        base = class_defs_off + 32 * index
        struct.pack_into("<I", out, base, strings.index(descriptor))
        struct.pack_into("<I", out, base + 4, flags)
        struct.pack_into("<I", out, base + 8, object_index)
        struct.pack_into("<I", out, base + 24, class_data_offsets[index])

    out[data_off:] = blob
    return bytes(out)


def healthy_dex() -> bytes:
    """A dex holding every model the verifier expects, all intact."""
    classes = [(descriptor, 0x0001, ("<init>",)) for descriptor in verify_release.GSON_MODEL_CLASSES]
    classes.append(
        (
            verify_release.JNI_CALLBACK_CLASS,
            0x0601,
            (verify_release.JNI_CALLBACK_METHOD,),
        )
    )
    return build_dex(classes)


def build_apk(
    root: Path,
    *,
    name: str = "app-release.apk",
    dex: bytes | None = None,
    natives: tuple[str, ...] = ("lib/arm64-v8a/liboffset_core.so",),
    manifest_permission: bool = False,
    filler: bytes = b"",
) -> Path:
    """Write a minimal APK with the requested contents."""
    if dex is None:
        dex = healthy_dex()
    path = root / name
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("classes.dex", dex)
        archive.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00")
        for native in natives:
            archive.writestr(native, b"\x7fELF")
        if filler:
            archive.writestr("assets/filler.bin", filler)
    if manifest_permission:
        # Rebuild with the permission string stored as UTF-16, which is how the
        # binary manifest holds it.
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("classes.dex", dex)
            archive.writestr(
                "AndroidManifest.xml",
                "android.permission.INTERNET".encode("utf-16-le"),
            )
            for native in natives:
                archive.writestr(native, b"\x7fELF")
            if filler:
                archive.writestr("assets/filler.bin", filler)
    return path


class CheckTests(unittest.TestCase):
    """Each check, exercised against a synthetic artefact."""

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)

    def tearDown(self) -> None:
        self.directory.cleanup()

    def test_a_small_apk_passes_the_size_budget(self) -> None:
        apk = build_apk(self.root)
        self.assertTrue(verify_release.check_size(apk).passed)

    def test_an_oversized_apk_fails_the_size_budget(self) -> None:
        apk = build_apk(
            self.root,
            filler=b"\x00" * (verify_release.SIZE_BUDGET_BYTES + 1024),
        )
        result = verify_release.check_size(apk)
        self.assertFalse(result.passed)
        self.assertIn("MiB", result.detail)

    def test_a_packaged_engine_passes_the_native_check(self) -> None:
        apk = build_apk(self.root)
        with zipfile.ZipFile(apk) as archive:
            names = set(archive.namelist())
        self.assertTrue(verify_release.check_native_objects(names).passed)

    def test_a_missing_engine_fails_the_native_check(self) -> None:
        apk = build_apk(self.root, natives=())
        with zipfile.ZipFile(apk) as archive:
            names = set(archive.namelist())
        result = verify_release.check_native_objects(names)
        self.assertFalse(result.passed)
        self.assertIn("liboffset_core.so", result.detail)

    def test_a_debug_log_call_site_fails_the_logging_check(self) -> None:
        apk = build_apk(self.root, dex=b"...android/util/Log->d(...")
        result = verify_release.check_no_debug_logging(apk)
        self.assertFalse(result.passed)
        self.assertIn("android/util/Log->d(", result.detail)

    def test_clean_bytecode_passes_the_logging_check(self) -> None:
        apk = build_apk(self.root, dex=b"...android/util/Log->i(...")
        self.assertTrue(verify_release.check_no_debug_logging(apk).passed)

    def test_a_manifest_without_internet_passes(self) -> None:
        apk = build_apk(self.root)
        self.assertTrue(verify_release.check_manifest(apk).passed)

    def test_a_manifest_with_internet_fails(self) -> None:
        apk = build_apk(self.root, manifest_permission=True)
        result = verify_release.check_manifest(apk)
        self.assertFalse(result.passed)
        self.assertIn("INTERNET", result.detail)

    def test_an_apk_without_a_manifest_is_an_error(self) -> None:
        apk = self.root / "no-manifest.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("classes.dex", b"\x03")
        with self.assertRaises(verify_release.ReleaseCheckError):
            verify_release.check_manifest(apk)

    def test_a_model_that_lost_its_constructor_fails_the_model_check(self) -> None:
        # This is the shipped bug: R8 removes the constructor and marks the
        # class abstract, and Gson then refuses to instantiate it.
        broken = verify_release.GSON_MODEL_CLASSES[0]
        dex_bytes = build_dex(
            [(descriptor, 0x0401, ()) for descriptor in verify_release.GSON_MODEL_CLASSES]
        )
        apk = build_apk(self.root, dex=dex_bytes)

        result = verify_release.check_gson_models(apk)

        self.assertFalse(result.passed)
        self.assertIn(verify_release._simple_name(broken), result.detail)

    def test_a_model_with_a_constructor_but_abstract_still_fails(self) -> None:
        dex_bytes = build_dex(
            [(descriptor, 0x0401, ("<init>",)) for descriptor in verify_release.GSON_MODEL_CLASSES]
        )
        apk = build_apk(self.root, dex=dex_bytes)

        result = verify_release.check_gson_models(apk)

        self.assertFalse(result.passed)
        self.assertIn("abstract", result.detail)

    def test_a_shrunk_away_model_fails_the_model_check(self) -> None:
        survivors = verify_release.GSON_MODEL_CLASSES[1:]
        dex_bytes = build_dex([(descriptor, 0x0001, ("<init>",)) for descriptor in survivors])
        apk = build_apk(self.root, dex=dex_bytes)

        result = verify_release.check_gson_models(apk)

        self.assertFalse(result.passed)
        self.assertIn(verify_release._simple_name(verify_release.GSON_MODEL_CLASSES[0]), result.detail)

    def test_every_intact_model_passes_the_model_check(self) -> None:
        apk = build_apk(self.root)

        result = verify_release.check_gson_models(apk)

        self.assertTrue(result.passed, result.detail)
        self.assertIn(str(len(verify_release.GSON_MODEL_CLASSES)), result.detail)

    def test_a_model_check_on_a_non_dex_fails_rather_than_raises(self) -> None:
        apk = self.root / "junk.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("classes.dex", b"\x03\x00\x00\x00")

        self.assertFalse(verify_release.check_gson_models(apk).passed)


class JniCallbackTests(unittest.TestCase):
    """The check that proves the JNI callback method survived shrinking."""

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)

    def tearDown(self) -> None:
        self.directory.cleanup()

    def test_the_surviving_callback_method_passes(self) -> None:
        apk = build_apk(self.root)
        self.assertTrue(verify_release.check_jni_callback_method(apk).passed)

    def test_a_renamed_callback_method_fails(self) -> None:
        classes = [
            (descriptor, 0x0001, ("<init>",))
            for descriptor in verify_release.GSON_MODEL_CLASSES
        ]
        # The callback kept its constructor but its method was renamed.
        classes.append((verify_release.JNI_CALLBACK_CLASS, 0x0601, ("a",)))
        apk = build_apk(self.root, dex=build_dex(classes))

        result = verify_release.check_jni_callback_method(apk)
        self.assertFalse(result.passed)
        self.assertIn("onProgress", result.detail)

    def test_a_shrunk_away_callback_fails(self) -> None:
        classes = [
            (descriptor, 0x0001, ("<init>",))
            for descriptor in verify_release.GSON_MODEL_CLASSES
        ]
        apk = build_apk(self.root, dex=build_dex(classes))

        result = verify_release.check_jni_callback_method(apk)
        self.assertFalse(result.passed)
        self.assertIn("shrunk away", result.detail)


class ProguardRuleTests(unittest.TestCase):
    """The keep rules and the verifier's model list must agree.

    The rules are what stops R8 from shrinking a model away; the verifier is
    what proves it did not. A model protected by one but not the other is a hole
    in the guard, so both are read from disk and compared.
    """

    @classmethod
    def setUpClass(cls) -> None:
        repo_root = Path(__file__).resolve().parent.parent.parent
        cls.rules_path = repo_root / "app" / "proguard-rules.pro"
        cls.rules_text = cls.rules_path.read_text(encoding="utf-8")

    def protected_packages(self) -> set[str]:
        """Return the packages covered by a `-keep class <pkg>.**` rule."""
        packages = set()
        for line in self.rules_text.splitlines():
            stripped = line.strip()
            if not stripped.startswith("-keep class "):
                continue
            target = stripped[len("-keep class ") :].split("{")[0].strip()
            if target.endswith(".**"):
                packages.add(target[: -len(".**")])
        return packages

    def test_the_rules_file_exists_and_is_tracked(self) -> None:
        self.assertTrue(
            self.rules_path.is_file(),
            "app/proguard-rules.pro is missing, so nothing keeps the models",
        )
        self.assertIn("com.chimerateam.offsetextractor", self.rules_text)

    def test_every_verifier_model_sits_in_a_protected_package(self) -> None:
        protected = self.protected_packages()
        self.assertTrue(protected, "no package-level keep rules were found")

        for descriptor in verify_release.GSON_MODEL_CLASSES:
            package = descriptor[1:-1].rsplit("/", 1)[0].replace("/", ".")
            self.assertTrue(
                any(package == rule or package.startswith(rule + ".") for rule in protected),
                f"{package} holds a Gson model but has no keep rule",
            )

    def test_the_dead_packages_are_not_referenced(self) -> None:
        # The original rules kept these two packages, neither of which exists,
        # so the models they were meant to protect were shrunk away.
        self.assertNotIn("offsetextractor.model.**", self.rules_text)
        self.assertNotIn("offsetextractor.data.model.**", self.rules_text)

    def test_the_jni_callback_method_is_kept(self) -> None:
        # The engine calls `onProgress` by name; a rename makes every progress
        # update a swallowed NoSuchMethodError.
        self.assertIn("ProgressCallback", self.rules_text)
        self.assertIn("void onProgress(int, java.lang.String);", self.rules_text)

    def test_the_signature_attribute_is_kept(self) -> None:
        # Without Signature, Gson cannot see List<PatternHit> as anything but a
        # raw List and silently mis-types the result.
        self.assertIn("-keepattributes Signature", self.rules_text)


class CliTests(unittest.TestCase):
    """The command-line contract."""

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)

    def tearDown(self) -> None:
        self.directory.cleanup()

    def test_a_clean_apk_exits_zero(self) -> None:
        apk = build_apk(self.root)
        self.assertEqual(0, verify_release.main([str(apk)]))

    def test_an_apk_with_a_missing_engine_exits_one(self) -> None:
        apk = build_apk(self.root, natives=())
        self.assertEqual(1, verify_release.main([str(apk)]))

    def test_the_apk_can_be_named_with_a_flag(self) -> None:
        # CI and the docs call the script both ways; the flag form must resolve
        # the same file as the positional form.
        apk = build_apk(self.root)
        self.assertEqual(0, verify_release.main(["--apk", str(apk)]))

    def test_the_flag_overrides_the_positional_argument(self) -> None:
        chosen = build_apk(self.root, name="chosen.apk")
        other = build_apk(self.root, name="other.apk")
        os.utime(chosen, (1_000_000, 1_000_000))
        os.utime(other, (2_000_000, 2_000_000))

        # The directory would resolve to `other.apk`; the flag must win.
        self.assertEqual(0, verify_release.main([str(self.root), "--apk", str(chosen)]))

    def test_a_missing_apk_exits_two(self) -> None:
        missing = self.root / "absent.apk"
        self.assertEqual(2, verify_release.main([str(missing)]))

    def test_a_directory_is_searched_for_the_newest_apk(self) -> None:
        older = build_apk(self.root, name="old.apk")
        newer = build_apk(self.root, name="new.apk")
        # Timestamps are set explicitly: two files written in the same tick
        # would otherwise have the same modification time.
        os.utime(older, (1_000_000, 1_000_000))
        os.utime(newer, (2_000_000, 2_000_000))

        self.assertEqual(newer, verify_release.find_apk(self.root))

    def test_a_directory_without_an_apk_is_an_error(self) -> None:
        with self.assertRaises(verify_release.ReleaseCheckError):
            verify_release.find_apk(self.root)

    def test_a_non_zip_file_exits_two(self) -> None:
        broken = self.root / "broken.apk"
        broken.write_bytes(b"not a zip")
        self.assertEqual(2, verify_release.main([str(broken)]))


if __name__ == "__main__":
    unittest.main()

"""Tests for the CaramelClient pack builder and validator."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import caramel_items  # noqa: E402
import caramel_pack  # noqa: E402
import validate_caramel_pack  # noqa: E402


def _write_category(directory: Path, name: str, document: dict) -> None:
    (directory / f"{name}.json").write_text(json.dumps(document), encoding="utf-8")


def _class(name: str, vtable: str, file_offset: str, slots: int = 3) -> dict:
    return {
        "name": name,
        "mangled": name,
        "bases": [],
        "vtable": vtable,
        "fileOffset": file_offset,
        "vtableOffset": "0x10",
        "typeInfo": "0x2000",
        "slotCount": slots,
        "slots": [],
        "vtableGroups": [],
    }


def _function(name: str, address: str, file_offset: str, source: str = "symbol") -> dict:
    return {
        "address": address,
        "fileOffset": file_offset,
        "size": 4,
        "name": name,
        "nameSource": source,
        "aliases": [],
        "kind": "function",
        "status": "verified",
        "prologueKind": "leaf",
    }


class PackBuildTest(unittest.TestCase):
    """The builder must never drop an item, and must never invent one."""

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.output = Path(self.directory.name) / "categories"
        self.output.mkdir()
        _write_category(
            self.output,
            "client_game",
            {
                "schemaVersion": 1,
                "category": "client_game",
                "classes": {
                    "ClientInstance": _class("ClientInstance", "0x3010", "0x2010"),
                    "LocalPlayer": _class("LocalPlayer", "0x3020", "0x2020"),
                },
                "functions": [
                    _function("ClientInstance::getLocalPlayer", "0x1000", "0x1000"),
                ],
            },
        )
        self.library = Path(self.directory.name) / "libminecraftpe.so"
        self.library.write_bytes(b"\0" * 0x4000)

    def build(self) -> dict:
        return caramel_pack.build(
            self.library,
            self.output,
            "26.60",
            "test",
            read_anchors=True,
        )

    def test_every_requested_item_is_present(self) -> None:
        document = self.build()
        for group, items in caramel_items.GROUPS.items():
            names = {entry["name"] for entry in document["groups"][group]}
            for item in items:
                self.assertIn(item["name"], names)

    def test_a_resolved_class_carries_the_file_offset_not_the_image_offset(self) -> None:
        document = self.build()
        entry = next(
            item
            for item in document["groups"]["access_chain"]
            if item["name"] == "ClientInstance"
        )
        self.assertEqual(entry["status"], "found")
        self.assertEqual(entry["rva"], "0x3010")
        self.assertEqual(entry["file_offset"], "0x2010")
        self.assertEqual(entry["confidence"], "certain")

    def test_a_resolved_function_is_found_by_its_symbol(self) -> None:
        document = self.build()
        entry = next(
            item
            for item in document["groups"]["access_chain"]
            if item["name"] == "ClientInstance::getLocalPlayer"
        )
        self.assertEqual(entry["status"], "found")
        self.assertEqual(entry["rva"], "0x1000")
        self.assertEqual(entry["how_found"], "symbol: demangled name ClientInstance::getLocalPlayer")

    def test_an_unresolved_item_says_what_was_tried(self) -> None:
        document = self.build()
        entry = next(
            item
            for item in document["groups"]["entity_list"]
            if item["name"] == "Level::getRuntimeActorList"
        )
        self.assertEqual(entry["status"], "missing")
        self.assertIsNone(entry["rva"])
        self.assertEqual(entry["confidence"], "none")
        self.assertTrue(entry["tried"])
        self.assertTrue(any("vtable slots" in line for line in entry["tried"]))

    def test_the_summary_matches_the_entries(self) -> None:
        document = self.build()
        entries = [entry for group in document["groups"].values() for entry in group]
        self.assertEqual(document["summary"]["total"], len(entries))
        for status in ("found", "partial", "missing"):
            self.assertEqual(
                document["summary"][status],
                sum(1 for entry in entries if entry["status"] == status),
            )

    def test_the_pack_validates(self) -> None:
        document = self.build()
        validate_caramel_pack.validate(document, self.library.stat().st_size)


class ValidatorTest(unittest.TestCase):
    """The validator must reject every way a pack can lie."""

    def setUp(self) -> None:
        self.document = {
            "schemaVersion": 1,
            "generatedBy": "test",
            "version": "26.60",
            "engineVersion": "test",
            "library": {"sha256": "a" * 64, "size": 4096, "arch": "aarch64"},
            "groups": {},
            "summary": {"total": 0, "found": 0, "partial": 0, "missing": 0, "by_confidence": {}},
        }
        for group, items in caramel_items.GROUPS.items():
            entries = []
            for item in items:
                entry = {
                    "name": item["name"],
                    "kind": item["kind"],
                    "group": group,
                    "status": "missing",
                    "confidence": "none",
                    "rva": None,
                    "file_offset": None,
                    "how_found": None,
                    "evidence": None,
                    "why": item["why"],
                    "tried": ["nothing matched"],
                }
                entries.append(entry)
            self.document["groups"][group] = entries
        self._recount()

    def _recount(self) -> None:
        entries = [entry for group in self.document["groups"].values() for entry in group]
        counts = {"found": 0, "partial": 0, "missing": 0}
        confidence: dict[str, int] = {}
        for entry in entries:
            counts[entry["status"]] += 1
            confidence[entry["confidence"]] = confidence.get(entry["confidence"], 0) + 1
        self.document["summary"] = {"total": len(entries), **counts, "by_confidence": confidence}

    def validate(self) -> None:
        validate_caramel_pack.validate(self.document, 4096)

    def first(self, group: str = "access_chain") -> dict:
        return self.document["groups"][group][0]

    def test_the_clean_document_validates(self) -> None:
        self.validate()

    def test_a_missing_item_is_rejected(self) -> None:
        self.document["groups"]["entity_list"].pop()
        self._recount()
        with self.assertRaises(validate_caramel_pack.PackError):
            self.validate()

    def test_a_found_item_without_an_address_is_rejected(self) -> None:
        entry = self.first()
        entry["status"] = "found"
        entry["confidence"] = "certain"
        self._recount()
        with self.assertRaises(validate_caramel_pack.PackError):
            self.validate()

    def test_a_missing_item_with_an_address_is_rejected(self) -> None:
        entry = self.first()
        entry["rva"] = "0x1000"
        with self.assertRaises(validate_caramel_pack.PackError):
            self.validate()

    def test_a_missing_item_without_its_attempts_is_rejected(self) -> None:
        self.first()["tried"] = []
        with self.assertRaises(validate_caramel_pack.PackError):
            self.validate()

    def test_an_offset_of_zero_is_rejected(self) -> None:
        entry = self.first()
        entry.update(status="found", confidence="certain", rva="0x1000", file_offset="0x0")
        self._recount()
        with self.assertRaises(validate_caramel_pack.PackError):
            self.validate()

    def test_an_address_past_the_library_is_rejected(self) -> None:
        entry = self.first()
        entry.update(status="found", confidence="certain", rva="0x100000", file_offset=None)
        self._recount()
        with self.assertRaises(validate_caramel_pack.PackError):
            self.validate()

    def test_a_duplicate_item_is_rejected(self) -> None:
        entries = self.document["groups"]["access_chain"]
        entries.append(dict(entries[0]))
        self._recount()
        with self.assertRaises(validate_caramel_pack.PackError):
            self.validate()

    def test_a_wrong_summary_is_rejected(self) -> None:
        self.document["summary"]["found"] = 3
        with self.assertRaises(validate_caramel_pack.PackError):
            self.validate()

    def test_a_guessed_item_is_rejected_when_it_is_marked_missing(self) -> None:
        entry = self.first()
        entry["confidence"] = "guess"
        self._recount()
        with self.assertRaises(validate_caramel_pack.PackError):
            self.validate()


if __name__ == "__main__":
    unittest.main()

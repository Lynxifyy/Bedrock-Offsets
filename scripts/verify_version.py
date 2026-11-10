#!/usr/bin/env python3
"""Check that the app version and the release tag agree.

A tag and the ``versionName`` inside the APK are two statements about the same
release. When they drift, the file name of the release artefact, the tag it was
built from and the version the device reports after installing it stop
matching, and nothing else in the pipeline notices.

Exit codes:
    0  the versions agree.
    1  they disagree, or the version could not be read.
    2  the arguments were wrong.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# `versionName = "1.2.3"` inside the defaultConfig block.
VERSION_NAME = re.compile(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', re.MULTILINE)


def read_version_name(build_file: Path) -> str:
    """Return the `versionName` declared in `build_file`.

    Raises:
        ValueError: when the file has no `versionName` assignment.
    """
    if not build_file.is_file():
        raise ValueError(f"{build_file} does not exist")
    match = VERSION_NAME.search(build_file.read_text(encoding="utf-8"))
    if match is None:
        raise ValueError(f"{build_file} declares no versionName")
    return match.group(1)


def normalise_tag(tag: str) -> str:
    """Strip the leading `v` from a release tag."""
    return tag[1:] if tag.startswith("v") else tag


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--tag",
        required=True,
        help="the release tag, for example v1.0.0",
    )
    parser.add_argument(
        "--build-file",
        default="app/build.gradle.kts",
        help="the Gradle file holding versionName (default: %(default)s)",
    )
    args = parser.parse_args(argv)

    build_file = Path(args.build_file)
    try:
        declared = read_version_name(build_file)
    except ValueError as error:
        print(f"[verify_version] {error}", file=sys.stderr)
        return 1

    tagged = normalise_tag(args.tag)
    if declared != tagged:
        print(
            f"[verify_version] tag {args.tag} implies version {tagged}, "
            f"but {build_file} declares {declared}",
            file=sys.stderr,
        )
        return 1

    print(f"[verify_version] {build_file} and tag {args.tag} both say {declared}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

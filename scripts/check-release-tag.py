#!/usr/bin/env python3
# Copyright(c) 2026 Sven Trittler
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
"""Fail before building if a release tag disagrees with any build version."""

from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


def check(tag, root):
    version = tag
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("Release tags must be X.Y.Z, for example 11.1.0 (no v prefix or snapshots).")
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    versions = {}
    for name in ("pom.xml", "maven/pom.xml", "src/buildtools/pom.xml"):
        pom = ET.parse(root / name).getroot()
        for field in ("m:version", "m:parent/m:version"):
            value = pom.findtext(field, namespaces=ns)
            if value is not None:
                versions[f"{name} {field}"] = value
    for value in re.findall(r"project\(CycloneDDSJava VERSION ([^\s)]+)",
                            (root / "CMakeLists.txt").read_text()):
        versions[f"CMake {value}"] = value
    if not versions or any(value != version for value in versions.values()):
        raise ValueError(f"Tag {tag} must match every build version: {versions}")
    print(f"Release {version}: all build versions match; core must use exact tag {tag}.")


if __name__ == "__main__":
    try:
        check(sys.argv[1], Path(__file__).resolve().parents[1])
    except (ValueError, IndexError) as error:
        sys.exit(str(error))

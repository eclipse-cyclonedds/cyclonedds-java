# Copyright(c) 2026 Sven Trittler
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
"""Exercise release/master notice layouts without a native build or network."""
from pathlib import Path
import subprocess
import sys
import tempfile

cmake, module = sys.argv[1:]
module = Path(module).resolve()
notice = b"Core notice\r\n"
license_text = "Copyright Kiyoshi Matsui\nSUCH DAMAGE.\n"
header = "/*-\r\n * Copyright Kiyoshi Matsui\r\n * SUCH DAMAGE.\r\n */\r\n"
with tempfile.TemporaryDirectory(prefix="core notices ") as temporary:
    root = Path(temporary)
    core, build = root / "core", root / "build"
    core.mkdir()
    build.mkdir()
    (core / "NOTICE.md").write_bytes(notice)

    def run(success=True):
        result = subprocess.run(
            [cmake, f"-DCYCLONEDDS_CORE_SOURCE_DIR={core}", "-P", str(module)],
            cwd=build, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
        assert (result.returncode == 0) == success, result.stdout
        return result.stdout

    for layout in ("src/tools/idlpp/src", "src/tools/idlc/idlpp/src"):
        source = core / layout / "system.H"
        source.parent.mkdir(parents=True)
        source.write_bytes(header.encode())
        run()
        output = build / "dependency-notices/cyclonedds"
        assert (output / "NOTICE.md").read_bytes() == b"Core notice\n"
        assert (output / "LICENSE-mcpp").read_bytes() == license_text.encode()
        source.write_text("/*- incomplete license */\n")
        assert "Incomplete MCPP license header" in run(success=False)
        source.unlink()

    assert "Missing MCPP license source" in run(success=False)
    (core / "NOTICE.md").unlink()
    assert "Missing core notice" in run(success=False)
print("Core notices: release/master layouts, LF normalization and missing/invalid inputs passed.")

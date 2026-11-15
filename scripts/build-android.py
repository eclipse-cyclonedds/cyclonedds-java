#!/usr/bin/env python3
# Copyright(c) 2026 Sven Trittler
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
"""Cross-build JNI with a static DDS core; no Android JVM or IDL tools required."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64", "armeabi-v7a", "x86"), required=True)
    parser.add_argument("--ref", default="master", help="Core branch, tag or resolved commit (default: latest master)")
    parser.add_argument("--source", type=Path, help="Existing matching Cyclone DDS checkout (offline builds)")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    work = root / "build" / "android" / args.abi
    work.mkdir(parents=True, exist_ok=True)
    ndk = args.ndk.resolve()
    source = args.source.resolve() if args.source else work / "core"
    if args.source is None:
        if not source.exists():
            run("git", "clone", "https://github.com/eclipse-cyclonedds/cyclonedds.git", source)
        run("git", "-C", source, "fetch", "--depth", "1", "origin", args.ref)
        run("git", "-C", source, "checkout", "--detach", "FETCH_HEAD")
    core_commit = (subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
                   if (source / ".git").exists() else None)
    prefix = work / "core-install"
    common = [f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake",
              f"-DANDROID_ABI={args.abi}", "-DANDROID_PLATFORM=android-28",
              "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
              "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384", "-DCMAKE_BUILD_TYPE=Release"]
    # Core master enables LTO in Release. When its static LLVM archive is linked
    # into JNI, NDK r27's LTO backend emits native x86 TLS resolver calls that
    # Android API 28 does not provide. Keep the archive as ordinary PIC objects,
    # so the Android compiler lowers TLS correctly for each target ABI.
    run("cmake", "-S", source, "-B", work / "core-build", *common,
        f"-DCMAKE_INSTALL_PREFIX={prefix}", "-DBUILD_SHARED_LIBS=OFF",
        "-DCMAKE_POSITION_INDEPENDENT_CODE=ON", "-DENABLE_LTO=OFF", "-DBUILD_IDLC=OFF",
        "-DBUILD_TESTING=OFF", "-DBUILD_EXAMPLES=OFF", "-DBUILD_DDSPERF=OFF", "-DENABLE_SECURITY=OFF", "-DENABLE_SSL=OFF")
    run("cmake", "--build", work / "core-build", "--parallel", "4")
    run("cmake", "--install", work / "core-build")
    run("cmake", "-S", root, "-B", work / "binding", *common,
        f"-DCycloneDDS_DIR={prefix}/lib/cmake/CycloneDDS", "-DBUILD_NATIVE_ONLY=ON")
    run("cmake", "--build", work / "binding", "--parallel", "4")
    library = work / "binding" / "src" / "jni" / "libcycloneddsjava.so"
    readelf, = ndk.glob("toolchains/llvm/prebuilt/*/bin/llvm-readelf")
    report = subprocess.check_output([str(readelf), "-h", "-l", "-d", "-W", str(library)], text=True)
    machine = {"arm64-v8a": "AArch64", "x86_64": "Advanced Micro Devices X86-64",
               "armeabi-v7a": "ARM", "x86": "Intel 80386"}[args.abi]
    if not re.search(r"Machine:\s+" + re.escape(machine) + r"\s*$", report, re.MULTILINE):
        raise SystemExit("Incorrect ELF architecture:\n" + report)
    if "Library soname: [libcycloneddsjava.so]" not in report:
        raise SystemExit("Missing JNI SONAME:\n" + report)
    if "libddsc" in report:
        raise SystemExit("DDS core must be statically linked into JNI")
    loads = [line.split() for line in report.splitlines() if line.strip().startswith("LOAD ")]
    if not loads or any(int(fields[-1], 16) < 16384 for fields in loads):
        raise SystemExit("JNI library must support 16 KB pages:\n" + report)
    package = work / "package"
    if package.exists():
        shutil.rmtree(package)
    destination = package / "jniLibs" / args.abi
    destination.mkdir(parents=True)
    shutil.copy2(library, destination)
    notices = package / "licenses"
    notices.mkdir()
    shutil.copy2(root / "LICENSE", notices / "LICENSE-java")
    for name in ("LICENSE", "NOTICE.md"):
        shutil.copy2(root / "third_party" / "cyclonedds" / name, notices / name)
    (package / "elf-report.txt").write_text(report)
    (package / "build-info.json").write_text(json.dumps({
        "abi": args.abi, "android_api": 28, "core_ref": args.ref if args.source is None else None, "core_commit": core_commit,
        "ndk": (ndk / "source.properties").read_text(),
        "sha256": hashlib.sha256(library.read_bytes()).hexdigest(),
        "validation": "Cross-compile/link and ELF checks only; no Android runtime tests"
    }, indent=2) + "\n")
    print(f"Verified Android JNI payload: {package}")


if __name__ == "__main__":
    main()

..
   Copyright(c) 2026 Sven Trittler

   This program and the accompanying materials are made available under the
   terms of the Eclipse Public License v. 2.0 which is available at
   http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
   v. 1.0 which is available at
   http://www.eclipse.org/org/documents/edl-v10.php.

   SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause


Development distributions
=========================

Build and validate the binding before producing archives:

.. code-block:: sh

   cmake --build build --parallel
   ctest --test-dir build --output-on-failure
   cmake --build build --target package

CPack creates platform-specific ``org.eclipse.cyclonedds-11.0.1-*.tar.gz`` and ``.zip``
files and accompanying SHA-256 checksum files in the build directory. These are
experimental development artifacts, not official Eclipse releases. Nothing is
published by this command. Archive bit-for-bit reproducibility is not established.

A distribution includes:

* ``share/java/org.eclipse.cyclonedds-11.0.1.jar``: API, provider, JNI and native runtimes.
* ``share/java/org.eclipse.cyclonedds.idlc-11.0.1.jar``: compiler launcher, compilers and their platform libraries.
* ``share/java/org.eclipse.cyclonedds-11.0.1-<platform>.jar``: per-platform runtime assembly input.
* ``share/java/org.eclipse.cyclonedds.idlc-11.0.1-<platform>.jar``: per-platform compiler assembly input.
* ``share/java/omg-dds-api.jar``\ : unmodified normative DDS-Java API and Apache notices.
* ``share/java/idl4-java-api.jar``\ : supported sequence interfaces and project license.
* ``share/java/org.eclipse.cyclonedds.jar``\ : provider, codec runtime and project notices.
* ``lib/libcycloneddsjava.*``\ : generic JNI runtime.
* ``lib/libcycloneddsidljava.*``\ : IDL compiler backend.
* ``lib/cmake/CycloneDDSJava``\ : downstream generator/package integration.
* ``share/doc/org.eclipse.cyclonedds``\ : license, README, coverage and usage documentation.
* ``share/org.eclipse.cyclonedds/consumer-example``\ : a standalone Java-only consumer project.

Each runtime/compiler JAR requires JDK 17+ and a platform present in its manifest.
It extracts only the build host's compiler or the running application's native
libraries, checks SHA-256 digests, and loads dependencies in order. The temporary
filesystem must allow native code to load/execute.

The ``org.eclipse.cyclonedds.idlc`` Maven goal generates application types using
its separate compiler package,
without a system Cyclone DDS installation, CMake or a C compiler. Building the
binding itself still requires the matching native core and development tools.
A local CMake build combines the local platform; :doc:`maven` describes the
multi-platform assembly and mandatory release platform checks. Release targets
are Linux x86-64/ARM64, macOS ARM64 and Windows x86-64/ARM64.

Verify an extracted archive
---------------------------

Extract it into a fresh directory and configure the included consumer:

.. code-block:: sh

   cmake -S /path/to/distribution/share/org.eclipse.cyclonedds/consumer-example \
     -B consumer-build \
     -DCMAKE_PREFIX_PATH="/path/to/distribution;/path/to/cyclonedds-install"
   cmake --build consumer-build --parallel
   ctest --test-dir consumer-build --output-on-failure

The consumer project declares only ``LANGUAGES Java``. It generates an appendable
keyed type, compiles against the normative API and generated types without the
provider JAR on its compilation classpath, then loads the bundled provider/core/JNI
and exchanges data using a native waitset. The root ``installed-consumer`` test
performs this install/configure/build/run workflow automatically.

The provider is selected by:

.. code-block:: text

   -Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment

At runtime, include only the runtime JAR and generated application JAR
on the classpath. Application code does not load libraries or set native paths.
The installed CMake package exposes ``CycloneDDSJava_BUNDLED_RUNTIME_JAR`` for
this purpose, alongside the thin API/runtime paths used for compilation.

Maven and CI
------------

``mvn package`` at the repository root builds the Java runtime. The
``maven/pom.xml`` reactor also builds ``org.eclipse.cyclonedds.idlc``, a compiler
JAR that is both directly executable and usable as a Maven build plugin. There
is no separate ``*-maven-plugin`` artifact. Runtime and compiler packages have
no dependency on one another, and Maven APIs are provided by Maven itself.

The CMake ``maven-install`` target installs the assembled runtime JAR and the
compiler JAR with its Maven integration. Explicit ``cyclonedds.runtime.jar`` and
``cyclonedds.idlc.jar`` properties supply the packaged native resources. See
:doc:`maven` for commands. Nothing is published automatically.

``.github/workflows/universal.yml`` builds Linux on ARM64 and x86-64,
macOS on ARM64, and Windows on x86-64/ARM64,
aggregates each kind into its own JAR, then verifies the same runtime/compiler
pair on all five hosts. Each build uses the core selection below. Local validation
is macOS ARM64; remote workflow results must be checked separately.

Unmodified core and reproducibility
-----------------------------------

Supply ``-DCYCLONEDDS_CORE_SOURCE_DIR=/path/to/cyclonedds`` when configuring
the binding to package notices from the matching core checkout. The root project
license is reused for the core; compiler packaging extracts the MCPP license
header from that checkout. OMG API license/notice files come from its Maven JAR.
No separate source-tree directory of copied dependency licenses is needed.

Branch and pull-request builds use the latest Cyclone DDS ``master``. When the
Java wrapper is built from a tag, CI requires the exact same tag in the C core
repository; a missing tag fails the workflow without falling back to master.
The prepare job resolves the selected ref once, then all desktop jobs
build that same commit. No core commit is hard-coded in the workflow.
The separate UBSan workflow follows the same master/tag policy and logs its
resolved core commit.
The resolved commit is recorded in the job log. No binding-specific core patches
are applied, installed or bundled.
The standalone runtime JAR embeds the matching unmodified core and its original
license notices. Runtime behavior is verified against independent C peers built
with the same unmodified installation.

Core limitations remain explicit: unsupported enabled-reader time-based filter
updates are rejected, wchar/negative signed 64-bit union labels are diagnosed,
and generated representation restrictions keep unsupported schemas off XCDR1
endpoints. Java codec-only vectors do not establish native DDS interoperability.

Local Android cross-builds
--------------------------

Android JNI can be built manually for ``arm64-v8a``, ``x86_64``, ``armeabi-v7a``
and ``x86``. There are no Android CI jobs, published Android binaries or Android
payloads in the universal Maven JARs.

With CMake 3.21+, Python 3 and Android NDK ``27.2.12479018`` installed:

.. code-block:: sh

   python3 scripts/build-android.py --ndk /path/to/android/ndk/27.2.12479018 --abi arm64-v8a

The script targets Android API 28 with 16 KB page support. It builds the C core
and statically links it into JNI. By default it uses core ``master``; supply
``--ref 11.0.1`` for that release, or ``--source /path/to/cyclonedds`` to use an
existing core checkout without fetching it. ``BUILD_NATIVE_ONLY=ON`` configures
only JNI, without requiring a host JDK, IDL compiler, examples or JVM tests.

Output is written to ``build/android/<abi>/package/``, containing
``jniLibs/<abi>/libcycloneddsjava.so``, license notices, an ELF report and build
metadata with a SHA-256 digest. The script checks architecture, SONAME and LOAD
segment alignment. It does not run an Android emulator or test Java runtime
compatibility. The output is a native payload, not an AAR.

Applications must provide Java 17 API/desugaring compatibility, Android-specific
native-library loading and packaging, and device or emulator testing. The
desktop runtime loader is not an Android integration.

iOS
---

iOS ARM64 (including the simulator) is not configured: the current binding
requires a JVM with JNI and has no iOS Java/AOT toolchain or static native
registration. Building the C core for iOS alone would not validate this Java
provider. Supporting iOS requires a separate runtime integration first.

..
   Copyright(c) 2026 Sven Trittler

   This program and the accompanying materials are made available under the
   terms of the Eclipse Public License v. 2.0 which is available at
   http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
   v. 1.0 which is available at
   http://www.eclipse.org/org/documents/edl-v10.php.

   SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

Maven: runtime and IDL compiler
===============================

The distribution has two JAR artifacts, both version ``11.1.0`` in Maven group
``org.eclipse.cyclonedds``:

* ``org.eclipse.cyclonedds``: Java API, provider, DDS core and JNI runtime libraries.
* ``org.eclipse.cyclonedds.idlc``: IDL compiler, Java backend, compiler platform
  libraries, CLI launcher and Maven source-generation goal.

The runtime contains no compiler or Maven integration. The compiler has no
runtime-Java dependency and is not put on an application's runtime classpath.
Its Maven integration is packaged inside the compiler JAR, not in a third JAR.
The two packages independently select the appropriate included OS/CPU payload.

Applications need JDK 17+ and Maven 3.9+, without CMake, a C compiler, a system
Cyclone DDS installation or a Git checkout. The artifacts are implemented and
tested locally but are not yet published to Maven Central. Until publication,
install them using the maintainer workflow below or use a Maven repository
containing them.

Application setup
-----------------

Put schemas in ``src/main/idl/Message.idl``:

.. code-block:: idl

   module Example {
     @final struct Message { @key long id; string text; };
   };

Declare the runtime as a dependency and the compiler as a build tool:

.. code-block:: xml

   <properties>
     <maven.compiler.release>17</maven.compiler.release>
     <cyclonedds.version>11.1.0</cyclonedds.version>
   </properties>
   <dependencies>
     <dependency>
       <groupId>org.eclipse.cyclonedds</groupId>
       <artifactId>org.eclipse.cyclonedds</artifactId>
       <version>${cyclonedds.version}</version>
     </dependency>
   </dependencies>
   <build>
     <plugins>
       <plugin>
         <groupId>org.eclipse.cyclonedds</groupId>
         <artifactId>org.eclipse.cyclonedds.idlc</artifactId>
         <version>${cyclonedds.version}</version>
         <executions>
           <execution><goals><goal>generate</goal></goals></execution>
         </executions>
       </plugin>
       <plugin>
         <groupId>org.apache.maven.plugins</groupId>
         <artifactId>maven-compiler-plugin</artifactId>
         <version>3.13.0</version>
       </plugin>
     </plugins>
   </build>

Maven uses its ``<plugin>`` mechanism to run the compiler JAR during
``generate-sources``. ``mvn package`` finds ``src/main/idl/*.idl`` recursively,
executes the packaged host compiler, writes ``target/generated-sources/cyclonedds``
and registers that directory for Java compilation. Application code imports
``Example.Message`` normally. No classifiers, OS properties or platform profiles
are needed in the POM. The complete example is in ``tests/maven-consumer``.

What is in each file
--------------------

``org.eclipse.cyclonedds-11.1.0.jar`` contains only the Java/runtime components
needed by applications. At runtime include this JAR and the application classes;
Maven and the compiler are not needed.

``org.eclipse.cyclonedds.idlc-11.1.0.jar`` contains the CLI, compilers and their
native dependencies. Its Maven APIs are declared ``provided``; they are supplied
by Maven and are not bundled. The CLI can run without Maven libraries:

.. code-block:: sh

   java -jar org.eclipse.cyclonedds.idlc-11.1.0.jar -o generated Message.idl

The launcher chooses the host's Java backend and forwards ``idlc`` options.
``--print-platform`` reports the detected host without extracting tools.

Platform selection and cross-platform builds
--------------------------------------------

The compiler uses ``META-INF/cyclonedds-idlc/<platform>/`` resources for its build
host. The runtime independently loads ``org/eclipse/cyclonedds/native/<platform>/``
for the running application. Each payload has a SHA-256 manifest.

A Linux build can generate Java classes for an application deployed on macOS:
it uses Linux ``idlc``; the deployed application uses macOS DDS/JNI. Packaging
native binaries does not cross-compile new C libraries. The maintainer must
build and test each native variant first.

The release matrix requires Linux on x86-64 and ARM64, macOS on ARM64, and
Windows on x86-64 and ARM64 for both JARs. macOS Intel is not a release target.
A missing host compiler/runtime is reported explicitly.
A local CMake build includes only its host by default; its platform indexes
list only actual supplied binaries. Release aggregation fails when any required
platform is missing.

Incremental builds and configuration
------------------------------------

After the first successful build, ``mvn -o package`` works offline. The compiler
runs as a child process; its temporary extraction directory is removed afterwards.
Source/include contents, preprocessor options and compiler JAR contents determine
whether generation is needed. Changed includes, edited/missing outputs and removed
schemas cause regeneration. A failed compiler fails Maven and retains the last
successful generated sources. Runtime-only changes do not change the compiler
package or its input fingerprint.

.. code-block:: xml

   <configuration>
     <sourceDirectory>${project.basedir}/schemas</sourceDirectory>
     <outputDirectory>${project.build.directory}/generated-sources/dds</outputDirectory>
     <includeDirectories>
       <includeDirectory>${project.basedir}/shared-idl</includeDirectory>
     </includeDirectories>
     <defines><define>FEATURE=1</define></defines>
   </configuration>

The compiler receives include directories with ``-I`` and definitions with
``-D``. Input/output trees must be separate. A nonempty output directory without
the generator's ownership marker is rejected.

``-Dcyclonedds.idlc.skip=true`` registers existing output without generation.
``-Dcyclonedds.idlc.compilerJar=/path/to/idlc.jar`` supplies a local compiler JAR.
``-Dcyclonedds.idlc.version=...`` selects another compiler package version;
normally runtime and compiler use the same Cyclone DDS version.

Maintainer packaging
--------------------

The runtime Maven module compiles against ``org.omg.dds:java5-psm:1.0`` from
Maven Central. The standalone runtime still includes these API classes and
notices. The OMG API sources are not stored in this repository. CMake resolves
the same published JAR from its build cache, the default local Maven repository,
or Maven Central (with the Google-hosted Central mirror as a fallback), and
verifies a pinned SHA-256 digest. To configure without network access, supply
``-DOMG_DDS_API_JAR=/path/to/java5-psm-1.0.jar``. OMG license/notice files come
from that JAR. Native packaging takes core notices and the MCPP license header
from the matching checkout supplied with
``-DCYCLONEDDS_CORE_SOURCE_DIR=/path/to/cyclonedds``. The root project
``LICENSE`` also covers the bundled core under the shared project license terms.

Compiler sources and the compiler Maven module live in ``src/buildtools``. The
repository root contains the runtime Maven module; ``maven/pom.xml`` builds both
and is their shared parent POM (``org.eclipse.cyclonedds-build``). The parent is
published as a POM, not as a third JAR.

A CMake build creates two distribution JARs:

* ``org.eclipse.cyclonedds-11.1.0.jar``: runtime.
* ``org.eclipse.cyclonedds.idlc-11.1.0.jar``: compiler toolchain.

The per-platform files with ``-<platform>`` suffixes are intermediate assembly
inputs. ``UNIVERSAL_BUNDLE_INPUTS`` accepts additional intermediate JARs;
``UNIVERSAL_REQUIRED_PLATFORMS`` lists mandatory platforms and defaults to the
local host. The assembler emits each kind separately:

.. code-block:: sh

   java -cp org.eclipse.cyclonedds.idlc-11.1.0-linux-x86_64.jar \
     org.eclipse.cyclonedds.idlc.UniversalJar --kind runtime \
     --output org.eclipse.cyclonedds-11.1.0.jar --version 11.1.0 \
     --platforms linux-x86_64,linux-aarch64,macos-aarch64,windows-x86_64,windows-aarch64 \
     --input-dir platform-builds

Use ``--kind compiler`` and output ``org.eclipse.cyclonedds.idlc-11.1.0.jar`` for
the compiler package. Inputs are checked for versions, checksums, conflicts and
required platforms. Compiler classes/resources in a runtime package, and runtime
classes/resources in a compiler package, are rejected. ZIP order/timestamps are
normalized; reversed input order produces identical output bytes.

With Maven available at CMake configuration time:

.. code-block:: sh

   cmake --build build --target maven-install

The equivalent explicit invocation is:

.. code-block:: sh

   mvn -f maven/pom.xml \
     -Dcyclonedds.runtime.jar="$PWD/build/org.eclipse.cyclonedds-11.1.0.jar" \
     -Dcyclonedds.idlc.jar="$PWD/build/org.eclipse.cyclonedds.idlc-11.1.0.jar" install

Maven packages the runtime as the main runtime artifact and adds compiler
platform resources to the ``org.eclipse.cyclonedds.idlc`` JAR alongside its Maven
goal/descriptor. This same final compiler JAR is usable through Maven or
``java -jar``. Without those properties the reactor builds Java-only development
artifacts. ``mvn package`` at repository root builds only the Java runtime.
These local packaging/install commands do not publish remotely.

``.github/workflows/universal.yml`` builds five desktop native variants, assembles the two
JARs, adds the compiler's Maven integration and tests the identical pair on every
host. Branch builds use the current Cyclone DDS C ``master`` commit, resolved once
for all platforms. Tag builds require the exact same tag in
``eclipse-cyclonedds/cyclonedds``; a missing tag fails without falling back to master.
Sources and Javadoc are built on branches too, so release packaging is exercised
before tagging.

Publishing a release
--------------------

The Java ``11.1.0`` release requires the matching Cyclone DDS core ``11.1.0``
release. Core ``11.0.1`` lacks the CDR, next-instance collector and IDL APIs used
by this binding, and lacks native Windows ARM64 stacktrace support. Release
builds use the unmodified matching core tag on every platform.

The ``Release to Maven Central`` workflow (``.github/workflows/release.yml``) runs
on pushed tags matching ``[0-9]+.[0-9]+.[0-9]+*``. It calls the reusable
``universal.yml`` workflow for the normal platform builds and tests,
then automatically publishes to Maven Central after all five universal-JAR host
checks pass. The existing version check requires ``X.Y.Z`` (for example
``11.1.0``), without a suffix or snapshot. The tag version must match the
root POM, parent POM, compiler parent version and CMake project version. The C
repository must contain the **exact same tag**. Tags with a ``v`` prefix are rejected.
Pull requests, branch pushes and manual workflow runs never publish.

The publication contains the tested runtime and final compiler JARs, their
sources/Javadoc JARs, all three POMs, PGP signatures and checksums. The release
job copies the verified compiler JAR as its main Maven artifact instead of
replacing it with a new build. Maven Central's publishing plugin uploads one
reactor bundle, automatically publishes it and waits until its state is
``PUBLISHED``. Central releases cannot be overwritten; use a new version for
subsequent changes.

One-time account and signing setup:

#. Create/sign in to an account at https://central.sonatype.com/ and ensure it
   has publishing permission for the ``org.eclipse.cyclonedds`` namespace.
   This requires authorization from the namespace owner; a GitHub repository
   alone does not grant permission to publish Eclipse coordinates.
#. Generate a Central Portal user token under the account settings. Use its
   generated username and password, not your interactive login credentials.
   See https://central.sonatype.org/publish/generate-portal-token/.
#. Create or choose a passphrase-protected OpenPGP signing key. Publish its
   public key to a Central-supported keyserver, for example:

   .. code-block:: sh

      gpg --full-generate-key
      gpg --list-secret-keys --keyid-format LONG
      gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_FINGERPRINT
      gpg --armor --export-secret-keys YOUR_FINGERPRINT > /safe/path/maven-signing-key.asc

   Keep the exported private key outside the checkout. Central needs the public
   key to verify signatures; see https://central.sonatype.org/publish/requirements/gpg/.
#. Make the following GitHub organization Actions secrets available to this
   repository (or configure repository secrets with the same names):

   * ``CENTRAL_SONATYPE_TOKEN_USERNAME``: generated Central Portal token username.
   * ``CENTRAL_SONATYPE_TOKEN_PASSWORD``: generated Central Portal token password.
   * ``GPG_PRIVATE_KEY``: entire ASCII-armored exported private key.
   * ``GPG_PASSPHRASE``: signing key passphrase.
   * ``GPG_KEY_ID``: signing key ID or fingerprint.

The release job imports the private key through ``actions/setup-java``. Maven's
GPG signer selects ``GPG_KEY_ID`` and reads the passphrase from the
``MAVEN_GPG_PASSPHRASE`` environment variable. The Central token is mapped to the
``central`` server in ``.github/maven-settings.xml``. Secrets are used only in the
publication job, after the reusable build succeeds. No extra GitHub token
permission is required beyond ``contents: read``.

For example, after all versions are updated to ``11.1.0`` and the C repository
has ``11.1.0``, push the Java release tag:

.. code-block:: sh

   git tag -a 11.1.0 -m "Cyclone DDS Java 11.1.0"
   git push origin 11.1.0

Local deployment dry run (requires an imported GPG signing key and
``MAVEN_GPG_PASSPHRASE`` in the environment, but no
Central credentials and **does not upload**):

.. code-block:: sh

   mvn --settings .github/maven-settings.xml -f maven/pom.xml \
     -Prelease-artifacts,central-release -DskipUpload=true \
     -Dgpg.keyname=YOUR_KEY_ID \
     -Dcyclonedds.runtime.jar=/absolute/path/to/verified-runtime.jar \
     -Dcyclonedds.idlc.verifiedJar=/absolute/path/to/verified-compiler.jar deploy

The bundle is written to ``maven/target/central-publishing/central-bundle.zip``.
Use Maven 3.9.9 and JDK 17, matching the release job. The CI retains the signed
bundle as the ``maven-central-deployment`` workflow artifact for diagnostics.
The publishing extension is configured according to
https://central.sonatype.org/publish/publish-portal-maven/.

Validation
----------

``-DBUILD_MAVEN_TESTING=ON`` enables the independent Maven consumer test. It checks
a clean build, compiler repository resolution, offline rebuilding, includes,
defines, error handling and stale-source removal. It also checks that the runtime
JAR contains no compiler/Maven classes, the compiler has no runtime-Java classes,
and the Maven-packaged compiler is executable without Maven on its classpath.
``idlc-bundle`` tests native compiler execution and runtime loading separately.
``universal-assembly`` checks archive separation, completeness, checksums and
host selection. Synthetic foreign fixtures are never executed or distributed.
Local execution is verified on macOS ARM64; CI supplies other host checks.

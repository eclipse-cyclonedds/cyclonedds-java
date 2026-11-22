# Java Binding for Eclipse Cyclone DDS

[![Website](https://img.shields.io/badge/web-cyclonedds.io-blue)](https://cyclonedds.io)
[![Community](https://img.shields.io/badge/discord-join%20community-5865f2)](https://discord.gg/BkRYQPpZVV)

The official Java binding for [Eclipse Cyclone DDS](https://github.com/eclipse-cyclonedds/cyclonedds) using the **official OMG DDS-Java API** and generated IDL4-Java types.

Two artifacts are provided:

* **`org.eclipse.cyclonedds`**: Java API, provider, DDS core and JNI libraries for runtime use.
* **`org.eclipse.cyclonedds.idlc`**: IDL compilers, platform libraries, command-line launcher and Maven build integration.

## Quick start

Use JDK 17+ and Maven 3.9+. Add this dependency to your project's `pom.xml`:

```xml
<dependency>
  <groupId>io.trittsv.cyclonedds</groupId>
  <artifactId>org.eclipse.cyclonedds</artifactId>
  <version>11.0.1</version>
</dependency>
```

Run `mvn package`; the runtime includes the matching native libraries for your
platform. For your own IDL types, add the generator shown below.

Then see [the example](#example) and [the docs](docs/manual/index.rst) to get started.

## Example

## 1. Generate a type

Save this as `Message.idl`:

```idl
module Example {
  @final struct Message { @key long id; string text; };
};
```

Put the file in `src/main/idl/Message.idl` and add the runtime dependency and
IDL generator to your Maven project's `pom.xml`:

```xml
<properties>
  <maven.compiler.release>17</maven.compiler.release>
  <cyclonedds.version>11.0.1</cyclonedds.version>
</properties>
<dependencies>
  <dependency>
    <groupId>io.trittsv.cyclonedds</groupId>
    <artifactId>org.eclipse.cyclonedds</artifactId>
    <version>${cyclonedds.version}</version>
  </dependency>
</dependencies>
<build>
  <plugins>
    <plugin>
      <groupId>io.trittsv.cyclonedds</groupId>
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
```

`mvn package` now generates Java under `target/generated-sources/cyclonedds`
before compiling the application. The `org.eclipse.cyclonedds.idlc` build tool
uses its own bundled compiler. No CMake, C compiler, GitHub checkout or manually installed `idlc` is needed in an application project.
Use JDK 17+ and Maven 3.9+.

No platform classifier or OS profiles are needed in the POM. The universal
release build requires Linux on x86-64 and ARM64, macOS on ARM64, and Windows on
x86-64 and ARM64. Its build matrix
combines the native outputs and tests the identical runtime/compiler pair on each host. A local
CMake build includes the local host by default; building that JAR alone does not
produce binaries for other systems. See
[assembly and cross-platform builds](docs/manual/maven.rst).

Generated classes have a default constructor, an all-values constructor, and
`get_id()` / `set_id(...)` accessors. No application-specific C library is needed.
See [Maven options, includes and offline builds](docs/manual/maven.rst), or the
[existing CMake/direct compiler integration](docs/manual/idljava.rst).
Optional/external fields, inheritance, recursive types, mutable XCDR1/XCDR2, wide
text and arrays of sequences are supported; see [richer schemas](docs/manual/howtos/idl.rst).

## 2. Use the type

Save as `src/main/java/Demo.java`. It publishes and receives one sample through the standard API:

```java
import Example.Message;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.omg.dds.core.ServiceEnvironment;
import org.omg.dds.core.WaitSet;
import org.omg.dds.core.status.PublicationMatchedStatus;
import org.omg.dds.domain.DomainParticipantFactory;

public final class Demo {
    public static void main(String[] args) throws Exception {
        System.setProperty("org.omg.dds.serviceClassName",
            "org.eclipse.cyclonedds.omg.CycloneServiceEnvironment");
        var env = ServiceEnvironment.createInstance(Demo.class.getClassLoader());
        var waitset = WaitSet.newWaitSet(env);
        try (var participant = DomainParticipantFactory.getInstance(env).createParticipant(0);
             var topic = participant.createTopic("ExampleMessage", Message.class);
             var publisher = participant.createPublisher();
             var subscriber = participant.createSubscriber();
             var writer = publisher.createDataWriter(topic);
             var reader = subscriber.createDataReader(topic);
             var ready = reader.select().getCondition()) {
            var matched = writer.getStatusCondition();
            matched.setEnabledStatuses(Set.of(PublicationMatchedStatus.class));
            waitset.attachCondition(matched);
            if (writer.getPublicationMatchedStatus().getCurrentCount() == 0)
                waitset.waitForConditions(5, TimeUnit.SECONDS);
            waitset.detachCondition(matched);
            waitset.attachCondition(ready);
            writer.write(new Message(42, "Hello from Java!"));
            waitset.waitForConditions(5, TimeUnit.SECONDS);
            for (var sample : reader.select().take(null)) {
                if (sample.getData() != null)
                    System.out.println(sample.getData().get_text());
            }
        } finally {
            if (waitset instanceof AutoCloseable resource) resource.close();
        }
    }
}
```

```sh
mvn package
mvn org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=Demo
```

Output: `Hello from Java!`. The runtime JAR loads its matching bundled native
libraries automatically; application code needs no native search-path setup.

## Build the package from source

Requirements: JDK 17+, CMake 3.21+, a C11 compiler and a Cyclone DDS installation
with `idlc`, IDL/DDSI development headers and type-library support.
The first CMake configuration resolves `org.omg.dds:java5-psm:1.0` from the local
Maven cache or Maven Central and verifies its SHA-256 digest. For an offline build,
pass `-DOMG_DDS_API_JAR=/path/to/java5-psm-1.0.jar`. No OMG API sources are vendored.
Native packaging also needs `-DCYCLONEDDS_CORE_SOURCE_DIR=/path/to/core` to collect
notices from the matching core checkout. OMG notices come from its Maven JAR.
Branch and pull-request CI builds use the latest Cyclone DDS `master`; Java tag
builds use the exact same tag in the C core repository and fail if it is missing.
The selected core commit is resolved once per workflow for all platforms.
No Cyclone DDS core patches are required. Build the core first:

```sh
git clone --branch master https://github.com/eclipse-cyclonedds/cyclonedds.git core
# For a release, use --branch <same-tag-as-java> instead.
cmake -S core -B core-build -DCMAKE_INSTALL_PREFIX="$PWD/core-install" \
  -DBUILD_TESTING=OFF -DBUILD_EXAMPLES=OFF -DENABLE_SECURITY=OFF -DENABLE_SSL=OFF
cmake --build core-build --parallel
cmake --install core-build
```

Then build and install the binding:

```sh
cmake -S . -B build -DCMAKE_PREFIX_PATH="$PWD/core-install" \
  -DCYCLONEDDS_CORE_SOURCE_DIR="$PWD/core"
cmake --build build --parallel
ctest --test-dir build --output-on-failure
cmake --install build --prefix /path/to/java-binding
cmake --build build --target package
```

The build creates separate runtime and compiler JARs for the configured platforms,
per-platform assembly inputs, thin development JARs, JNI runtime and IDL backend. CPack produces development `.tar.gz` / `.zip` archives and SHA-256
checksums. Pushing a release tag publishes the verified Maven artifacts to Maven Central
when publishing credentials are configured. Temporarily, pushes and manual runs on
`feature/initial-commit` also publish to the test group `io.trittsv.cyclonedds`.
Other branch builds create workflow artifacts.
See [release setup](docs/manual/maven.rst#publishing-a-release). The tests cover wire formats,
standard API behavior, three independent C interoperability paths, a Java-only
installed consumer, and bundled-native loading with native search paths disabled.
Local validation is macOS ARM64; Linux x86-64/ARM64, macOS ARM64 and Windows x86-64/ARM64 CI is configured.

To build and install the Maven artifacts locally, make Maven available when
configuring CMake, then run:

```sh
cmake --build build --target maven-install
```

This installs `org.eclipse.cyclonedds` (runtime) and `org.eclipse.cyclonedds.idlc`
(compiler plus Maven integration) into the local Maven repository. It does not
publish to a remote repository. See [Maven packaging and validation](docs/manual/maven.rst)
and [distribution details](docs/manual/distribution.rst).

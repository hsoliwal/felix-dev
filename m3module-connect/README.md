# M3 Felix Atomos Connect preflight (Java 21)

An **opt-in**, Apache-2.0 module that wraps the standard OSGi Connect
`ModuleConnector` without taking over module resolution, bundle installation,
class loading, service registration, or lifecycle decisions.

The catalog in [`../m3module`](../m3module) reads OSGi JARs, JPMS JARs, JMODs
and JDK module descriptors and produces deterministic evidence. This adapter
allows Apache Felix Atomos and Apache Felix Framework to continue doing the
work they already do, with an auditable preflight before initialization.

## Usage with existing Apache Felix Atomos

```java
Atomos atomos = Atomos.newAtomos();
M3ConnectPreflight connector = new M3ConnectPreflight(
        atomos.getModuleConnector(),
        List.of(Path.of("build/bundles"), Path.of("/path/to/jdk/jmods")));

Map<String, String> config = Map.of(
        Constants.FRAMEWORK_SYSTEMPACKAGES, "",
        Constants.FRAMEWORK_STORAGE, "target/osgi-cache");
Framework framework = new org.apache.felix.framework.FrameworkFactory()
        .newFramework(config, connector);
framework.init();
framework.start();

// Optionally feed evidenceJson() into the M3Scale/file journal.
System.out.println(connector.evidenceJson());
```

The above snippet assumes the Atomos and Felix Framework dependencies are
already available to the caller. The preflight's JMOD path is **inspection
only**: JMOD files cannot be loaded as runtime modules or OSGi bundles.

The default `M3ConnectPreflight(delegate, paths)` is **report-only**. If a
deployment policy requires rejecting duplicate JPMS module names in the
provided paths, use `M3ConnectPreflight(delegate, paths, true)`. A diagnostic
about OSGi/JPMS non-equivalence is *not* a deployment error. Scanning fails
closed for invalid input paths or malformed JMODs. The adapter scans once at
construction, and artifacts are immutable snapshots; a later file edit
requires a new instance.

`initialize(storage, config)`, `connect(location)`, and
`newBundleActivator()` forward directly to the supplied connector, preserving
the exact object references, results and exceptions. These methods are not
intercepted for metrics or content rewriting. Apache Felix retains OSGi
resolution and lifecycle authority; the JVM retains JPMS access control.

## Build and test on five JDK 21 vendors

```bash
mvn -B -ntp -f m3module/pom.xml clean install
mvn -B -ntp -f m3module-connect/pom.xml verify
mvn -B -ntp -f m3module-connect/pom.xml rewrite:dryRun
```

The isolated catalog and adapter are intentionally not added to the Felix
Framework's own Maven reactor. They do not change the framework's Java
baseline, public API or dependencies. Tests cover an identity-preserving
connector spy, strict and report-only duplicate detection, and Felix Framework
startup and shutdown through an actual Atomos connector.

The `rewrite.yml` recipe is **search-only**. Future source transformations
must have a dedicated OpenRewrite recipe, contract tests, and an independently
reviewed diff. No existing Felix sources are rewritten by this change.

## Extension points

1. Incremental M3Scale journal keyed by the existing artifact SHA-256.
2. Maven, Gradle and Eclipse PDE adapters that consume this evidence without
   changing OSGi manifest or JPMS descriptor meanings.
3. Optional runtime capability providers for tested OpenJ9, GraalVM and other
   JDK implementations, with separate compatibility and licensing checks.
4. Dedicated `jlink` integration tests, native-code filters, fragment
   attachment, dynamic imports and qualified JPMS exports before advancing
   beyond the preflight-only scope.

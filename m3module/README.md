# M3 module convergence catalog (Java 21)

This additive Synexia component inventories **OSGi bundles, modular/automatic
JARs, JMOD archives, and the running JDK's modules** without replacing Apache
Felix or Java's module resolver. It is designed to feed Maven, Gradle, Eclipse
PDE, M3Scale, and [Apache Felix Atomos](https://github.com/apache/felix-atomos).

## Why it lives beside Felix Framework

Apache Felix already implements OSGi Connect and Atomos already maps modules on
the JPMS module path or in `jlink` images to connected OSGi bundles. The Felix
Framework has a wider Java compatibility baseline than Synexia M3JDK 21. An
independently built module therefore avoids raising its minimum JDK or changing
the established OSGi wiring, lifecycle, bundle identities or service registry.

This module is deliberately **not** a second OSGi resolver, a custom class
loader, a JMOD executable loader, or an alternative `ModuleConnector`.

## What is preserved

| Evidence | Representation |
| --- | --- |
| OSGi manifest | Original, case-insensitively collected main attribute values, including directives and version-range strings |
| JPMS | Real `ModuleDescriptor`, including requires, exports, qualified targets, opens, uses and provides |
| JMOD | `classes/module-info.class` parsed by the public `ModuleDescriptor.read` API; `lib/` and `bin/` entries identified, never executed or extracted |
| Artifact identity | SHA-256 of each entire artifact, plus deterministic path-ordered reports |
| JDK implementation | Runtime vendor, VM name, Java version, platform and available system module descriptors |

JMODs are valid for **compile/link-time use**, not as runtime modules. Java's
`ModuleFinder.of(jmod)` rejects JMODs at execution time, so the catalog reads
the documented archive content rather than trying to load a JMOD as a bundle.
Atomos remains responsible for connecting content already present on the module
path or in a linked image.

The report's `findings` make non-equivalences explicit:

- `OSGI_PACKAGE_WIRING`: version ranges and uses constraints remain OSGi-only.
- `OSGI_PACKAGE_EXPORT`: package versions and `uses` are not JPMS exports.
- `OSGI_REQUIRE_BUNDLE`: bundle version ranges, reexport and identity remain OSGi-only.
- `OSGI_REQUIRE_CAPABILITY` / `OSGI_PROVIDE_CAPABILITY`: namespaces and filters
  do not collapse into JPMS `requires` or `provides` declarations.
- `OSGI_DYNAMIC_IMPORT`: dynamic imports cannot become static JPMS requires.
- `OSGI_FRAGMENT`: fragment-host attachment must remain an OSGi operation.
- `OSGI_NATIVE_CODE`: native selection needs OSGi filter/platform handling.
- `OSGI_BUNDLE_CLASSPATH`: bundle-embedded JARs and classpath ordering are distinct.
- `OSGI_LAZY_ACTIVATION` / `OSGI_BUNDLE_ACTIVATOR`: preserve OSGi lifecycle.
- `OSGI_DECLARATIVE_SERVICES`: requires the OSGi Service Component Runtime.
- `OSGI_JPMS_NAME_DIFFERENCE`: names are kept distinct, never silently renamed.
- `DUPLICATE_JPMS_MODULE`: one scan contains two paths defining the same JPMS name.
- `JDK_MODULE_VARIANT`: separate JDK `jmods/` sets contain the same module name;
  compare their surfaces without treating alternate runtimes as one module path.
- `JPMS_UNREADABLE`: preserve a valid OSGi JAR even if JPMS can't resolve it.

## Build, inspect, and test

Run with a complete JDK 21 or newer:

```bash
mvn -B -ntp -f m3module/pom.xml verify
java --module-path m3module/target/m3-felix-module-catalog-0.1.0-SNAPSHOT.jar \
  --module com.synexia.felix.modules/com.synexia.felix.modules.ModuleCatalogCli \
  --runtime --scan /path/to/bundles --jdk-home /path/to/another-jdk
```

Both `--scan` and `--jdk-home` may be repeated. The latter reads the target
JDK's `jmods/` folder without executing it. The single JSON document has schema
`com.synexia.m3module.catalog/v1` and contains `runtime`, `artifacts`,
`jdkInstallations` (including target JDK release-file provenance), and `findings`.

Add `--features` to opt in to a deterministic `capabilityEvidence` array for
documented module-level donor signals (JFR, the Vector incubator, JVMCI, Graal
compiler module, CRaC module, jlink module, and compiler modules). A signal
means only that a module descriptor was found in the current runtime or a
scanned JMOD. **It does not mean the feature is initialized, supported by the
VM, licensed for copying, or available through an API.** OpenJ9 shared caches,
GraalVM native-image compilation, and vendor-specific AOT need independent
providers and actual capability tests; VM branding is never sufficient. The
default JSON shape is unchanged without this option.

To compare *actual JMOD evidence* from multiple JDK implementations, use:

```bash
java --module-path m3module/target/m3-felix-module-catalog-0.1.0-SNAPSHOT.jar \
  --module com.synexia.felix.modules/com.synexia.felix.modules.ModuleCatalogCli \
  --compare-jdks --jdk-home /path/to/temurin-21 --jdk-home /path/to/semeru-21
```

The opt-in `jdkModuleDifferences` array compares each JDK against the
lexicographically first canonical JDK home, independently of command-line
argument order. It checks module presence, declared version, open-module and
other modifiers, main class, JPMS `requires` (including modifiers and compiled
versions), qualified `exports` and `opens`, `uses`, `provides`, packages, and
whole-JMOD SHA-256. The comparison only includes JMODs directly under the
provided JDK homes; unrelated `--scan` artifacts are excluded. A difference
reports *evidence*, not binary incompatibility or permission to replace
OSGi resolution. Different hashes alone may reflect vendor implementation or
packaging details even when module descriptors agree. This mode requires two
distinct `--jdk-home` arguments and does not change the existing v1 JSON
shape when omitted. It is covered by the same search-only OpenRewrite recipe,
`com.synexia.m3module.AuditModuleConnect`, and Java 21 vendor CI matrix.

A non-existent input or a malformed JMOD fails closed; an OSGi JAR
whose JPMS metadata is invalid remains readable and receives a diagnostic.
The self-test checks that the runtime `ModuleFinder` rejects JMOD inputs and,
when the active JDK ships both `jmods/` and a `jlink` tool provider, produces a
linked image containing the fixture module. This feature test is skipped with
an explicit diagnostic on JDK images without the linking inputs.

To run the non-mutating OpenRewrite audit of the module code:

```bash
mvn -B -ntp -f m3module/pom.xml rewrite:dryRun
```

`rewrite.yml` is an initial **search-only recipe**. Any automated migration of
existing Felix/Atomos code should be a separate recipe with compatibility tests
and a verified diff. This catalog itself does not transform existing sources.

## Multi-JDK donor policy

The CI matrix tests the same Java 21 contract on Temurin/HotSpot, IBM Semeru
(OpenJ9), Amazon Corretto, Azul Zulu, and GraalVM Community. Vendor-specific
features (shared-class caches, AOT, native image, checkpoint/restore) can be
added later behind explicit capability detection, not assumed just from the
vendor name. Such optional mechanisms cannot change public Java/OSGi contracts.
Keep each vendor's licenses and attribution separate; inspecting metadata does
not confer permission to copy implementation source into Apache-licensed code.

## Follow-on adapters

1. Use this catalog as deterministic preflight evidence for Atomos before its
   `ModuleConnector` is initialized. Retain the framework's authoritative OSGi
   resolver and the JVM's authoritative JPMS access checks.
2. Feed artifact hashes, module graph data and diagnostics into the M3Scale
   file journal. Update only changed inputs, then promote FILE → PACKAGE →
   MODULE → REACTOR → API, keeping the original atom-level evidence.
3. Integrate build goals with the Maven and Gradle forks; preserve the same
   descriptors for Eclipse PDE, m2e and JDT.
4. Test real Felix + Atomos launches and `jlink` images on each JDK before any
   runtime-path or class-loader optimization is proposed.

This is the **first implementation slice** of the convergence work, not a claim
that all OSGi and JPMS semantics can be merged losslessly.

The next opt-in slice is implemented in
[`../m3module-connect`](../m3module-connect): an identity-preserving wrapper
around the existing Atomos `ModuleConnector`, with read-only preflight evidence,
optional conflict rejection, and real Felix/Atomos smoke tests. It deliberately
does not alter either resolver or treat JMODs as runtime bundles.

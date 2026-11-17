/*
 * Copyright 2026 Synexia contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.synexia.felix.modules;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Executable assertion harness; also run from Maven via its JUnit wrapper. */
public final class ModuleInventorySelfTest {
    private ModuleInventorySelfTest() { }

    public static void main(String[] args) throws Exception {
        Path fixture = Files.createTempDirectory("m3module-test-");
        try {
            Path compiled = compileModule(fixture);
            Path dual = jar(fixture.resolve("dual.jar"), compiled, true, true);
            ModuleArtifact artifact = ModuleInventory.inspect(dual);
            check(artifact.kind() == ModuleArtifact.Kind.DUAL_JAR, "dual JAR classification");
            check(artifact.jpmsName().orElseThrow().equals("com.example.catalog"), "JPMS name");
            check(artifact.osgiSymbolicName().orElseThrow().equals("com.example.bundle"), "OSGi identity");
            check(artifact.manifestHeaders().get("Bundle-SymbolicName").contains("singleton"),
                    "OSGi directives must be preserved verbatim");
            check(artifact.sha256().matches("[0-9a-f]{64}"), "content hash");
            check(ModuleCompatibility.audit(List.of(artifact)).stream()
                    .anyMatch(f -> f.code().equals("OSGI_DYNAMIC_IMPORT")), "dynamic import diagnostic");
            List<String> osgiOnlyContracts = List.of("OSGI_PACKAGE_EXPORT", "OSGI_REQUIRE_BUNDLE",
                    "OSGI_REQUIRE_CAPABILITY", "OSGI_PROVIDE_CAPABILITY", "OSGI_BUNDLE_CLASSPATH",
                    "OSGI_LAZY_ACTIVATION", "OSGI_BUNDLE_ACTIVATOR", "OSGI_DECLARATIVE_SERVICES");
            check(ModuleCompatibility.audit(List.of(artifact)).stream()
                    .map(ModuleCompatibility.Finding::code).toList().containsAll(osgiOnlyContracts),
                    "OSGi resolver, classpath, and lifecycle semantics must remain explicit");

            Path automatic = jar(fixture.resolve("automatic.jar"), compiled, true, false);
            ModuleArtifact osgi = ModuleInventory.inspect(automatic);
            check(osgi.kind() == ModuleArtifact.Kind.OSGI_JAR, "automatic OSGi JAR classification");
            check(osgi.jpms() != null && osgi.jpms().isAutomatic(), "automatic JPMS module");

            String first = CatalogJson.render(List.of(artifact, osgi), ModuleInventory.currentRuntime());
            String second = CatalogJson.render(List.of(artifact, osgi), ModuleInventory.currentRuntime());
            check(first.equals(second), "stable JSON output");
            check(first.contains("\"javaVersion\""), "runtime information");
            check(first.contains("\"OSGI_DYNAMIC_IMPORT\""), "findings in JSON");
            check(ModuleInventory.scan(List.of(fixture)).size() == 2, "recursive artifact scan");
            check(ModuleCompatibility.audit(List.of(artifact, artifact)).stream()
                    .noneMatch(f -> f.code().equals("DUPLICATE_JPMS_MODULE")),
                    "same input is not a duplicate");
            check(ModuleCompatibility.audit(List.of(artifact, ModuleInventory.inspect(
                    Files.copy(dual, fixture.resolve("duplicate.jar"))))).stream()
                    .anyMatch(f -> f.code().equals("DUPLICATE_JPMS_MODULE")),
                    "different paths exporting the same JPMS name are reported");

            Path jmod = fixture.resolve("catalog.jmod");
            Path nativeDirectory = fixture.resolve("native");
            Files.createDirectories(nativeDirectory);
            Files.write(nativeDirectory.resolve("libm3bridge.so"), new byte[] { 1, 2, 3 });
            Optional<java.util.spi.ToolProvider> jmodTool = java.util.spi.ToolProvider.findFirst("jmod");
            check(jmodTool.isPresent(), "JMOD tool provider is required on a full JDK 21");
            StringWriter output = new StringWriter();
            StringWriter errors = new StringWriter();
            int jmodStatus = jmodTool.get().run(new PrintWriter(output), new PrintWriter(errors),
                    "create", "--class-path", compiled.toString(),
                    "--libs", nativeDirectory.toString(), jmod.toString());
            check(jmodStatus == 0, "jmod creation: " + errors);
            ModuleArtifact linked = ModuleInventory.inspect(jmod);
            check(linked.kind() == ModuleArtifact.Kind.JMOD, "JMOD classification");
            check(linked.jpmsName().orElseThrow().equals("com.example.catalog"), "JMOD descriptor");
            check(linked.jmodNativePaths().contains("lib/libm3bridge.so"),
                    "JMOD native content inventory");

            // JMOD is a compile/link-time container, never a runtime module.
            try {
                ModuleFinder.of(jmod).findAll();
                throw new AssertionError("runtime ModuleFinder must reject JMOD input");
            } catch (FindException expected) {
                check(expected.getMessage().contains("JMOD"),
                        "runtime JMOD rejection must be explicit");
            }

            Optional<java.util.spi.ToolProvider> jlinkTool = java.util.spi.ToolProvider.findFirst("jlink");
            Path currentJmods = Path.of(System.getProperty("java.home"), "jmods");
            if (jlinkTool.isPresent() && Files.isDirectory(currentJmods)) {
                // Do not place the two same-named JAR fixtures on jlink's module path.
                Path linkModulePath = Files.createDirectories(fixture.resolve("jmod-input"));
                Files.copy(jmod, linkModulePath.resolve("catalog.jmod"));
                Path image = fixture.resolve("linked-image");
                StringWriter linkErrors = new StringWriter();
                int linkStatus = jlinkTool.get().run(new PrintWriter(new StringWriter()),
                        new PrintWriter(linkErrors), "--module-path",
                        currentJmods + java.io.File.pathSeparator + linkModulePath,
                        "--add-modules", "com.example.catalog", "--output", image.toString(),
                        "--no-header-files", "--no-man-pages");
                check(linkStatus == 0, "JMOD-to-jlink image creation: " + linkErrors);
                Path imageRelease = image.resolve("release");
                check(Files.isRegularFile(imageRelease), "jlink image has a release manifest");
                check(Files.readString(imageRelease).contains("com.example.catalog"),
                        "jlink image contains the sample module");
            } else {
                System.out.println("Skipping jlink image test: this JDK image has no jmods/jlink");
            }

            Path vendorA = fixture.resolve("vendor-a");
            Path vendorB = fixture.resolve("vendor-b");
            Files.createDirectories(vendorA.resolve("jmods"));
            Files.createDirectories(vendorB.resolve("jmods"));
            Files.writeString(vendorA.resolve("release"),
                    "IMPLEMENTOR=\"Example Vendor A\"\nJAVA_VERSION=\"21.0.1\"\n");
            Path aModule = Files.copy(jmod, vendorA.resolve("jmods/catalog.jmod"));
            Path bModule = Files.copy(jmod, vendorB.resolve("jmods/catalog.jmod"));
            check(ModuleInventory.inspectJdkHome(vendorA).releaseFields().get("IMPLEMENTOR")
                    .equals("Example Vendor A"), "other-JDK provenance");
            check(ModuleCompatibility.audit(List.of(ModuleInventory.inspect(aModule),
                    ModuleInventory.inspect(bModule))).stream()
                    .anyMatch(f -> f.code().equals("JDK_MODULE_VARIANT")
                            && f.level().equals("INFO")), "separate vendor JDKs are variants");
            check(CatalogJson.render(List.of(), null,
                    List.of(ModuleInventory.inspectJdkHome(vendorA)))
                    .contains("Example Vendor A"), "target JDK provenance in JSON");

            ModuleArtifact mixedCase = new ModuleArtifact(dual,
                    ModuleArtifact.Kind.OSGI_JAR, artifact.sha256(), null,
                    java.util.Map.of("bundle-symbolicname", "lower.case"), List.of(), List.of());
            check(mixedCase.osgiSymbolicName().orElseThrow().equals("lower.case"),
                    "OSGi header lookup is case insensitive");

            Path badJmod = fixture.resolve("invalid.jmod");
            Files.writeString(badJmod, "not-a-jmod");
            try {
                ModuleInventory.inspect(badJmod);
                throw new AssertionError("invalid JMOD must fail closed");
            } catch (IOException expected) {
                check(expected.getMessage().contains("Invalid JMOD header"), "JMOD format check");
            }

            check(ModuleInventory.currentRuntime().modules().stream()
                    .anyMatch(d -> d.name().equals("java.base")), "system modules");

            // Module evidence is usable across vendors without falsely
            // inferring OpenJ9, GraalVM, CRaC or other features from a VM name.
            ModuleInventory.RuntimeSnapshot synthetic = new ModuleInventory.RuntimeSnapshot(
                    "21", "Any vendor", "OpenJ9 or GraalVM (unverified)", "Linux", "x86_64",
                    List.of(java.lang.module.ModuleDescriptor.newModule("jdk.jfr").build(),
                            java.lang.module.ModuleDescriptor.newModule("jdk.crac").build()));
            List<JdkCapabilityEvidence.Signal> signals =
                    JdkCapabilityEvidence.inspect(synthetic, List.of());
            check(signals.stream().anyMatch(s -> s.feature().equals("jdk.crac-module")
                    && s.evidence().equals("SYSTEM_MODULE_DESCRIPTOR")), "module evidence");
            check(signals.stream().noneMatch(s -> s.feature().contains("openj9")),
                    "vendor strings cannot imply unverified runtime capabilities");
            ModuleArtifact donorJmod = new ModuleArtifact(
                    fixture.resolve("vendor-c/jmods/jdk.crac.jmod"), ModuleArtifact.Kind.JMOD,
                    "0".repeat(64), java.lang.module.ModuleDescriptor.newModule("jdk.crac").build(),
                    java.util.Map.of(), List.of(), List.of());
            List<JdkCapabilityEvidence.Signal> combinedSignals =
                    JdkCapabilityEvidence.inspect(synthetic, List.of(donorJmod));
            check(combinedSignals.stream().anyMatch(s -> s.feature().equals("jdk.crac-module")
                    && s.evidence().equals("JMOD_DESCRIPTOR")
                    && s.origin().endsWith("vendor-c/jmods/jdk.crac.jmod")),
                    "a separate JDK provides independent JMOD provenance");
            check(combinedSignals.equals(JdkCapabilityEvidence.inspect(synthetic, List.of(donorJmod))),
                    "capability evidence has stable deterministic ordering");
            String extended = CatalogJson.render(List.of(), synthetic, List.of(), true);
            check(extended.contains("\"capabilityEvidence\""), "opt-in capability output");
            check(!CatalogJson.render(List.of(), synthetic).contains("\"capabilityEvidence\""),
                    "legacy JSON contract stays unchanged by default");
            java.io.ByteArrayOutputStream featureOutput = new java.io.ByteArrayOutputStream();
            check(ModuleCatalogCli.run(new String[] { "--features" },
                    new java.io.PrintStream(featureOutput), System.err) == 0,
                    "opt-in CLI capability mode");
            check(featureOutput.toString(java.nio.charset.StandardCharsets.UTF_8)
                    .contains("\"capabilityEvidence\""), "opt-in CLI report");
            System.out.println("M3 module inventory self-test passed");
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static Path compileModule(Path root) throws IOException {
        Path sources = root.resolve("sources");
        Path compiled = root.resolve("compiled");
        Files.createDirectories(sources.resolve("com/example"));
        Files.createDirectories(compiled);
        Path module = sources.resolve("module-info.java");
        Path sample = sources.resolve("com/example/Widget.java");
        Files.writeString(module, "module com.example.catalog { exports com.example; }");
        Files.writeString(sample, "package com.example; public class Widget { }");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        check(compiler != null, "A full JDK, not a JRE, is required");
        int exit = compiler.run(null, null, null, "-source", "21", "-target", "21", "-d", compiled.toString(),
                module.toString(), sample.toString());
        check(exit == 0, "fixture compilation failed");
        return compiled;
    }

    private static Path jar(Path dest, Path compiled, boolean osgi, boolean explicitModule)
            throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        if (osgi) {
            attributes.putValue("Bundle-SymbolicName", "com.example.bundle;singleton:=true");
            attributes.putValue("Bundle-Version", "1.2.3");
            attributes.putValue("Import-Package", "com.example.api;version=\"[1.0,2.0)\"");
            attributes.putValue("Export-Package", "com.example;version=\"1.0.0\"");
            attributes.putValue("Require-Bundle", "com.example.dependency;bundle-version=\"[1.0,2.0)\"");
            attributes.putValue("Require-Capability", "osgi.ee;filter:=\"(osgi.ee=JavaSE)\"");
            attributes.putValue("Provide-Capability", "com.example.capability;flag=true");
            attributes.putValue("DynamicImport-Package", "*");
            attributes.putValue("Bundle-ClassPath", ".,lib/embedded.jar");
            attributes.putValue("Bundle-ActivationPolicy", "lazy");
            attributes.putValue("Bundle-Activator", "com.example.Widget");
            attributes.putValue("Service-Component", "OSGI-INF/component.xml");
        }
        if (!explicitModule) attributes.putValue("Automatic-Module-Name", "com.example.automatic");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(dest), manifest);
             var files = Files.walk(compiled)) {
            for (Path input : files.filter(Files::isRegularFile).sorted().toList()) {
                String entry = compiled.relativize(input).toString().replace('\\', '/');
                if (!explicitModule && entry.equals("module-info.class")) continue;
                output.putNextEntry(new ZipEntry(entry));
                Files.copy(input, output);
                output.closeEntry();
            }
        }
        return dest;
    }

    private static void check(boolean condition, String explanation) {
        if (!condition) throw new AssertionError(explanation);
    }
}

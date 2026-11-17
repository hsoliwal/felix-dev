/*
 * Copyright 2026 Synexia contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.synexia.felix.modules;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.FindException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/** Build-time/read-only inspection. No class loader, resolver or service registry is changed. */
public final class ModuleInventory {
    private ModuleInventory() { }

    public static ModuleArtifact inspect(Path input) throws IOException {
        Path path = input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IOException("Not a regular artifact file: " + path);
        }
        boolean jmod = hasExtension(path, ".jmod");
        if (!jmod && !hasExtension(path, ".jar")) {
            throw new IOException("Expected a .jar or .jmod: " + path);
        }
        String hash = sha256(path);
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<String> nativePaths = new ArrayList<>();
        if (!jmod) {
            try (JarFile jar = new JarFile(path.toFile())) {
                Manifest manifest = jar.getManifest();
                copyManifest(manifest, headers);
            }
        }

        ModuleDescriptor jpms = null;
        List<String> diagnostics = new ArrayList<>();
        if (jmod) {
            try (InputStream in = Files.newInputStream(path)) {
                byte[] magic = in.readNBytes(4);
                if (magic.length != 4 || magic[0] != 'J' || magic[1] != 'M'
                        || magic[2] != 1 || magic[3] != 0) {
                    throw new IOException("Invalid JMOD header: " + path);
                }
            }
            try (ZipFile zip = new ZipFile(path.toFile())) {
                ZipEntry moduleInfo = zip.getEntry("classes/module-info.class");
                if (moduleInfo == null) {
                    throw new IOException("JMOD has no classes/module-info.class: " + path);
                }
                try (InputStream in = zip.getInputStream(moduleInfo)) {
                    jpms = ModuleDescriptor.read(in);
                }
                ZipEntry manifest = zip.getEntry("classes/META-INF/MANIFEST.MF");
                if (manifest != null) {
                    try (InputStream in = zip.getInputStream(manifest)) {
                        copyManifest(new Manifest(in), headers);
                    }
                }
                zip.stream().map(ZipEntry::getName)
                        .filter(name -> name.startsWith("lib/") || name.startsWith("bin/"))
                        .sorted().forEach(nativePaths::add);
            } catch (RuntimeException ex) {
                throw new IOException("Invalid JMOD module descriptor: " + path, ex);
            }
        } else {
            try {
                Set<ModuleReference> references = ModuleFinder.of(path).findAll();
                if (references.size() != 1) {
                    throw new FindException("Expected exactly one JPMS module; found " + references.size());
                }
                jpms = references.iterator().next().descriptor();
            } catch (FindException | IllegalArgumentException e) {
                // An OSGi bundle can be valid even if JPMS rejects its automatic name.
                diagnostics.add("JPMS_UNREADABLE: " + e.getMessage());
            }
        }

        boolean osgi = headers.containsKey("Bundle-SymbolicName");
        ModuleArtifact.Kind kind = jmod ? ModuleArtifact.Kind.JMOD
                : osgi && jpms != null && !jpms.isAutomatic() ? ModuleArtifact.Kind.DUAL_JAR
                : osgi ? ModuleArtifact.Kind.OSGI_JAR
                : jpms != null && !jpms.isAutomatic() ? ModuleArtifact.Kind.MODULAR_JAR
                : ModuleArtifact.Kind.PLAIN_JAR;
        return new ModuleArtifact(path, kind, hash, jpms, headers, nativePaths, diagnostics);
    }

    /** Recursively inventories only JAR/JMOD inputs; the same path is indexed once. */
    public static List<ModuleArtifact> scan(List<Path> inputs) throws IOException {
        Set<Path> paths = new java.util.TreeSet<>(Comparator.comparing(Path::toString));
        for (Path input : inputs) {
            Path path = input.toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                try (Stream<Path> stream = Files.walk(path)) {
                    stream.filter(Files::isRegularFile)
                            .filter(ModuleInventory::isSupported)
                            .forEach(paths::add);
                }
            } else if (Files.isRegularFile(path)) {
                if (!isSupported(path)) {
                    throw new IOException("Expected a .jar or .jmod: " + path);
                }
                paths.add(path);
            } else {
                throw new IOException("Input not found: " + path);
            }
        }
        List<ModuleArtifact> result = new ArrayList<>();
        for (Path path : paths) {
            result.add(inspect(path));
        }
        return List.copyOf(result);
    }

    public static RuntimeSnapshot currentRuntime() {
        List<ModuleDescriptor> modules = ModuleFinder.ofSystem().findAll().stream()
                .map(ModuleReference::descriptor)
                .sorted(Comparator.comparing(ModuleDescriptor::name))
                .toList();
        return new RuntimeSnapshot(
                Runtime.version().toString(),
                System.getProperty("java.vendor", "unknown"),
                System.getProperty("java.vm.name", "unknown"),
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.arch", "unknown"),
                modules);
    }

    public record RuntimeSnapshot(String javaVersion, String vendor, String vmName,
                                  String osName, String architecture,
                                  List<ModuleDescriptor> modules) {
        public RuntimeSnapshot {
            modules = List.copyOf(modules);
        }
    }

    /** Release-file provenance for a separate JDK installation. Never executes that JDK. */
    public record JdkInstallation(Path home, Map<String, String> releaseFields) {
        public JdkInstallation {
            home = home.toAbsolutePath().normalize();
            releaseFields = Collections.unmodifiableMap(new TreeMap<>(releaseFields));
        }
    }

    public static JdkInstallation inspectJdkHome(Path input) throws IOException {
        Path home = input.toAbsolutePath().normalize();
        if (!Files.isDirectory(home.resolve("jmods"))) {
            throw new IOException("JDK does not contain a jmods directory: " + home);
        }
        Map<String, String> fields = new TreeMap<>();
        Path release = home.resolve("release");
        if (Files.isRegularFile(release)) {
            // JDK release metadata is key=value, often with quoted values.
            for (String line : Files.readAllLines(release)) {
                int equals = line.indexOf('=');
                if (equals <= 0 || line.startsWith("#")) continue;
                String key = line.substring(0, equals).trim();
                String value = line.substring(equals + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                if (Set.of("IMPLEMENTOR", "IMPLEMENTOR_VERSION", "JAVA_VERSION",
                        "JAVA_RUNTIME_VERSION", "JAVA_VM_VERSION", "OS_ARCH").contains(key)) {
                    fields.put(key, value);
                }
            }
        }
        return new JdkInstallation(home, fields);
    }

    private static boolean hasExtension(Path path, String extension) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension);
    }

    private static boolean isSupported(Path path) {
        return hasExtension(path, ".jar") || hasExtension(path, ".jmod");
    }

    private static void copyManifest(Manifest manifest, Map<String, String> headers) {
        if (manifest != null) {
            for (Map.Entry<Object, Object> entry : manifest.getMainAttributes().entrySet()) {
                headers.put(entry.getKey().toString(), entry.getValue().toString());
            }
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK SHA-256 provider is missing", e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(path), digest)) {
            byte[] buffer = new byte[64 * 1024];
            while (in.read(buffer) != -1) {
                // DigestInputStream advances the digest incrementally.
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
}

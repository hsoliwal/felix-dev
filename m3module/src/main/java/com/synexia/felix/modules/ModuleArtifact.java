/*
 * Copyright 2026 Synexia contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.synexia.felix.modules;

import java.lang.module.ModuleDescriptor;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * An inventory record, not a replacement for an OSGi BundleRevision or a JPMS Module.
 * The complete JPMS descriptor and original manifest header values are retained.
 */
public record ModuleArtifact(
        Path path,
        Kind kind,
        String sha256,
        ModuleDescriptor jpms,
        Map<String, String> manifestHeaders,
        List<String> jmodNativePaths,
        List<String> diagnostics) {

    public enum Kind { JMOD, DUAL_JAR, OSGI_JAR, MODULAR_JAR, PLAIN_JAR }

    public ModuleArtifact {
        path = path.toAbsolutePath().normalize();
        TreeMap<String, String> caseInsensitiveHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        caseInsensitiveHeaders.putAll(manifestHeaders);
        manifestHeaders = Collections.unmodifiableMap(caseInsensitiveHeaders);
        jmodNativePaths = List.copyOf(jmodNativePaths);
        diagnostics = List.copyOf(diagnostics);
    }

    public Optional<String> osgiSymbolicName() {
        String original = manifestHeaders.get("Bundle-SymbolicName");
        if (original == null || original.isBlank()) {
            return Optional.empty();
        }
        // Only the symbolic name is taken here; the unparsed directives remain intact.
        String name = original.split(";", 2)[0].trim();
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    public Optional<String> osgiVersion() {
        return Optional.ofNullable(manifestHeaders.get("Bundle-Version"));
    }

    public Optional<String> jpmsName() {
        return Optional.ofNullable(jpms).map(ModuleDescriptor::name);
    }

    public boolean hasExplicitJpmsDescriptor() {
        return jpms != null && !jpms.isAutomatic();
    }
}

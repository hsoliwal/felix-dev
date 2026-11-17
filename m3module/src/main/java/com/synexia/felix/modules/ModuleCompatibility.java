/*
 * Copyright 2026 Synexia contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.synexia.felix.modules;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Conservative compatibility audit. OSGi requirements and JPMS readability are
 * deliberately not treated as interchangeable resolution rules.
 */
public final class ModuleCompatibility {
    private ModuleCompatibility() { }

    public record Finding(String level, String code, Path path, String detail) { }

    public static List<Finding> audit(List<ModuleArtifact> artifacts) {
        List<Finding> findings = new ArrayList<>();
        Map<String, ModuleArtifact> jpmsOwners = new HashMap<>();
        for (ModuleArtifact artifact : artifacts) {
            for (String diagnostic : artifact.diagnostics()) {
                findings.add(new Finding("WARNING", "JPMS_UNREADABLE", artifact.path(), diagnostic));
            }
            artifact.jpmsName().ifPresent(name -> {
                ModuleArtifact prior = jpmsOwners.putIfAbsent(name, artifact);
                if (prior != null && !prior.path().equals(artifact.path())) {
                    boolean separateJdks = isJdkJmod(prior) && isJdkJmod(artifact)
                            && !prior.path().getParent().equals(artifact.path().getParent());
                    findings.add(new Finding(separateJdks ? "INFO" : "ERROR",
                            separateJdks ? "JDK_MODULE_VARIANT" : "DUPLICATE_JPMS_MODULE",
                            artifact.path(), name + " is also supplied by " + prior.path()));
                }
            });
            if (artifact.osgiSymbolicName().isPresent() && artifact.jpmsName().isPresent()
                    && !artifact.osgiSymbolicName().get().equals(artifact.jpmsName().get())) {
                findings.add(new Finding("INFO", "OSGI_JPMS_NAME_DIFFERENCE", artifact.path(),
                        "Bundle-SymbolicName and JPMS module name differ; do not silently rename either"));
            }
            requiresOsgi(artifact, "Import-Package", "OSGI_PACKAGE_WIRING", findings,
                    "Package version ranges and uses constraints must remain with the OSGi resolver");
            requiresOsgi(artifact, "DynamicImport-Package", "OSGI_DYNAMIC_IMPORT", findings,
                    "JPMS does not provide OSGi dynamic import semantics");
            requiresOsgi(artifact, "Fragment-Host", "OSGI_FRAGMENT", findings,
                    "Fragments require OSGi host attachment semantics");
            requiresOsgi(artifact, "Bundle-NativeCode", "OSGI_NATIVE_CODE", findings,
                    "Native selection must obey OSGi environment filters and target platform");
        }
        findings.sort(Comparator.comparing((Finding f) -> f.path().toString())
                .thenComparing(Finding::code).thenComparing(Finding::detail));
        return List.copyOf(findings);
    }

    private static boolean isJdkJmod(ModuleArtifact artifact) {
        return artifact.kind() == ModuleArtifact.Kind.JMOD
                && artifact.path().getParent() != null
                && artifact.path().getParent().getFileName().toString().equals("jmods");
    }

    private static void requiresOsgi(ModuleArtifact artifact, String header,
                                     String code, List<Finding> findings, String detail) {
        String value = artifact.manifestHeaders().get(header);
        if (value != null && !value.isBlank()) {
            findings.add(new Finding("INFO", code, artifact.path(), detail));
        }
    }
}

/*
 * Copyright 2026 Synexia contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.synexia.felix.modules;

import java.lang.module.ModuleDescriptor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Read-only comparison of JMOD evidence from separate JDK installations.
 * A difference is not an OSGi resolution error or evidence of binary incompatibility.
 */
public final class JdkModuleDiff {
    private JdkModuleDiff() { }

    public record Change(String module, Path baselineHome, Path candidateHome,
                         String aspect, List<String> baseline, List<String> candidate) {
        public Change {
            baseline = List.copyOf(baseline);
            candidate = List.copyOf(candidate);
        }
    }

    /**
     * Compare each supplied installation against the lexically first JDK home.
     * Only JMODs directly within one of the declared {@code jmods/} directories
     * participate; unrelated {@code --scan} inputs cannot contaminate the result.
     */
    public static List<Change> compare(List<ModuleArtifact> artifacts,
                                       List<ModuleInventory.JdkInstallation> installations) {
        Map<Path, Map<String, ModuleArtifact>> byHome = new TreeMap<>(Comparator.comparing(Path::toString));
        for (ModuleInventory.JdkInstallation installation : installations) {
            byHome.putIfAbsent(installation.home(), new TreeMap<>());
        }
        if (byHome.size() < 2) return List.of();

        for (ModuleArtifact artifact : artifacts) {
            if (artifact.kind() != ModuleArtifact.Kind.JMOD) continue;
            Path parent = artifact.path().getParent();
            if (parent == null) continue;
            Path home = parent.getParent();
            if (home == null) continue;
            Map<String, ModuleArtifact> modules = byHome.get(home);
            if (modules == null || !parent.equals(home.resolve("jmods"))) continue;
            String module = artifact.jpmsName().orElseThrow(() ->
                    new IllegalArgumentException("JMOD has no JPMS module descriptor: " + artifact.path()));
            ModuleArtifact previous = modules.putIfAbsent(module, artifact);
            if (previous != null && !previous.path().equals(artifact.path())) {
                throw new IllegalArgumentException("Duplicate JMOD module " + module + " under " + parent);
            }
        }

        List<Path> homes = List.copyOf(byHome.keySet());
        Path baselineHome = homes.get(0);
        Map<String, ModuleArtifact> baseline = byHome.get(baselineHome);
        List<Change> changes = new ArrayList<>();
        for (int i = 1; i < homes.size(); i++) {
            Path candidateHome = homes.get(i);
            Map<String, ModuleArtifact> candidate = byHome.get(candidateHome);
            Set<String> names = new TreeSet<>(baseline.keySet());
            names.addAll(candidate.keySet());
            for (String module : names) {
                ModuleArtifact left = baseline.get(module);
                ModuleArtifact right = candidate.get(module);
                if (left == null || right == null) {
                    add(changes, module, baselineHome, candidateHome, "PRESENCE",
                            left == null ? List.of() : List.of("present"),
                            right == null ? List.of() : List.of("present"));
                    continue;
                }
                ModuleDescriptor a = left.jpms();
                ModuleDescriptor b = right.jpms();
                compareDescriptor(changes, module, baselineHome, candidateHome, a, b);
                add(changes, module, baselineHome, candidateHome, "JMOD_SHA256",
                        List.of(left.sha256()), List.of(right.sha256()));
            }
        }
        changes.sort(Comparator.comparing(Change::module)
                .thenComparing(change -> change.candidateHome().toString())
                .thenComparing(Change::aspect));
        return List.copyOf(changes);
    }

    private static void compareDescriptor(List<Change> changes, String module,
                                          Path baseline, Path candidate,
                                          ModuleDescriptor a, ModuleDescriptor b) {
        add(changes, module, baseline, candidate, "VERSION", one(a.rawVersion().orElse(null)),
                one(b.rawVersion().orElse(null)));
        add(changes, module, baseline, candidate, "OPEN_MODULE", one(Boolean.toString(a.isOpen())),
                one(Boolean.toString(b.isOpen())));
        add(changes, module, baseline, candidate, "MODIFIERS", enumNames(a.modifiers()),
                enumNames(b.modifiers()));
        add(changes, module, baseline, candidate, "MAIN_CLASS", one(a.mainClass().orElse(null)),
                one(b.mainClass().orElse(null)));
        add(changes, module, baseline, candidate, "REQUIRES", sorted(a.requires(), r ->
                r.name() + " | " + enumNames(r.modifiers()) + " | "
                        + r.rawCompiledVersion().orElse("")), sorted(b.requires(), r ->
                r.name() + " | " + enumNames(r.modifiers()) + " | "
                        + r.rawCompiledVersion().orElse("")));
        add(changes, module, baseline, candidate, "EXPORTS", sorted(a.exports(), e ->
                e.source() + " | " + enumNames(e.modifiers()) + " | " + sortedStrings(e.targets())),
                sorted(b.exports(), e -> e.source() + " | " + enumNames(e.modifiers())
                        + " | " + sortedStrings(e.targets())));
        add(changes, module, baseline, candidate, "OPENS", sorted(a.opens(), e ->
                e.source() + " | " + enumNames(e.modifiers()) + " | " + sortedStrings(e.targets())),
                sorted(b.opens(), e -> e.source() + " | " + enumNames(e.modifiers())
                        + " | " + sortedStrings(e.targets())));
        add(changes, module, baseline, candidate, "USES", sortedStrings(a.uses()),
                sortedStrings(b.uses()));
        add(changes, module, baseline, candidate, "PROVIDES", sorted(a.provides(), p ->
                p.service() + " | " + p.providers()), sorted(b.provides(), p ->
                p.service() + " | " + p.providers()));
        add(changes, module, baseline, candidate, "PACKAGES", sortedStrings(a.packages()),
                sortedStrings(b.packages()));
    }

    private static void add(List<Change> changes, String module, Path baselineHome,
                            Path candidateHome, String aspect, List<String> baseline,
                            List<String> candidate) {
        if (!baseline.equals(candidate)) {
            changes.add(new Change(module, baselineHome, candidateHome, aspect, baseline, candidate));
        }
    }

    private static List<String> one(String value) {
        return value == null ? List.of() : List.of(value);
    }

    private static List<String> enumNames(Set<? extends Enum<?>> values) {
        return sorted(values, Enum::name);
    }

    private static List<String> sortedStrings(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static <T> List<String> sorted(Set<T> values, Function<T, String> render) {
        return values.stream().map(render).sorted().toList();
    }
}

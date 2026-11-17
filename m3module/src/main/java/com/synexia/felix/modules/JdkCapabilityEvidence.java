/*
 * Copyright 2026 Synexia contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.synexia.felix.modules;

import java.lang.module.ModuleDescriptor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Evidence only, not a claim that a runtime feature is enabled or usable.
 * In particular a VM vendor name alone never establishes a capability.
 */
public final class JdkCapabilityEvidence {
    private JdkCapabilityEvidence() { }

    private static final Map<String, String> MODULE_FEATURES = Map.ofEntries(
            Map.entry("java.compiler", "java.compiler-api"),
            Map.entry("jdk.jfr", "jdk.flight-recorder-module"),
            Map.entry("jdk.management.jfr", "jdk.flight-recorder-management-module"),
            Map.entry("jdk.jlink", "jdk.image-linker-module"),
            Map.entry("jdk.incubator.vector", "jdk.vector-incubator-module"),
            Map.entry("jdk.internal.vm.ci", "jdk.jvmci-module"),
            Map.entry("jdk.graal.compiler", "jdk.graal-compiler-module"),
            Map.entry("jdk.crac", "jdk.crac-module"));

    public record Signal(String feature, String evidence, String module, String origin) { }

    /** Signals in the running image and in separately inventoried JDK JMODs. */
    public static List<Signal> inspect(ModuleInventory.RuntimeSnapshot runtime,
                                       List<ModuleArtifact> artifacts) {
        List<Signal> signals = new ArrayList<>();
        if (runtime != null) {
            Set<String> installed = runtime.modules().stream().map(ModuleDescriptor::name)
                    .collect(Collectors.toSet());
            for (Map.Entry<String, String> rule : MODULE_FEATURES.entrySet()) {
                if (installed.contains(rule.getKey())) {
                    signals.add(new Signal(rule.getValue(), "SYSTEM_MODULE_DESCRIPTOR",
                            rule.getKey(), "runtime-image"));
                }
            }
        }
        for (ModuleArtifact artifact : artifacts) {
            if (artifact.kind() == ModuleArtifact.Kind.JMOD && artifact.jpms() != null) {
                String module = artifact.jpms().name();
                String feature = MODULE_FEATURES.get(module);
                if (feature != null) {
                    signals.add(new Signal(feature, "JMOD_DESCRIPTOR",
                            module, artifact.path().toString()));
                }
            }
        }
        signals.sort(Comparator.comparing(Signal::feature).thenComparing(Signal::evidence)
                .thenComparing(Signal::module).thenComparing(Signal::origin));
        return List.copyOf(signals);
    }
}

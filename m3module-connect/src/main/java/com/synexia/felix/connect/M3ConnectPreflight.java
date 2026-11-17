/*
 * Copyright 2026 Synexia contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.synexia.felix.connect;

import com.synexia.felix.modules.CatalogJson;
import com.synexia.felix.modules.ModuleArtifact;
import com.synexia.felix.modules.ModuleCompatibility;
import com.synexia.felix.modules.ModuleInventory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleException;
import org.osgi.framework.connect.ConnectModule;
import org.osgi.framework.connect.ModuleConnector;

/**
 * Read-only preflight around an existing OSGi Connect connector such as Atomos.
 * It does not synthesize bundles or change OSGi/JPMS resolution rules.
 */
public final class M3ConnectPreflight implements ModuleConnector {
    private final ModuleConnector delegate;
    private final List<ModuleArtifact> artifacts;
    private final List<ModuleCompatibility.Finding> findings;

    public M3ConnectPreflight(ModuleConnector delegate, List<Path> inputs) throws IOException {
        this(delegate, inputs, false);
    }

    /**
     * @param rejectConflicts when true, reject duplicate JPMS names before
     *                        the framework starts; the default is report-only
     */
    public M3ConnectPreflight(ModuleConnector delegate, List<Path> inputs,
                              boolean rejectConflicts) throws IOException {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.artifacts = ModuleInventory.scan(List.copyOf(Objects.requireNonNull(inputs, "inputs")));
        this.findings = ModuleCompatibility.audit(artifacts);
        if (rejectConflicts && findings.stream().anyMatch(f -> f.level().equals("ERROR"))) {
            throw new IllegalArgumentException("M3 module preflight found conflicting JPMS modules: "
                    + findings.stream().filter(f -> f.level().equals("ERROR"))
                    .map(ModuleCompatibility.Finding::detail).toList());
        }
    }

    public List<ModuleArtifact> artifacts() {
        return artifacts;
    }

    public List<ModuleCompatibility.Finding> findings() {
        return findings;
    }

    /** Stable evidence for the build log or M3Scale journal. */
    public String evidenceJson() {
        return CatalogJson.render(artifacts, ModuleInventory.currentRuntime());
    }

    @Override
    public void initialize(File storage, Map<String, String> configuration) {
        delegate.initialize(storage, configuration);
    }

    @Override
    public Optional<ConnectModule> connect(String location) throws BundleException {
        return delegate.connect(location);
    }

    @Override
    public Optional<BundleActivator> newBundleActivator() {
        return delegate.newBundleActivator();
    }
}

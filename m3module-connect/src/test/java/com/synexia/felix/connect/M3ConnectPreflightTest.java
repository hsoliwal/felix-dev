/*
 * Copyright 2026 Synexia contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.synexia.felix.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.connect.ConnectModule;
import org.osgi.framework.connect.ModuleConnector;

class M3ConnectPreflightTest {
    @TempDir
    Path temp;

    @Test
    void preservesExactConnectorCallsAndResults() throws Exception {
        RecordingConnector delegate = new RecordingConnector();
        M3ConnectPreflight wrapper = new M3ConnectPreflight(delegate, List.of());
        File storage = temp.resolve("felix-cache").toFile();
        Map<String, String> config = Map.of("framework.setting", "unchanged");

        wrapper.initialize(storage, config);
        assertSame(storage, delegate.storage);
        assertSame(config, delegate.configuration);
        assertEquals(1, delegate.initializeCalls);

        Optional<ConnectModule> connected = wrapper.connect("custom:original-location");
        assertSame(delegate.connected, connected);
        assertEquals("custom:original-location", delegate.lastLocation);
        assertEquals(1, delegate.connectCalls);

        Optional<BundleActivator> activator = wrapper.newBundleActivator();
        assertSame(delegate.activator, activator);
        assertEquals(1, delegate.activatorCalls);
        assertTrue(wrapper.artifacts().isEmpty());
        assertTrue(wrapper.findings().isEmpty());
        assertTrue(wrapper.evidenceJson().contains("com.synexia.m3module.catalog/v1"));

        BundleException original = new BundleException("original failure");
        delegate.failure = original;
        assertSame(original, assertThrows(BundleException.class,
                () -> wrapper.connect("custom:broken")));
        assertEquals("custom:broken", delegate.lastLocation);
        assertEquals(2, delegate.connectCalls);
    }

    @Test
    void reportsConflictsWithoutMutatingDelegateOrManifest() throws Exception {
        Path first = jar("first.jar", "shared.module", "bundle.first");
        Path second = jar("second.jar", "shared.module", "bundle.second");
        RecordingConnector delegate = new RecordingConnector();

        M3ConnectPreflight wrapper = new M3ConnectPreflight(delegate, List.of(second, first));
        assertEquals(2, wrapper.artifacts().size());
        assertEquals(first.toAbsolutePath(), wrapper.artifacts().get(0).path());
        assertTrue(wrapper.findings().stream().anyMatch(f ->
                f.code().equals("DUPLICATE_JPMS_MODULE") && f.level().equals("ERROR")));
        assertEquals("bundle.first", wrapper.artifacts().get(0).osgiSymbolicName().orElseThrow());
        assertEquals("shared.module", wrapper.artifacts().get(0).jpmsName().orElseThrow());
        assertTrue(wrapper.findings().stream().anyMatch(f ->
                f.code().equals("OSGI_JPMS_NAME_DIFFERENCE")));
        assertFalse(wrapper.evidenceJson().isBlank());
        assertEquals(0, delegate.initializeCalls);

        assertThrows(IllegalArgumentException.class,
                () -> new M3ConnectPreflight(delegate, List.of(first, second), true));
        assertEquals(0, delegate.initializeCalls);
    }

    private Path jar(String file, String module, String symbolicName) throws IOException {
        Path path = temp.resolve(file);
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.putValue("Manifest-Version", "1.0");
        main.putValue("Automatic-Module-Name", module);
        main.putValue("Bundle-SymbolicName", symbolicName);
        try (JarOutputStream out = new JarOutputStream(java.nio.file.Files.newOutputStream(path), manifest)) {
            out.putNextEntry(new JarEntry("evidence.txt"));
            out.write("unchanged".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return path;
    }

    private static final class RecordingConnector implements ModuleConnector {
        private final Optional<ConnectModule> connected = Optional.empty();
        private final Optional<BundleActivator> activator = Optional.of(new BundleActivator() {
            @Override public void start(BundleContext context) { }
            @Override public void stop(BundleContext context) { }
        });
        private File storage;
        private Map<String, String> configuration;
        private String lastLocation;
        private int initializeCalls;
        private int connectCalls;
        private int activatorCalls;
        private BundleException failure;

        @Override
        public void initialize(File storage, Map<String, String> configuration) {
            this.storage = storage;
            this.configuration = configuration;
            initializeCalls++;
        }

        @Override
        public Optional<ConnectModule> connect(String location) throws BundleException {
            this.lastLocation = location;
            connectCalls++;
            if (failure != null) throw failure;
            return connected;
        }

        @Override
        public Optional<BundleActivator> newBundleActivator() {
            activatorCalls++;
            return activator;
        }
    }
}

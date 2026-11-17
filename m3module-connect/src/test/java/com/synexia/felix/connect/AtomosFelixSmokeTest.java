/*
 * Copyright 2026 Synexia contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.synexia.felix.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.felix.atomos.Atomos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;

/** Exercises the real Atomos connector against the real Apache Felix framework. */
class AtomosFelixSmokeTest {
    @TempDir
    Path temp;

    @Test
    void startsAndStopsFelixThroughUnmodifiedAtomosSemantics() throws Exception {
        Atomos atomos = Atomos.newAtomos();
        M3ConnectPreflight connector = new M3ConnectPreflight(atomos.getModuleConnector(), List.of());
        Map<String, String> config = new HashMap<>();
        config.put(Constants.FRAMEWORK_SYSTEMPACKAGES, "");
        config.put(Constants.FRAMEWORK_STORAGE, temp.resolve("osgi-cache").toString());
        config.put("atomos.content.install", "false");
        config.put("atomos.content.start", "false");

        Framework framework = new org.apache.felix.framework.FrameworkFactory()
                .newFramework(config, connector);
        try {
            framework.init();
            framework.start();
            assertEquals(Bundle.ACTIVE, framework.getState());
            assertTrue(connector.evidenceJson().contains("\"runtime\""));
        } finally {
            framework.stop();
            framework.waitForStop(10_000);
        }
    }
}

/*
 * Copyright 2026 Synexia contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.synexia.felix.modules;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Read-only CLI: module path and JMOD inventory for Maven/Gradle/Atomos input. */
public final class ModuleCatalogCli {
    private ModuleCatalogCli() { }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        boolean includeRuntime = false;
        boolean includeCapabilityEvidence = false;
        boolean compareJdkModules = false;
        List<Path> inputs = new ArrayList<>();
        List<ModuleInventory.JdkInstallation> jdkInstallations = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--runtime" -> includeRuntime = true;
                case "--features" -> includeCapabilityEvidence = true;
                case "--compare-jdks" -> compareJdkModules = true;
                case "--scan" -> {
                    if (++i >= args.length) return usage(err);
                    inputs.add(Path.of(args[i]));
                }
                case "--jdk-home" -> {
                    if (++i >= args.length) return usage(err);
                    Path home = Path.of(args[i]);
                    try {
                        jdkInstallations.add(ModuleInventory.inspectJdkHome(home));
                    } catch (IOException e) {
                        err.println("M3 module inventory failed: " + e.getMessage());
                        return 1;
                    }
                    inputs.add(home.resolve("jmods"));
                }
                default -> { return usage(err); }
            }
        }
        if (!includeRuntime && !includeCapabilityEvidence && inputs.isEmpty()) return usage(err);
        if (compareJdkModules && jdkInstallations.stream().map(ModuleInventory.JdkInstallation::home)
                .distinct().count() < 2) {
            err.println("--compare-jdks requires at least two distinct --jdk-home inputs");
            return usage(err);
        }
        try {
            out.println(CatalogJson.render(ModuleInventory.scan(inputs),
                    includeRuntime || includeCapabilityEvidence ? ModuleInventory.currentRuntime() : null,
                    jdkInstallations, includeCapabilityEvidence, compareJdkModules));
            return 0;
        } catch (IOException | IllegalArgumentException ex) {
            err.println("M3 module inventory failed: " + ex.getMessage());
            return 1;
        }
    }

    private static int usage(PrintStream err) {
        err.println("Usage: ModuleCatalogCli [--runtime] [--features] [--compare-jdks] "
                + "[--scan jar|jmod|directory]... [--jdk-home /path/to/jdk]...");
        return 2;
    }
}

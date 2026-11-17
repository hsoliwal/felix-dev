/*
 * Copyright 2026 Synexia contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package com.synexia.felix.modules;

import java.lang.module.ModuleDescriptor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable JSON report without a third-party runtime dependency. */
public final class CatalogJson {
    private CatalogJson() { }

    public static String render(List<ModuleArtifact> artifacts, ModuleInventory.RuntimeSnapshot runtime) {
        return render(artifacts, runtime, List.of());
    }

    public static String render(List<ModuleArtifact> artifacts, ModuleInventory.RuntimeSnapshot runtime,
                                List<ModuleInventory.JdkInstallation> installations) {
        return render(artifacts, runtime, installations, false);
    }

    /** Optional capability evidence; the existing v1 JSON shape remains the default. */
    public static String render(List<ModuleArtifact> artifacts, ModuleInventory.RuntimeSnapshot runtime,
                                List<ModuleInventory.JdkInstallation> installations,
                                boolean includeCapabilityEvidence) {
        return render(artifacts, runtime, installations, includeCapabilityEvidence, false);
    }

    /** Independent opt-in sections do not alter the default v1 JSON contract. */
    public static String render(List<ModuleArtifact> artifacts, ModuleInventory.RuntimeSnapshot runtime,
                                List<ModuleInventory.JdkInstallation> installations,
                                boolean includeCapabilityEvidence, boolean compareJdkModules) {
        Map<String, Object> report = object();
        report.put("schema", "com.synexia.m3module.catalog/v1");
        report.put("runtime", runtime == null ? null : runtime(runtime));
        report.put("jdkInstallations", installations.stream()
                .sorted(Comparator.comparing(i -> i.home().toString()))
                .map(installation -> {
                    Map<String, Object> entry = object();
                    entry.put("home", installation.home().toString());
                    entry.put("release", installation.releaseFields());
                    return entry;
                }).toList());
        List<Object> entries = new ArrayList<>();
        for (ModuleArtifact artifact : artifacts) {
            Map<String, Object> entry = object();
            entry.put("path", artifact.path().toString());
            entry.put("kind", artifact.kind().name());
            entry.put("sha256", artifact.sha256());
            entry.put("jpms", artifact.jpms() == null ? null : descriptor(artifact.jpms()));
            entry.put("osgiSymbolicName", artifact.osgiSymbolicName().orElse(null));
            entry.put("osgiVersion", artifact.osgiVersion().orElse(null));
            entry.put("manifestHeaders", artifact.manifestHeaders());
            entry.put("jmodNativePaths", artifact.jmodNativePaths());
            entry.put("diagnostics", artifact.diagnostics());
            entries.add(entry);
        }
        report.put("artifacts", entries);
        List<Object> findings = new ArrayList<>();
        for (ModuleCompatibility.Finding finding : ModuleCompatibility.audit(artifacts)) {
            Map<String, Object> entry = object();
            entry.put("level", finding.level());
            entry.put("code", finding.code());
            entry.put("path", finding.path().toString());
            entry.put("detail", finding.detail());
            findings.add(entry);
        }
        report.put("findings", findings);
        if (includeCapabilityEvidence) {
            report.put("capabilityEvidence", JdkCapabilityEvidence.inspect(runtime, artifacts).stream()
                    .map(signal -> {
                        Map<String, Object> entry = object();
                        entry.put("feature", signal.feature());
                        entry.put("evidence", signal.evidence());
                        entry.put("module", signal.module());
                        entry.put("origin", signal.origin());
                        return entry;
                    }).toList());
        }
        if (compareJdkModules) {
            report.put("jdkModuleDifferences", JdkModuleDiff.compare(artifacts, installations).stream()
                    .map(change -> {
                        Map<String, Object> entry = object();
                        entry.put("module", change.module());
                        entry.put("baselineHome", change.baselineHome().toString());
                        entry.put("candidateHome", change.candidateHome().toString());
                        entry.put("aspect", change.aspect());
                        entry.put("baseline", change.baseline());
                        entry.put("candidate", change.candidate());
                        return entry;
                    }).toList());
        }
        StringBuilder out = new StringBuilder();
        write(out, report);
        return out.toString();
    }

    private static Map<String, Object> runtime(ModuleInventory.RuntimeSnapshot snapshot) {
        Map<String, Object> result = object();
        result.put("javaVersion", snapshot.javaVersion());
        result.put("vendor", snapshot.vendor());
        result.put("vmName", snapshot.vmName());
        result.put("osName", snapshot.osName());
        result.put("architecture", snapshot.architecture());
        result.put("modules", snapshot.modules().stream().map(CatalogJson::descriptor).toList());
        return result;
    }

    private static Map<String, Object> descriptor(ModuleDescriptor descriptor) {
        Map<String, Object> result = object();
        result.put("name", descriptor.name());
        result.put("version", descriptor.version().map(Object::toString).orElse(null));
        result.put("automatic", descriptor.isAutomatic());
        result.put("open", descriptor.isOpen());
        result.put("modifiers", descriptor.modifiers().stream().map(Enum::name).sorted().toList());
        result.put("mainClass", descriptor.mainClass().orElse(null));

        result.put("requires", descriptor.requires().stream()
                .sorted(Comparator.comparing(ModuleDescriptor.Requires::name))
                .map(requirement -> {
                    Map<String, Object> element = object();
                    element.put("name", requirement.name());
                    element.put("modifiers", requirement.modifiers().stream()
                            .map(Enum::name).sorted().toList());
                    element.put("compiledVersion", requirement.compiledVersion()
                            .map(Object::toString).orElse(null));
                    return element;
                }).toList());
        result.put("exports", descriptor.exports().stream()
                .sorted(Comparator.comparing(ModuleDescriptor.Exports::source))
                .map(export -> access(export.source(), export.targets(),
                        export.modifiers().stream().map(Enum::name).sorted().toList())).toList());
        result.put("opens", descriptor.opens().stream()
                .sorted(Comparator.comparing(ModuleDescriptor.Opens::source))
                .map(open -> access(open.source(), open.targets(),
                        open.modifiers().stream().map(Enum::name).sorted().toList())).toList());
        result.put("uses", descriptor.uses().stream().sorted().toList());
        result.put("provides", descriptor.provides().stream()
                .sorted(Comparator.comparing(ModuleDescriptor.Provides::service))
                .map(provide -> {
                    Map<String, Object> element = object();
                    element.put("service", provide.service());
                    element.put("providers", List.copyOf(provide.providers()));
                    return element;
                }).toList());
        result.put("packages", descriptor.packages().stream().sorted().toList());
        return result;
    }

    private static Map<String, Object> access(String source, Collection<String> targets,
                                               List<String> modifiers) {
        Map<String, Object> entry = object();
        entry.put("package", source);
        entry.put("targets", targets.stream().sorted().toList());
        entry.put("modifiers", modifiers);
        return entry;
    }

    private static Map<String, Object> object() {
        return new LinkedHashMap<>();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            quote(out, text);
        } else if (value instanceof Boolean bool) {
            out.append(bool);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(out, (String) entry.getKey());
                out.append(':');
                write(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Collection<?> collection) {
            out.append('[');
            boolean first = true;
            for (Object item : collection) {
                if (!first) out.append(',');
                first = false;
                write(out, item);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value " + value.getClass());
        }
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}

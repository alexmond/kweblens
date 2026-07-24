package org.alexmond.kweblens.metric;

/**
 * CPU/memory usage for one node or pod, formatted for display (cpu in millicores like
 * {@code 120m}, memory in mebibytes like {@code 512Mi}).
 *
 * @param name node or pod name
 * @param namespace namespace (null for nodes)
 * @param cpu CPU usage, millicores
 * @param memory memory usage, mebibytes
 */
public record UsageSummary(String name, String namespace, String cpu, String memory) {
}

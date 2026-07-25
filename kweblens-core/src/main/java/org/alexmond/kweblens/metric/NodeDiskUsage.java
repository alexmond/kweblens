package org.alexmond.kweblens.metric;

/**
 * Disk usage for a node, aggregated across all real filesystems, from node-exporter
 * metrics in a Prometheus-compatible backend (metrics-server does not expose disk).
 *
 * @param node the Kubernetes node name
 * @param usedBytes used bytes across all real filesystems
 * @param totalBytes total bytes across all real filesystems
 */
public record NodeDiskUsage(String node, long usedBytes, long totalBytes) {
}

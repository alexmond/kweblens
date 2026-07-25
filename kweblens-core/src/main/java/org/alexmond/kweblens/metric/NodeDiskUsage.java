package org.alexmond.kweblens.metric;

/**
 * Root-filesystem disk usage for a node, from node-exporter metrics in a
 * Prometheus-compatible backend (metrics-server does not expose disk).
 *
 * @param node the Kubernetes node name
 * @param usedBytes used bytes on the root filesystem
 * @param totalBytes total bytes on the root filesystem
 */
public record NodeDiskUsage(String node, long usedBytes, long totalBytes) {
}

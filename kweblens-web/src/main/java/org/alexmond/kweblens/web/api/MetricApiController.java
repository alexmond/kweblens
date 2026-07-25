package org.alexmond.kweblens.web.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.metric.MetricSeries;
import org.alexmond.kweblens.metric.MetricService;
import org.alexmond.kweblens.metric.NodeDiskUsage;
import org.alexmond.kweblens.metric.PrometheusMetricService;
import org.alexmond.kweblens.metric.UsageSummary;

/**
 * Read-only JSON API over metrics-server usage. Returns an empty array (not an error)
 * when metrics-server is not installed.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/metrics")
@RequiredArgsConstructor
public class MetricApiController {

	private final MetricService metrics;

	private final PrometheusMetricService prometheus;

	@GetMapping("/nodes")
	public List<UsageSummary> nodes(@PathVariable String clusterId) {
		return metrics.nodeUsage(clusterId);
	}

	@GetMapping("/pods")
	public List<UsageSummary> pods(@PathVariable String clusterId, @RequestParam(required = false) String namespace) {
		return metrics.podUsage(clusterId, namespace);
	}

	/**
	 * Per-node root-filesystem disk usage from Prometheus/node-exporter (empty if none).
	 */
	@GetMapping("/nodes/disk")
	public List<NodeDiskUsage> nodeDisk(@PathVariable String clusterId) {
		return prometheus.nodeDiskUsage(clusterId);
	}

	/**
	 * Whether a Prometheus-compatible backend was discovered (so charts are possible).
	 */
	@GetMapping("/prometheus")
	public Map<String, Object> prometheus(@PathVariable String clusterId) {
		Optional<String> endpoint = prometheus.endpoint(clusterId);
		return Map.of("available", endpoint.isPresent(), "service", endpoint.orElse(""));
	}

	/**
	 * A time-series chart for a fixed {@code target}. PromQL is built server-side from
	 * the target (never taken raw from the client), with {@code namespace}/{@code name}
	 * sanitised into the query.
	 */
	@GetMapping("/graph")
	public MetricSeries graph(@PathVariable String clusterId, @RequestParam String target,
			@RequestParam(required = false) String namespace, @RequestParam(required = false) String name,
			@RequestParam(defaultValue = "60") int minutes) {
		String ns = sanitize(namespace);
		String nm = sanitize(name);
		String promql;
		String unit;
		switch (target) {
			case "pod-cpu" -> {
				promql = "sum(rate(container_cpu_usage_seconds_total{namespace=\"" + ns + "\",pod=\"" + nm
						+ "\"}[2m]))";
				unit = "cores";
			}
			case "pod-mem" -> {
				promql = "sum(container_memory_working_set_bytes{namespace=\"" + ns + "\",pod=\"" + nm + "\"})";
				unit = "bytes";
			}
			case "cluster-cpu" -> {
				promql = "sum(rate(node_cpu_seconds_total{mode!=\"idle\"}[2m]))";
				unit = "cores";
			}
			case "cluster-mem" -> {
				promql = "sum(node_memory_MemTotal_bytes - node_memory_MemAvailable_bytes)";
				unit = "bytes";
			}
			default -> {
				return MetricSeries.unavailable();
			}
		}
		return prometheus.queryRange(clusterId, promql, unit, minutes);
	}

	private String sanitize(String value) {
		return (value != null) ? value.replaceAll("[^a-zA-Z0-9._-]", "") : "";
	}

}

package org.alexmond.kweblens.web.api;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.metric.MetricService;
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

	@GetMapping("/nodes")
	public List<UsageSummary> nodes(@PathVariable String clusterId) {
		return metrics.nodeUsage(clusterId);
	}

	@GetMapping("/pods")
	public List<UsageSummary> pods(@PathVariable String clusterId, @RequestParam(required = false) String namespace) {
		return metrics.podUsage(clusterId, namespace);
	}

}

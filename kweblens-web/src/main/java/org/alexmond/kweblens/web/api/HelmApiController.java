package org.alexmond.kweblens.web.api;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.web.helm.HelmChartService;
import org.alexmond.kweblens.web.helm.HelmChartSummary;
import org.alexmond.kweblens.web.helm.HelmReleaseSummary;
import org.alexmond.kweblens.web.helm.HelmResourceRef;
import org.alexmond.kweblens.web.helm.HelmService;

/**
 * Read-only JSON API over Helm (via jhelm). Lists releases (per cluster) and their
 * status/history, plus the charts browser over the configured chart repositories.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/helm")
@RequiredArgsConstructor
public class HelmApiController {

	private final HelmService helm;

	private final HelmChartService chartService;

	@GetMapping("/charts")
	public List<HelmChartSummary> charts(@PathVariable String clusterId, @RequestParam(required = false) String query) {
		return chartService.listCharts(query);
	}

	@GetMapping("/releases")
	public List<HelmReleaseSummary> releases(@PathVariable String clusterId,
			@RequestParam(required = false) String namespace) {
		return helm.listReleases(clusterId, namespace);
	}

	@GetMapping("/releases/{namespace}/{name}")
	public ResponseEntity<HelmReleaseSummary> release(@PathVariable String clusterId, @PathVariable String namespace,
			@PathVariable String name) {
		return helm.status(clusterId, namespace, name)
			.map(ResponseEntity::ok)
			.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@GetMapping("/releases/{namespace}/{name}/history")
	public List<HelmReleaseSummary> history(@PathVariable String clusterId, @PathVariable String namespace,
			@PathVariable String name) {
		return helm.history(clusterId, namespace, name);
	}

	@GetMapping("/releases/{namespace}/{name}/resources")
	public List<HelmResourceRef> resources(@PathVariable String clusterId, @PathVariable String namespace,
			@PathVariable String name) {
		return helm.resources(clusterId, namespace, name);
	}

	/**
	 * The values a release was installed/upgraded with (Helm's stored config), as YAML.
	 */
	@GetMapping(value = "/releases/{namespace}/{name}/values", produces = "application/yaml")
	public String values(@PathVariable String clusterId, @PathVariable String namespace, @PathVariable String name) {
		return helm.releaseValues(clusterId, namespace, name);
	}

}

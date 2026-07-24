package org.alexmond.kweblens.web.api;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.web.helm.HelmReleaseSummary;
import org.alexmond.kweblens.web.helm.HelmService;

/**
 * Read-only JSON API over Helm releases (via jhelm). Lists releases, and returns the
 * status + revision history of a single release.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/helm")
@RequiredArgsConstructor
public class HelmApiController {

	private final HelmService helm;

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

}

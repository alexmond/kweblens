package org.alexmond.kweblens.web.api;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.cluster.ClusterInfo;
import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.resource.ResourceSummary;

/**
 * Read-only JSON API over the registered clusters. Mirrors the dashboard's data so the
 * same views can be driven by htmx fragments or external tooling.
 */
@RestController
@RequestMapping("/api/v1/clusters")
@RequiredArgsConstructor
public class ClusterApiController {

	private final ClusterRegistry clusters;

	private final ResourceService resources;

	@GetMapping
	public List<ClusterInfo> clusters() {
		return clusters.list();
	}

	@GetMapping("/{clusterId}/namespaces")
	public List<ResourceSummary> namespaces(@PathVariable String clusterId) {
		return resources.listNamespaces(clusterId);
	}

	@GetMapping("/{clusterId}/pods")
	public List<ResourceSummary> pods(@PathVariable String clusterId,
			@RequestParam(required = false) String namespace) {
		return resources.listPods(clusterId, namespace);
	}

}

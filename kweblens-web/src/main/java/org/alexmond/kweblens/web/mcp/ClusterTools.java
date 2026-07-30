package org.alexmond.kweblens.web.mcp;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

import org.alexmond.kweblens.cluster.ClusterInfo;
import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.resource.ResourceSummary;
import org.alexmond.kweblens.web.nav.ClusterNavService;

/**
 * Orientation tools: what clusters exist, what is in them, and what kinds can be asked
 * for.
 *
 * <p>
 * Read-only, like the whole tool surface — every method is a projection of the same
 * access layer the API and dashboard use, and no mutation is offered. Changing the
 * cluster stays on the guarded suggest → diff → approve → apply path, which is
 * deliberately not reachable from here.
 *
 * <p>
 * {@link #listResourceKinds} exists because every other tool takes a {@code resourceId},
 * and those ids are per-cluster: they include the CRDs this cluster actually serves. An
 * assistant that had to guess them would guess wrong on exactly the custom resources it
 * was asked about.
 */
@Component
@RequiredArgsConstructor
public class ClusterTools {

	private final ClusterRegistry clusters;

	private final ResourceService resources;

	private final ClusterNavService clusterNav;

	@Tool(description = "List the Kubernetes clusters kweblens is connected to.")
	public List<ClusterInfo> listClusters() {
		return clusters.list();
	}

	@Tool(description = "List the namespaces in a cluster, given its kweblens cluster id.")
	public List<ResourceSummary> listNamespaces(@ToolParam(description = "kweblens cluster id") String clusterId) {
		return resources.listNamespaces(clusterId);
	}

	@Tool(description = "List pods in a cluster, optionally scoped to one namespace.")
	public List<ResourceSummary> listPods(@ToolParam(description = "kweblens cluster id") String clusterId,
			@ToolParam(required = false, description = "namespace, or omit for all namespaces") String namespace) {
		return resources.listPods(clusterId, namespace);
	}

	@Tool(description = "List the resource kinds this cluster serves, grouped by category, as the "
			+ "resourceId values the other tools take. Includes the cluster's CRDs, so call this before "
			+ "guessing an id for a custom resource.")
	public List<Map<String, Object>> listResourceKinds(
			@ToolParam(description = "kweblens cluster id") String clusterId) {
		return this.clusterNav.categories(clusterId)
			.stream()
			.map((category) -> Map.<String, Object>of("category", category.label(), "kinds",
					category.items()
						.stream()
						.map((item) -> Map.of("resourceId", item.id(), "kind", item.kind(), "namespaced",
								String.valueOf(item.namespaced())))
						.toList()))
			.toList();
	}

}

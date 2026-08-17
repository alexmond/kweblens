package org.alexmond.kweblens.web.mcp;

import java.util.ArrayList;
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
import org.alexmond.kweblens.web.nav.NavCategory;

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
			+ "resourceId values the other tools take. Every kind appears exactly once, including the "
			+ "cluster's custom kinds: those are in a category named 'Custom Resources / <api group>', "
			+ "except the few promoted into a category of their own (e.g. Gateway). Call this before "
			+ "guessing an id for a custom resource.")
	public List<Map<String, Object>> listResourceKinds(
			@ToolParam(description = "kweblens cluster id") String clusterId) {
		List<Map<String, Object>> categories = new ArrayList<>();
		for (NavCategory category : this.clusterNav.categories(clusterId)) {
			collect(category, null, categories);
		}
		return categories;
	}

	/**
	 * One entry per category, then one per nested sub-group — the nav's <b>Custom
	 * Resources</b> holds a sub-group per API group, and a mapping of {@code items()}
	 * alone returned that category holding only {@code CustomResourceDefinition} while
	 * every custom kind the cluster serves stayed invisible (#436).
	 *
	 * <p>
	 * <b>A sub-group is emitted as its own category rather than flattened into its
	 * parent's kinds</b>, because the category name is the only context this tool gives
	 * and the reader is choosing a {@code resourceId} to pass to another tool, not
	 * rendering a menu: "Custom Resources" says only "not built-in", which the dotted id
	 * already says, whereas the sub-group's label is the API group — which names what
	 * defines the kind and puts the cert-manager kinds beside each other. The parent's
	 * label is kept as a prefix so the CRD-delivered ones are still identifiable as such;
	 * the result is the path a human reads down the rail.
	 *
	 * <p>
	 * The walk is recursive and takes the tree as it finds it, so a level the nav nests
	 * later cannot silently disappear the way this one did. It also cannot double-count a
	 * <b>promoted</b> kind: {@link org.alexmond.kweblens.web.nav.ClusterNavService}
	 * removes a promoted group (Gateway, whole) or kind (the VPA, part of
	 * {@code autoscaling.k8s.io}) from the sub-groups it builds, so each descriptor is in
	 * the tree once and walking it inherits that — a union of the two levels would not.
	 */
	private void collect(NavCategory category, String parentLabel, List<Map<String, Object>> out) {
		String label = (parentLabel != null) ? parentLabel + " / " + category.label() : category.label();
		out.add(Map.of("category", label, "kinds",
				category.items()
					.stream()
					.map((item) -> Map.of("resourceId", item.id(), "kind", item.kind(), "namespaced",
							String.valueOf(item.namespaced())))
					.toList()));
		for (NavCategory subgroup : category.subgroups()) {
			collect(subgroup, label, out);
		}
	}

}

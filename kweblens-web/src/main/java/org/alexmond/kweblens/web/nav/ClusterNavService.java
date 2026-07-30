package org.alexmond.kweblens.web.nav;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.resource.CrdService;
import org.alexmond.kweblens.resource.ResourceDescriptor;

/**
 * The per-cluster navigation: the static {@link NavCatalog} plus a dynamic <b>Custom
 * Resources</b> section generated from the cluster's CRDs, one category per API group.
 * Also resolves a route id back to its descriptor, whether built-in or custom.
 */
@Service
@RequiredArgsConstructor
public class ClusterNavService {

	private static final String CRD_ICON = "bi-puzzle";

	private static final String CUSTOM_RESOURCES = "Custom Resources";

	/**
	 * Gateway API is CRD-delivered but is a first-class part of the networking story, so
	 * it gets promoted out of Custom Resources into its own category — next to Network,
	 * where someone looking for ingress-shaped things will actually look, rather than
	 * buried under an API-group name.
	 *
	 * <p>
	 * Promotion is CONDITIONAL on the CRDs being installed. A static nav entry for a
	 * CRD-delivered kind would give every cluster without Gateway API a dead category
	 * that errors when clicked, so the category is only synthesised when the group is
	 * discovered.
	 */
	private static final String GATEWAY_GROUP = "gateway.networking.k8s.io";

	private static final String GATEWAY_LABEL = "Gateway";

	private static final String GATEWAY_ICON = "bi-door-open";

	private static final String NETWORK = "Network";

	/**
	 * Traffic-flow order rather than alphabetical: a GatewayClass names the
	 * implementation, a Gateway binds listeners to it, and routes attach to a Gateway.
	 * Reading top-to-bottom therefore follows a request, which is how people reason about
	 * this API — alphabetical order would put BackendTLSPolicy first, which explains
	 * nothing.
	 */
	private static final List<String> GATEWAY_KIND_ORDER = List.of("GatewayClass", "Gateway", "ListenerSet",
			"HTTPRoute", "GRPCRoute", "TLSRoute", "ReferenceGrant", "BackendTLSPolicy");

	private final NavCatalog navCatalog;

	private final CrdService crdService;

	/**
	 * The static categories, with the cluster's CRDs nested under <b>Custom Resources</b>
	 * as one collapsible sub-group per API group (alphabetical), each holding its kinds.
	 */
	public List<NavCategory> categories(String clusterId) {
		Map<String, List<ResourceDescriptor>> byGroup = crdService.customResourceDescriptors(clusterId)
			.stream()
			.collect(Collectors.groupingBy(ResourceDescriptor::group, TreeMap::new, Collectors.toList()));
		// Gateway API is promoted to its own category, so remove it here — otherwise the
		// same
		// kinds would appear twice, once promoted and once under Custom Resources.
		List<ResourceDescriptor> gatewayKinds = byGroup.remove(GATEWAY_GROUP);
		List<NavCategory> groups = new ArrayList<>();
		byGroup.forEach((group, items) -> {
			items.sort(Comparator.comparing(ResourceDescriptor::label));
			groups.add(new NavCategory(group, CRD_ICON, items));
		});

		List<NavCategory> categories = new ArrayList<>();
		for (NavCategory category : navCatalog.categories()) {
			if (category.label().equals(CUSTOM_RESOURCES)) {
				categories.add(new NavCategory(category.label(), category.icon(), category.items(), groups));
			}
			else {
				categories.add(category);
			}
			// Placed immediately after Network, because Gateway API is the successor to
			// Ingress and belongs beside it rather than at the end of the menu.
			if (category.label().equals(NETWORK) && gatewayKinds != null && !gatewayKinds.isEmpty()) {
				categories.add(gatewayCategory(gatewayKinds));
			}
		}
		return categories;
	}

	/**
	 * The promoted Gateway category, ordered by traffic flow rather than alphabetically.
	 */
	private NavCategory gatewayCategory(List<ResourceDescriptor> kinds) {
		List<ResourceDescriptor> ordered = new ArrayList<>(kinds);
		// Kinds outside the known order (a newer Gateway API version, or an extension of
		// the
		// same group) sort after the known ones rather than being dropped.
		ordered.sort(Comparator.comparingInt((ResourceDescriptor d) -> {
			int i = GATEWAY_KIND_ORDER.indexOf(d.kind());
			return (i >= 0) ? i : GATEWAY_KIND_ORDER.size();
		}).thenComparing(ResourceDescriptor::label));
		return new NavCategory(GATEWAY_LABEL, GATEWAY_ICON, ordered);
	}

	/** Resolve a route id to its descriptor — built-in first, then the cluster's CRDs. */
	public Optional<ResourceDescriptor> find(String clusterId, String id) {
		return navCatalog.find(id)
			.or(() -> crdService.customResourceDescriptors(clusterId)
				.stream()
				.filter((d) -> d.id().equals(id))
				.findFirst());
	}

}

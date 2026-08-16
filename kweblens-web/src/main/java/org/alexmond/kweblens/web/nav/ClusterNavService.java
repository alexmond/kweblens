package org.alexmond.kweblens.web.nav;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 *
 * <p>
 * Two categories take CRD-delivered kinds rather than only declared ones: <b>Gateway</b>
 * is synthesised when its CRDs exist, and the catalog's <b>Autoscaling</b> category gains
 * the cluster's VPA kinds when theirs are installed. One rule, in both cases about the
 * CRD-delivered kinds alone: a kind the cluster's API does not serve is not put in the
 * menu. Everything here is presentation — {@link #find} resolves every catalog route id
 * on every cluster.
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

	/**
	 * Vertical Pod Autoscaling is CRD-delivered, so its kinds land under an API-group
	 * name in Custom Resources while the built-in HorizontalPodAutoscaler sits in a
	 * static category — the two answers to "what scales this workload" filed in two
	 * places, neither of them called autoscaling (#428). They are joined here, in the
	 * catalog's declared <b>Autoscaling</b> category.
	 */
	private static final String AUTOSCALING_GROUP = "autoscaling.k8s.io";

	private static final String AUTOSCALING = "Autoscaling";

	/**
	 * Ordered by <b>what each kind changes</b>, not alphabetically: an HPA changes how
	 * many pods there are, a VPA changes how big each one is, and "how many" is the
	 * question asked first when a workload's capacity moves. HPA leading also matches the
	 * only kind here every cluster serves. The two happen to be alphabetical as well —
	 * written down so a third kind lands by the rule rather than by its spelling.
	 */
	private static final List<String> AUTOSCALING_KIND_ORDER = List.of("HorizontalPodAutoscaler",
			"VerticalPodAutoscaler");

	/**
	 * Kinds in {@link #AUTOSCALING_GROUP} that are the autoscaler's own machinery rather
	 * than something an operator browses, so promoting them would crowd out the objects
	 * that are.
	 *
	 * <p>
	 * A VerticalPodAutoscalerCheckpoint is the recommender's saved histogram for one
	 * target — the cluster writes them, nothing reads them by hand, and there are more of
	 * them than there are VPAs (37 against 31 on the cluster this was filed from). The
	 * recommendation an operator actually wants is on the VPA's own {@code status}. It is
	 * <b>demoted, not hidden</b>: it stays under Custom Resources with its API group,
	 * where every other CRD kind lives, and its route id resolves as before.
	 */
	private static final Set<String> AUTOSCALING_MACHINERY = Set.of("VerticalPodAutoscalerCheckpoint");

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
		List<ResourceDescriptor> autoscalingKinds = takeAutoscalingKinds(byGroup);
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
			else if (category.label().equals(AUTOSCALING)) {
				categories.add(autoscalingCategory(category, autoscalingKinds));
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
		return new NavCategory(GATEWAY_LABEL, GATEWAY_ICON, ordered(kinds, GATEWAY_KIND_ORDER));
	}

	/**
	 * The autoscaling kinds this cluster's CRDs deliver, removed from the group map so
	 * they are not rendered twice — the same reason Gateway's group is removed whole.
	 *
	 * <p>
	 * Unlike Gateway this takes only <i>part</i> of its group: the machinery kinds stay
	 * behind under {@code autoscaling.k8s.io}, so the group survives the promotion rather
	 * than disappearing with it.
	 */
	private List<ResourceDescriptor> takeAutoscalingKinds(Map<String, List<ResourceDescriptor>> byGroup) {
		List<ResourceDescriptor> group = byGroup.get(AUTOSCALING_GROUP);
		if (group == null) {
			return List.of();
		}
		List<ResourceDescriptor> promoted = group.stream()
			.filter((d) -> !AUTOSCALING_MACHINERY.contains(d.kind()))
			.toList();
		List<ResourceDescriptor> left = new ArrayList<>(
				group.stream().filter((d) -> AUTOSCALING_MACHINERY.contains(d.kind())).toList());
		if (left.isEmpty()) {
			byGroup.remove(AUTOSCALING_GROUP);
		}
		else {
			byGroup.put(AUTOSCALING_GROUP, left);
		}
		return promoted;
	}

	/**
	 * The catalog's Autoscaling category with this cluster's VPA kinds added.
	 *
	 * <p>
	 * <b>The category is offered on every cluster, and nothing is probed to decide
	 * it.</b> Gateway's rule hides a category whose kinds are not in the cluster's API at
	 * all — clicking one would error. That does not apply here:
	 * {@code HorizontalPodAutoscaler} is a built-in kind, {@code autoscaling/v2}, GA and
	 * served by every supported cluster, so the category always has something to navigate
	 * to. An empty list is a correct answer — it is how an operator sees "no HPAs yet" —
	 * and every one of the other 38 built-in kinds is in the menu on the same terms.
	 * Making this the one kind whose presence depends on its object count would be a new
	 * and invisible principle.
	 *
	 * <p>
	 * So only the VPA half is conditional, which is the half Gateway's rule really
	 * covers: a CRD-delivered kind appears when the CRD is installed.
	 */
	private NavCategory autoscalingCategory(NavCategory declared, List<ResourceDescriptor> discovered) {
		List<ResourceDescriptor> kinds = new ArrayList<>(declared.items());
		kinds.addAll(discovered);
		return new NavCategory(declared.label(), declared.icon(), ordered(kinds, AUTOSCALING_KIND_ORDER));
	}

	/**
	 * Kinds in a declared order. A kind outside it (a newer API version, an extension of
	 * the same group) sorts after the known ones rather than being dropped.
	 */
	private List<ResourceDescriptor> ordered(List<ResourceDescriptor> kinds, List<String> kindOrder) {
		List<ResourceDescriptor> out = new ArrayList<>(kinds);
		out.sort(Comparator.comparingInt((ResourceDescriptor d) -> {
			int i = kindOrder.indexOf(d.kind());
			return (i >= 0) ? i : kindOrder.size();
		}).thenComparing(ResourceDescriptor::label));
		return out;
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

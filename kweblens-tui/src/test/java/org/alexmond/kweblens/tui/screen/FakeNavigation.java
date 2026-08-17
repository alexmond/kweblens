package org.alexmond.kweblens.tui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import org.alexmond.kweblens.resource.DiscoveredKind;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.WellKnownKinds;
import org.alexmond.kweblens.tui.data.ResourceQuery;
import org.alexmond.kweblens.tui.kind.KindIndex;

/**
 * A session that records where it was asked to go, with a small discovered vocabulary.
 *
 * <p>
 * The kinds are shaped like a cluster's: pods, namespaces, events, deployments and one
 * CRD ({@code traefik.io/ingressroutes}) that nothing in this repo lists. A controller
 * test can therefore prove the command line reached a kind <em>through discovery</em>
 * rather than through anything hard-coded.
 */
public class FakeNavigation implements Navigation {

	/** A CRD kind no catalog in this repo names. */
	public static final ResourceDescriptor INGRESS_ROUTES = ResourceDescriptor.namespaced("traefik.io.ingressroutes",
			"IngressRoute", "IngressRoute", "traefik.io", "v1alpha1", "ingressroutes");

	/** Deployments, so a drill-down has a workload to start from. */
	public static final ResourceDescriptor DEPLOYMENTS = ResourceDescriptor.namespaced("apps.deployments", "Deployment",
			"Deployment", "apps", "v1", "deployments");

	private final KindIndex index = KindIndex.of(List.of(new DiscoveredKind(WellKnownKinds.PODS, "pod", List.of("po")),
			new DiscoveredKind(WellKnownKinds.NAMESPACES, "namespace", List.of("ns")),
			new DiscoveredKind(WellKnownKinds.EVENTS, "event", List.of("ev")),
			new DiscoveredKind(WellKnownKinds.NODES, "node", List.of("no")),
			new DiscoveredKind(DEPLOYMENTS, "deployment", List.of("deploy")),
			new DiscoveredKind(INGRESS_ROUTES, "ingressroute", List.of("ir"))));

	private final List<ResourceQuery> shown = new ArrayList<>();

	private final List<Predicate<ResourceRow>> filters = new ArrayList<>();

	private GenericKubernetesResource object;

	/** What {@link #show} answers; empty means the view was filled. */
	private String refusal = "";

	@Override
	public String clusterId() {
		return "fake";
	}

	@Override
	public KindIndex kinds() {
		return this.index;
	}

	@Override
	public GenericKubernetesResource object(String namespace, String name) {
		return this.object;
	}

	@Override
	public String show(ResourceQuery query, Predicate<ResourceRow> filter) {
		this.shown.add(query);
		this.filters.add(filter);
		return this.refusal;
	}

	/**
	 * What every subsequent {@code show} answers — a cluster that will not serve the view
	 * the controller asked for (GH#434). The query is still recorded, because the
	 * navigation was still made: the controller pushed the level and this is the session
	 * saying it could not fill it.
	 */
	public FakeNavigation refusing(String reason) {
		this.refusal = reason;
		return this;
	}

	/** What a drill-down will read a selector off. */
	public FakeNavigation withObject(GenericKubernetesResource single) {
		this.object = single;
		return this;
	}

	/** Every view that was opened, in order. */
	public List<ResourceQuery> shown() {
		return List.copyOf(this.shown);
	}

	/** The query most recently opened. */
	public ResourceQuery last() {
		return this.shown.get(this.shown.size() - 1);
	}

	/** The filter most recently applied. */
	public Predicate<ResourceRow> lastFilter() {
		return this.filters.get(this.filters.size() - 1);
	}

}

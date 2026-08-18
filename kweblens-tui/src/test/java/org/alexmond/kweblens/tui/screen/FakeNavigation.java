package org.alexmond.kweblens.tui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import org.alexmond.kweblens.resource.DiscoveredKind;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.WellKnownKinds;
import org.alexmond.kweblens.tui.data.ObjectDetail;
import org.alexmond.kweblens.tui.data.ResourceQuery;
import org.alexmond.kweblens.tui.kind.KindIndex;
import org.alexmond.kweblens.tui.log.LogModel;
import org.alexmond.kweblens.tui.log.LogOpen;
import org.alexmond.kweblens.tui.log.LogRequest;

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

	private final List<String> detailsRead = new ArrayList<>();

	private GenericKubernetesResource object;

	/** What {@link #detail} answers; null means "no such object any more". */
	private ObjectDetail detail;

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

	@Override
	public ObjectDetail detail(String namespace, String name) {
		this.detailsRead.add(namespace + "/" + name);
		return (this.detail != null) ? this.detail : ObjectDetail.missing("Pod", name);
	}

	/** Every log reading asked for, in order — including the re-opens a key causes. */
	private final List<LogRequest> logsOpened = new ArrayList<>();

	/**
	 * How many times the session was told to release the follow. The pane's own state is
	 * not evidence of a release; this is (GH#369).
	 */
	private int logsClosed;

	/** What {@code logs} answers; a refusal when set. */
	private String logRefusal;

	/** The containers a log pane will be told the pod has. */
	private List<String> containers = List.of("app");

	@Override
	public LogOpen logs(LogRequest request) {
		this.logsOpened.add(request);
		if (this.logRefusal != null) {
			return LogOpen.failed(this.logRefusal);
		}
		// The real session releases the previous follow inside this call; a fake that did
		// not count that would let a controller test pass while the shipped pane leaked.
		this.logsClosed++;
		String container = (!request.container().isBlank()) ? request.container()
				: this.containers.isEmpty() ? "" : this.containers.get(0);
		if (request.previous()) {
			return LogOpen.of(LogModel.previous(request.namespace(), request.pod(), container, this.containers,
					"crashed at boot\n", null));
		}
		return LogOpen.of(LogModel.following(request.namespace(), request.pod(), container, this.containers,
				request.timestamps()));
	}

	@Override
	public void closeLogs() {
		this.logsClosed++;
	}

	/** What the pod's containers are. */
	public FakeNavigation withContainers(String... names) {
		this.containers = List.of(names);
		return this;
	}

	/**
	 * What every subsequent {@code logs} answers — a cluster that will not serve them.
	 */
	public FakeNavigation refusingLogs(String reason) {
		this.logRefusal = reason;
		return this;
	}

	/** Every reading the pane asked for. */
	public List<LogRequest> logsOpened() {
		return List.copyOf(this.logsOpened);
	}

	/** How many releases the session was asked for. */
	public int logsClosed() {
		return this.logsClosed;
	}

	/**
	 * What the pane will be built from. Seeded, never computed: the relations are the
	 * server's answer and a fake that worked them out would be the second implementation
	 * this whole ticket exists to avoid.
	 */
	public FakeNavigation withDetail(ObjectDetail seeded) {
		this.detail = seeded;
		return this;
	}

	/** Every object the pane was opened on, as {@code namespace/name}. */
	public List<String> detailsRead() {
		return List.copyOf(this.detailsRead);
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

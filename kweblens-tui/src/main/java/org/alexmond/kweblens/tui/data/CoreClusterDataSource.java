package org.alexmond.kweblens.tui.data;

import java.io.OutputStream;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.cluster.ClusterInfo;
import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.column.Column;
import org.alexmond.kweblens.column.ColumnCatalog;
import org.alexmond.kweblens.column.PrinterColumns;
import org.alexmond.kweblens.event.EventService;
import org.alexmond.kweblens.exec.ExecService;
import org.alexmond.kweblens.health.ObjectState;
import org.alexmond.kweblens.health.ObjectStates;
import org.alexmond.kweblens.log.LogService;
import org.alexmond.kweblens.log.LogSource;
import org.alexmond.kweblens.log.LogSourceResolver;
import org.alexmond.kweblens.resource.ApiDiscoveryService;
import org.alexmond.kweblens.resource.CrdService;
import org.alexmond.kweblens.resource.DiscoveredKind;
import org.alexmond.kweblens.resource.RelationService;
import org.alexmond.kweblens.resource.ResourceService;

/**
 * The one {@link ClusterDataSource} in the tree: straight onto {@code kweblens-core}'s
 * access services, against the operator's own kubeconfig.
 *
 * <p>
 * Every method here is a delegation, deliberately. This class is not allowed to grow
 * cluster access of its own — that is the standing rule one module over, and it is what
 * keeps the TUI's answers identical to the dashboard's and MCP's. In particular it never
 * builds a {@link io.fabric8.kubernetes.client.KubernetesClient}: the
 * {@link ClusterRegistry} owns client lifecycles, and a client built outside it is owned
 * by nobody.
 */
@Service
@RequiredArgsConstructor
public class CoreClusterDataSource implements ClusterDataSource {

	/**
	 * The TUI has no session-level exec callbacks yet — output arrives on the
	 * {@code OutputStream} and the close is observed by the reader. #370 widens the port
	 * if the pane needs to know about failures out-of-band.
	 */
	private static final ExecListener NO_LISTENER = new ExecListener() {
		@Override
		public void onClose(int code, String reason) {
			// Nothing to do: the reader sees end-of-stream when the session ends.
		}
	};

	/**
	 * Joins the two halves of the memo key with an ASCII unit separator, written as an
	 * escape and never as a raw byte — a control character in a source file makes
	 * {@code grep} treat the whole file as binary and report nothing for every search
	 * over it.
	 */
	private static final String FIELD_SEPARATOR = "\u001f";

	private final ClusterRegistry clusters;

	/**
	 * Printer columns already fetched, keyed by cluster and kind — see {@link #columns}.
	 */
	private final Map<String, List<Column>> declared = new ConcurrentHashMap<>();

	private final ApiDiscoveryService discovery;

	private final ResourceService resources;

	private final ObjectStates states;

	private final LogService logs;

	/**
	 * Which containers a pod has — the SPA's own expansion of "logs for this pod", reused
	 * rather than reimplemented. See {@link ClusterDataSource#containers}.
	 */
	private final LogSourceResolver logSources;

	private final ExecService exec;

	private final CrdService crds;

	/**
	 * The twelve joins, and <b>the only place in this module that names them</b>. Every
	 * relation the detail pane draws arrives through {@link #detail}; nothing downstream
	 * may so much as reference this type, which is what {@code TuiComputesNoRelationTest}
	 * asserts.
	 */
	private final RelationService relations;

	private final EventService events;

	@Override
	public List<String> clusters() {
		return this.clusters.list().stream().map(ClusterInfo::id).toList();
	}

	@Override
	public List<DiscoveredKind> kinds(String clusterId) {
		return this.discovery.kinds(clusterId);
	}

	@Override
	public void list(ResourceQuery query, int chunkSize, Consumer<List<GenericKubernetesResource>> onPage) {
		this.resources.listRawChunked(query.clusterId(), query.descriptor(), query.namespace(), chunkSize, onPage);
	}

	@Override
	public List<Optional<ObjectState>> states(ResourceQuery query, List<GenericKubernetesResource> objects) {
		return this.states.forList(query.clusterId(), query.kind(), query.namespace(), objects);
	}

	/**
	 * A covered built-in answers from the static catalog; anything else is asked of its
	 * CRD once and remembered.
	 *
	 * <p>
	 * <b>The fallback runs on the render thread, so what it costs is this module's
	 * problem even though it lives one module over</b> (#459). It used to list every CRD
	 * in the cluster and filter in memory — 10 393 833 bytes on the lab cluster —
	 * synchronously inside the keystroke, for every kind {@code ColumnCatalog} does not
	 * cover, which is every built-in outside the #367 tranche. {@code CrdService} now
	 * answers a core-group kind ({@code secrets}, {@code configmaps}) with no request at
	 * all and everything else with one CRD fetched by name, so what is left behind a
	 * navigation is a single keyed GET.
	 *
	 * <p>
	 * <b>Still memoised per (cluster, kind), and that is the same trade
	 * {@code KindCatalog} already takes.</b> One bounded GET is affordable; one per
	 * {@code :} command, on the thread that draws, is not. The cost of remembering is
	 * that printer columns edited while the screen is up are not picked up until restart,
	 * exactly as a CRD installed while the screen is up is not addressable until restart.
	 */
	@Override
	public List<Column> columns(ResourceQuery query) {
		List<Column> built = ColumnCatalog.forDescriptor(query.descriptor());
		if (!built.isEmpty()) {
			return built;
		}
		return this.declared.computeIfAbsent(query.clusterId() + FIELD_SEPARATOR + query.descriptor().id(),
				(key) -> PrinterColumns.of(this.crds.printerColumns(query.clusterId(), query.descriptor().id()),
						Clock.systemUTC()));
	}

	@Override
	public GenericKubernetesResource get(ResourceQuery query, String name) {
		return this.resources.getRaw(query.clusterId(), query.descriptor(), query.namespace(), name);
	}

	/**
	 * One GET, then three projections of it — and every one of them belongs to somebody
	 * else.
	 *
	 * <p>
	 * The object is read once rather than through {@code ResourceService.getYaml} plus a
	 * second read for the relations, because two GETs straddle a change: the YAML would
	 * show a selector the relations beside it were not computed from, and nothing on
	 * screen would say so. {@code Serialization.asYaml} is what {@code getYaml} would
	 * have called on the object this already holds.
	 *
	 * <p>
	 * The events are scoped to the <em>object's</em> namespace, taken from the object
	 * rather than from the query: a cluster-scoped object has none, and its events are
	 * wherever the component that emitted them put them, so scoping those to the screen's
	 * namespace would quietly hide them.
	 */
	@Override
	public ObjectDetail detail(ResourceQuery query, String name) {
		GenericKubernetesResource object = this.resources.getRaw(query.clusterId(), query.descriptor(),
				query.namespace(), name);
		if (object == null) {
			return ObjectDetail.missing(query.kind(), name);
		}
		String namespace = (object.getMetadata() != null) ? object.getMetadata().getNamespace() : null;
		return ObjectDetail.of(Serialization.asYaml(object),
				this.relations.relationsFor(query.clusterId(), query.descriptor(), object),
				this.events.listForObject(query.clusterId(), namespace, query.kind(), name));
	}

	/**
	 * The end of the stream is reported through {@link CoreWatch}, which is also the
	 * handle — one object, so the question "did we close this ourselves?" has exactly one
	 * answer and it is the one the caller's close set.
	 */
	@Override
	public Subscription watch(ResourceQuery query, BiConsumer<String, GenericKubernetesResource> onEvent,
			Consumer<WatchEnd> onEnd) {
		CoreWatch handle = new CoreWatch(onEnd);
		handle
			.attach(this.resources.watchRaw(query.clusterId(), query.descriptor(), query.namespace(), onEvent, handle));
		return handle;
	}

	@Override
	public List<String> containers(String clusterId, String namespace, String pod) {
		return this.logSources.forPod(clusterId, namespace, pod, false).stream().map(LogSource::container).toList();
	}

	@Override
	public LogStream logs(PodTarget target) {
		LogWatch watch = this.logs.watch(target.clusterId(), target.namespace(), target.pod(), target.container());
		return new CoreLogStream(this.logs, watch);
	}

	@Override
	public LogStream logsWithTimestamps(PodTarget target) {
		LogWatch watch = this.logs.watchWithTimestamps(target.clusterId(), target.namespace(), target.pod(),
				target.container());
		return new CoreLogStream(this.logs, watch);
	}

	/**
	 * Three answers, because the API server gives three.
	 *
	 * <p>
	 * A container that has never restarted is a 400, which {@code LogService.previous}
	 * turns into null; a terminated instance that wrote nothing is an empty 200. Those
	 * are different facts about the cluster and they are precisely the two a crashloop
	 * reader is choosing between — "it has not crashed" against "it crashed without
	 * saying why" — so both become sentences here rather than a blank pane that
	 * manufactures the second.
	 */
	@Override
	public PreviousLog previousLog(PodTarget target, int tailLines) {
		String body = this.logs.previous(target.clusterId(), target.namespace(), target.pod(), target.container(),
				tailLines);
		if (body == null) {
			return PreviousLog.none(target.container());
		}
		return (!body.isBlank()) ? PreviousLog.of(body) : PreviousLog.silent(target.container());
	}

	@Override
	public ExecSession exec(PodTarget target, OutputStream output) {
		ExecWatch watch = this.exec.exec(target.clusterId(), target.namespace(), target.pod(), target.container(),
				output, NO_LISTENER);
		return new CoreExecSession(watch);
	}

}

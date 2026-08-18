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
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.cluster.ClusterInfo;
import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.column.Column;
import org.alexmond.kweblens.column.ColumnCatalog;
import org.alexmond.kweblens.column.PrinterColumns;
import org.alexmond.kweblens.exec.ExecService;
import org.alexmond.kweblens.health.ObjectState;
import org.alexmond.kweblens.health.ObjectStates;
import org.alexmond.kweblens.log.LogService;
import org.alexmond.kweblens.resource.ApiDiscoveryService;
import org.alexmond.kweblens.resource.CrdService;
import org.alexmond.kweblens.resource.DiscoveredKind;
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

	private final ExecService exec;

	private final CrdService crds;

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
	 * <b>Memoised per (cluster, kind), and that is the same trade {@code KindCatalog}
	 * already takes.</b> {@code CrdService.printerColumns} lists every CRD in the cluster
	 * to find one, so asking it on each {@code :} command would put a cluster-wide list
	 * behind every navigation — and a kind with no CRD at all would pay it to learn
	 * nothing. The cost of remembering is that printer columns edited while the screen is
	 * up are not picked up until restart, exactly as a CRD installed while the screen is
	 * up is not addressable until restart.
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
	public LogStream logs(PodTarget target) {
		LogWatch watch = this.logs.watch(target.clusterId(), target.namespace(), target.pod(), target.container());
		return new CoreLogStream(this.logs, watch);
	}

	@Override
	public ExecSession exec(PodTarget target, OutputStream output) {
		ExecWatch watch = this.exec.exec(target.clusterId(), target.namespace(), target.pod(), target.container(),
				output, NO_LISTENER);
		return new CoreExecSession(watch);
	}

}

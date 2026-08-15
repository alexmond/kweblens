package org.alexmond.kweblens.tui.data;

import java.io.OutputStream;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.cluster.ClusterInfo;
import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.exec.ExecService;
import org.alexmond.kweblens.health.ObjectState;
import org.alexmond.kweblens.health.ObjectStates;
import org.alexmond.kweblens.log.LogService;
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

	private final ClusterRegistry clusters;

	private final ResourceService resources;

	private final ObjectStates states;

	private final LogService logs;

	private final ExecService exec;

	@Override
	public List<String> clusters() {
		return this.clusters.list().stream().map(ClusterInfo::id).toList();
	}

	@Override
	public void list(ResourceQuery query, int chunkSize, Consumer<List<GenericKubernetesResource>> onPage) {
		this.resources.listRawChunked(query.clusterId(), query.descriptor(), query.namespace(), chunkSize, onPage);
	}

	@Override
	public List<Optional<ObjectState>> states(ResourceQuery query, List<GenericKubernetesResource> objects) {
		return this.states.forList(query.clusterId(), query.kind(), query.namespace(), objects);
	}

	@Override
	public GenericKubernetesResource get(ResourceQuery query, String name) {
		return this.resources.getRaw(query.clusterId(), query.descriptor(), query.namespace(), name);
	}

	@Override
	public Subscription watch(ResourceQuery query, BiConsumer<String, GenericKubernetesResource> onEvent) {
		Watch watch = this.resources.watchRaw(query.clusterId(), query.descriptor(), query.namespace(), onEvent);
		return watch::close;
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

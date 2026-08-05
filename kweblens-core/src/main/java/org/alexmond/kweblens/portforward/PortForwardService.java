package org.alexmond.kweblens.portforward;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.dsl.PortForwardable;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.config.KweblensProperties;

/**
 * Manages the lifecycle of pod/service port-forwards. Each forward binds a port on the
 * kweblens host (loopback by default, see {@link KweblensProperties.PortForward}) and
 * tunnels it to a port on the target through the API server, exactly like
 * {@code kubectl port-forward}. Forwards are held server-side and addressed by id;
 * everything is closed on shutdown.
 *
 * <p>
 * A Service target is translated to a pod and a pod port first, by
 * {@link PortForwardTargetResolver} — a Service's own port is not what the container
 * listens on. fabric8's {@code services().portForward(…)} picks a matching pod but passes
 * the port straight through, which reaches nothing whenever a Service remaps a port, so
 * every forward is started against the resolved <em>pod</em>.
 */
@Slf4j
@Service
public class PortForwardService implements AutoCloseable {

	private final ClusterRegistry clusters;

	private final KweblensProperties properties;

	private final ConcurrentMap<String, Active> forwards = new ConcurrentHashMap<>();

	private final AtomicLong sequence = new AtomicLong();

	public PortForwardService(ClusterRegistry clusters, KweblensProperties properties) {
		this.clusters = clusters;
		this.properties = properties;
	}

	/**
	 * Start a forward from {@code remotePort} on the target to {@code localPort} on the
	 * kweblens host. A {@code localPort} of {@code null} or {@code 0} binds an ephemeral
	 * port.
	 */
	public PortForwardInfo start(String clusterId, String kind, String namespace, String name, int remotePort,
			Integer localPort) {
		KubernetesClient client = clusters.require(clusterId);
		PortForwardTarget target = target(clusterId, kind, namespace, name, remotePort);
		PortForwardable pod = client.pods().inNamespace(target.namespace()).withName(target.podName());
		int requested = (localPort != null) ? localPort : 0;
		LocalPortForward forward = pod.portForward(target.podPort(), bindAddress(), requested);
		if (forward.errorOccurred()) {
			closeQuietly(forward);
			throw new PortForwardException("Failed to start port-forward to " + normalise(kind) + "/" + name + " ("
					+ target.podName() + ":" + target.podPort() + ")");
		}
		String id = clusterId + "-" + sequence.incrementAndGet();
		PortForwardInfo info = new PortForwardInfo(id, clusterId, namespace, normalise(kind), name, remotePort,
				target.podPort(), target.podName(), forward.getLocalPort(), forward.getLocalAddress().getHostAddress(),
				"TCP", "Active");
		forwards.put(id, new Active(forward, info));
		log.info("Started port-forward {} {}/{} {}->{} via pod {}:{}", id, namespace, name, remotePort,
				forward.getLocalPort(), target.podName(), target.podPort());
		return info;
	}

	/**
	 * Where a forward to {@code kind}/{@code name} would land: the pod, and the port
	 * inside it. For a Service this performs the {@code port} to {@code targetPort}
	 * translation that {@code kubectl port-forward service/…} does, resolving a
	 * <em>named</em> {@code targetPort} against the selected pod's container ports. A Pod
	 * target is returned unchanged — there is nothing to translate.
	 */
	public PortForwardTarget target(String clusterId, String kind, String namespace, String name, int remotePort) {
		KubernetesClient client = clusters.require(clusterId);
		String resolved = normalise(kind).toLowerCase(Locale.ROOT);
		if (!"pod".equals(resolved) && !"service".equals(resolved)) {
			throw new PortForwardException("Port-forward supports Pod and Service, not '" + kind + "'");
		}
		return new PortForwardTargetResolver(client).resolve(resolved, namespace, name, remotePort);
	}

	/**
	 * Active forwards for a cluster, with each status refreshed from the live channel.
	 */
	public List<PortForwardInfo> list(String clusterId) {
		return forwards.values()
			.stream()
			.filter((active) -> active.info.clusterId().equals(clusterId))
			.map(this::currentStatus)
			.sorted(Comparator.comparing(PortForwardInfo::id))
			.toList();
	}

	/** Stop and forget a forward. A no-op if the id is unknown. */
	public void stop(String id) {
		Active active = forwards.remove(id);
		if (active != null) {
			closeQuietly(active.forward);
			log.info("Stopped port-forward {}", id);
		}
	}

	private PortForwardInfo currentStatus(Active active) {
		String status;
		if (active.forward.errorOccurred()) {
			status = "Failed";
		}
		else {
			status = active.forward.isAlive() ? "Active" : "Closed";
		}
		return active.info.withStatus(status);
	}

	private String normalise(String kind) {
		return (kind == null || kind.isBlank()) ? "Pod" : kind;
	}

	private InetAddress bindAddress() {
		String addr = properties.getPortForward().getBindAddress();
		if (addr == null || addr.isBlank()) {
			return InetAddress.getLoopbackAddress();
		}
		try {
			return InetAddress.getByName(addr);
		}
		catch (UnknownHostException ex) {
			log.warn("Invalid port-forward bind address '{}', falling back to loopback: {}", addr, ex.getMessage());
			return InetAddress.getLoopbackAddress();
		}
	}

	private void closeQuietly(Closeable closeable) {
		try {
			closeable.close();
		}
		catch (IOException ex) {
			log.debug("Failed to close port-forward: {}", ex.getMessage());
		}
	}

	@Override
	public void close() {
		forwards.values().forEach((active) -> closeQuietly(active.forward));
		forwards.clear();
	}

	private record Active(LocalPortForward forward, PortForwardInfo info) {
	}

}

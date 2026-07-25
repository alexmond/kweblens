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
 * Both {@code Pod} and {@code Service} resources are {@link PortForwardable}, so the
 * fabric8 client resolves the target (including a service's endpoints) for us.
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
		PortForwardable target = resolve(client, kind, namespace, name);
		int requested = (localPort != null) ? localPort : 0;
		LocalPortForward forward = target.portForward(remotePort, bindAddress(), requested);
		if (forward.errorOccurred()) {
			closeQuietly(forward);
			throw new PortForwardException("Failed to start port-forward to " + kind + "/" + name);
		}
		String id = clusterId + "-" + sequence.incrementAndGet();
		PortForwardInfo info = new PortForwardInfo(id, clusterId, namespace, normalise(kind), name, remotePort,
				forward.getLocalPort(), forward.getLocalAddress().getHostAddress(), "TCP", "Active");
		forwards.put(id, new Active(forward, info));
		log.info("Started port-forward {} {}/{} {}->{}", id, namespace, name, remotePort, forward.getLocalPort());
		return info;
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

	private PortForwardable resolve(KubernetesClient client, String kind, String namespace, String name) {
		return switch (normalise(kind).toLowerCase(Locale.ROOT)) {
			case "service" -> client.services().inNamespace(namespace).withName(name);
			case "pod" -> client.pods().inNamespace(namespace).withName(name);
			default -> throw new PortForwardException("Port-forward supports Pod and Service, not '" + kind + "'");
		};
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

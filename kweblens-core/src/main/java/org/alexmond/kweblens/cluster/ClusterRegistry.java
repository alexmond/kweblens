package org.alexmond.kweblens.cluster;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

/**
 * Holds the live {@link KubernetesClient} for every cluster kweblens knows about, keyed
 * by a stable id. This is the single place that owns client lifecycles; everything else
 * asks the registry for a client rather than constructing one.
 */
@Slf4j
@Service
public class ClusterRegistry implements AutoCloseable {

	private final Map<String, Entry> entries = new ConcurrentHashMap<>();

	private final List<ClusterClientListener> listeners = new CopyOnWriteArrayList<>();

	/**
	 * Be told when a cluster's client is closed, so that whatever else that cluster was
	 * holding can be released too — see {@link ClusterClientListener} for why the client
	 * is not the whole story.
	 * @param listener notified after the client has been closed
	 */
	public void addClientListener(ClusterClientListener listener) {
		this.listeners.add(listener);
	}

	/**
	 * Register (or replace) a cluster declared outside kweblens (configuration or the
	 * ambient kubeconfig).
	 * @return the {@link ClusterInfo} view of the registered cluster
	 */
	public ClusterInfo register(String id, String name, KubernetesClient client) {
		return register(id, name, client, ClusterOrigin.STATIC);
	}

	/**
	 * Register (or replace) a cluster. Replacing an existing id closes the old client,
	 * unless the same client instance is being re-registered.
	 * @return the {@link ClusterInfo} view of the registered cluster
	 */
	// Reference identity is intentional: only close a *different* client instance, never
	// the
	// same one being re-registered.
	@SuppressWarnings("PMD.CompareObjectsWithEquals")
	public ClusterInfo register(String id, String name, KubernetesClient client, ClusterOrigin origin) {
		Entry previous = entries.put(id, new Entry(name, client, origin));
		if (previous != null && previous.client() != client) {
			closeQuietly(previous.client());
			// Re-pointing an id is a close as much as a removal is: everything derived
			// from the old client — a port-forward's socket, a cached schema — describes
			// a
			// cluster this id no longer reaches.
			clientClosed(id);
		}
		log.info("Registered cluster '{}' ({}) at {}", id, name, client.getMasterUrl());
		return info(id).orElseThrow();
	}

	/**
	 * Drop a cluster and close its client. Closing is the point: a removed cluster must
	 * stop holding sockets and threads, not merely disappear from the list.
	 * @return true if a cluster was removed
	 */
	public boolean unregister(String id) {
		Entry removed = entries.remove(id);
		if (removed == null) {
			return false;
		}
		closeQuietly(removed.client());
		clientClosed(id);
		log.info("Unregistered cluster '{}'", id);
		return true;
	}

	/**
	 * @return the client for {@code id}, or empty if no such cluster is registered
	 */
	public Optional<KubernetesClient> client(String id) {
		return Optional.ofNullable(entries.get(id)).map(Entry::client);
	}

	/**
	 * @return the client for {@code id}
	 * @throws UnknownClusterException if no cluster with that id is registered
	 */
	public KubernetesClient require(String id) {
		return client(id).orElseThrow(() -> new UnknownClusterException(id));
	}

	/**
	 * @return the {@link ClusterInfo} view of {@code id}, or empty if unknown
	 */
	public Optional<ClusterInfo> info(String id) {
		return Optional.ofNullable(entries.get(id)).map((e) -> toInfo(id, e));
	}

	/**
	 * @return every registered cluster, in registration-id order
	 */
	public List<ClusterInfo> list() {
		return entries.entrySet()
			.stream()
			.map((e) -> toInfo(e.getKey(), e.getValue()))
			.sorted((a, b) -> a.id().compareTo(b.id()))
			.toList();
	}

	private ClusterInfo toInfo(String id, Entry entry) {
		return new ClusterInfo(id, entry.name(), String.valueOf(entry.client().getMasterUrl()), entry.origin());
	}

	/**
	 * Shutdown. Listeners are deliberately <b>not</b> notified here: every holder of a
	 * cluster-scoped resource closes its own on shutdown, and firing a
	 * per-cluster-removal callback during a context close would have them racing the
	 * container over the same objects. The listeners exist for the case the container
	 * knows nothing about — one cluster going away while the app keeps running.
	 */
	@Override
	public void close() {
		entries.values().forEach((e) -> closeQuietly(e.client()));
		entries.clear();
	}

	/**
	 * A listener that throws must not stop the removal: the client is already closed, and
	 * an id that half-disappears is worse than one whose leftovers are only logged.
	 */
	private void clientClosed(String id) {
		for (ClusterClientListener listener : this.listeners) {
			try {
				listener.clusterClientClosed(id);
			}
			catch (RuntimeException ex) {
				log.warn("A listener failed to release its resources for cluster '{}'", id, ex);
			}
		}
	}

	private void closeQuietly(KubernetesClient client) {
		try {
			client.close();
		}
		catch (RuntimeException ex) {
			log.warn("Failed to close cluster client cleanly", ex);
		}
	}

	private record Entry(String name, KubernetesClient client, ClusterOrigin origin) {
	}

}

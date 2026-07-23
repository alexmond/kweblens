package org.alexmond.kweblens.cluster;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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

	/**
	 * Register (or replace) a cluster. Replacing an existing id closes the old client,
	 * unless the same client instance is being re-registered.
	 * @return the {@link ClusterInfo} view of the registered cluster
	 */
	// Reference identity is intentional: only close a *different* client instance, never
	// the
	// same one being re-registered.
	@SuppressWarnings("PMD.CompareObjectsWithEquals")
	public ClusterInfo register(String id, String name, KubernetesClient client) {
		Entry previous = entries.put(id, new Entry(name, client));
		if (previous != null && previous.client() != client) {
			closeQuietly(previous.client());
		}
		log.info("Registered cluster '{}' ({}) at {}", id, name, client.getMasterUrl());
		return info(id).orElseThrow();
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
		return Optional.ofNullable(entries.get(id))
			.map((e) -> new ClusterInfo(id, e.name(), String.valueOf(e.client().getMasterUrl())));
	}

	/**
	 * @return every registered cluster, in registration-id order
	 */
	public List<ClusterInfo> list() {
		return entries.entrySet()
			.stream()
			.map((e) -> new ClusterInfo(e.getKey(), e.getValue().name(),
					String.valueOf(e.getValue().client().getMasterUrl())))
			.sorted((a, b) -> a.id().compareTo(b.id()))
			.toList();
	}

	@Override
	public void close() {
		entries.values().forEach((e) -> closeQuietly(e.client()));
		entries.clear();
	}

	private void closeQuietly(KubernetesClient client) {
		try {
			client.close();
		}
		catch (RuntimeException ex) {
			log.warn("Failed to close cluster client cleanly", ex);
		}
	}

	private record Entry(String name, KubernetesClient client) {
	}

}

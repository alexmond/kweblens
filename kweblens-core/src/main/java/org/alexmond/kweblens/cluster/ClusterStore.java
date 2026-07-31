package org.alexmond.kweblens.cluster;

import java.util.List;
import java.util.Optional;

/**
 * Where runtime-added clusters — and the kubeconfigs that authenticate them — are kept
 * between restarts.
 *
 * <p>
 * There are two real deployment shapes and they want different answers, which is why this
 * is an interface rather than a directory path:
 *
 * <ul>
 * <li><b>In-cluster</b> — a kubeconfig is a credential, so it belongs in a Kubernetes
 * Secret, where it inherits the cluster's encryption-at-rest and RBAC rather than sitting
 * in a container filesystem. See {@code SecretClusterStore}.</li>
 * <li><b>Anywhere else</b> (a laptop, a VM, docker-compose) — there is no API server to
 * hold a Secret, so a data directory on a mounted volume is the only durable option. See
 * {@code FileClusterStore}.</li>
 * </ul>
 *
 * <p>
 * Implementations must treat {@link ClusterDefinition#kubeconfig()} as a secret: no
 * logging, no world-readable files, no echoing it back through {@link #describe()}.
 */
public interface ClusterStore {

	/** Every persisted definition. Never throws for "nothing stored yet". */
	List<ClusterDefinition> load();

	/** Create or replace a definition. */
	void save(ClusterDefinition definition);

	/** Remove a definition (no-op when absent). */
	void delete(String id);

	/**
	 * Where definitions are being kept, for the diagnostics panel — a path or a
	 * {@code namespace/secret} reference, <b>never</b> a credential.
	 */
	String describe();

	/** One definition by id, if stored. */
	default Optional<ClusterDefinition> find(String id) {
		return load().stream().filter((d) -> d.id().equals(id)).findFirst();
	}

	/**
	 * False when runtime clusters live only in memory and will not survive a restart —
	 * reported so the UI can say so rather than implying durability it does not have.
	 */
	default boolean persistent() {
		return true;
	}

}

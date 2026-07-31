package org.alexmond.kweblens.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where clusters added at runtime — and the kubeconfigs that authenticate them — are
 * kept. Configured under {@code kweblens.cluster-store.*}.
 *
 * <p>
 * The default is {@code auto}, which picks Kubernetes Secrets when kweblens is running
 * inside a cluster and a data directory otherwise. That split is deliberate: a kubeconfig
 * is a credential, so in-cluster it belongs somewhere with encryption-at-rest and RBAC,
 * while a laptop or docker-compose deployment has no API server to hold one and needs a
 * file.
 */
@Data
@ConfigurationProperties(prefix = "kweblens.cluster-store")
public class ClusterStoreProperties {

	/**
	 * Where runtime clusters are persisted: {@code auto} (Secrets in-cluster, files
	 * otherwise), {@code file}, {@code secret}, or {@code memory} (not persisted at all).
	 */
	private Mode mode = Mode.AUTO;

	/**
	 * Data directory for {@code file} mode. Defaults to
	 * {@code <user.home>/.kweblens/clusters}; in a container point this at a mounted
	 * volume.
	 */
	private String path;

	/**
	 * Namespace for {@code secret} mode. Defaults to the namespace kweblens is running
	 * in.
	 */
	private String namespace;

	/** Name prefix for the per-cluster Secrets in {@code secret} mode. */
	private String secretPrefix = "kweblens-cluster-";

	/** Persistence backends for runtime-added clusters. */
	public enum Mode {

		/** Secrets when running in-cluster, otherwise the data directory. */
		AUTO,

		/** A data directory on the kweblens host. */
		FILE,

		/** One Kubernetes Secret per cluster, in kweblens's own namespace. */
		SECRET,

		/** Process memory only — runtime clusters do not survive a restart. */
		MEMORY

	}

}

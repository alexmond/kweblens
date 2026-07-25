package org.alexmond.kweblens.config;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for how kweblens discovers and connects to Kubernetes clusters.
 *
 * <p>
 * By default the current kubeconfig (honouring {@code KUBECONFIG} /
 * {@code ~/.kube/config}) is loaded and every context in it becomes a selectable cluster.
 * Explicit clusters can be added under {@code kweblens.clusters[*]}.
 */
@Data
@ConfigurationProperties(prefix = "kweblens")
public class KweblensProperties {

	/**
	 * Load clusters from the ambient kubeconfig (KUBECONFIG env var, or ~/.kube/config).
	 */
	private boolean loadKubeconfig = true;

	/**
	 * Explicit, statically-configured clusters (in addition to any from the kubeconfig).
	 */
	private List<Cluster> clusters = new ArrayList<>();

	/** Port-forwarding behaviour. */
	private PortForward portForward = new PortForward();

	@Data
	public static class Cluster {

		/** Stable identifier used in URLs and the API (e.g. {@code prod-eu}). */
		private String id;

		/** Human-readable display name; defaults to the id when unset. */
		private String name;

		/** Path to a kubeconfig file to load this cluster from. */
		private String kubeconfig;

		/**
		 * kubeconfig context to select within that file; defaults to its current-context.
		 */
		private String context;

	}

	@Data
	public static class PortForward {

		/**
		 * Address the forwarded local ports bind to on the kweblens host. Blank (the
		 * default) binds loopback only, so forwards are not exposed on the network; set
		 * to {@code 0.0.0.0} only behind auth on a trusted host.
		 */
		private String bindAddress = "";

	}

}

package org.alexmond.kweblens.web.diag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.VersionInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import io.fabric8.kubernetes.client.Config;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.cluster.ClusterStore;
import org.alexmond.kweblens.cluster.ProxyStatus;
import org.alexmond.kweblens.config.KweblensProperties;
import org.alexmond.kweblens.metric.PrometheusMetricService;
import org.alexmond.kweblens.web.ai.KweblensAiProperties;
import org.alexmond.kweblens.web.files.FilesProperties;
import org.alexmond.kweblens.web.security.SecurityProperties;
import org.alexmond.kweblens.web.sim.SimulatorProperties;

/**
 * Read-only diagnostics: what kweblens detected in a cluster, and how this instance is
 * configured.
 *
 * <p>
 * Exists because graceful degradation is silent. When no Prometheus-compatible backend is
 * discovered the metric charts simply render "unavailable", which is indistinguishable
 * from "the backend is there but the query failed" — during the node-metrics work that
 * difference could only be established by reading server logs. This service answers "why
 * is this empty?" directly.
 *
 * <p>
 * <b>Deliberately read-only.</b> Deployment configuration (port, auth mode, admin
 * password, AI keys, kubeconfig loading) is NOT editable from the browser: it is a
 * security surface, and editing it at runtime would create drift between the running
 * state and the deployed manifest. Values are reported and labelled with where they are
 * set; secrets are never reported at all.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosticsService {

	/** How far to unwrap a cause chain when explaining a probe failure. */
	private static final int MAX_CAUSE_DEPTH = 10;

	private final ClusterRegistry clusters;

	private final ClusterStore clusterStore;

	private final PrometheusMetricService prometheus;

	private final KweblensProperties properties;

	private final SecurityProperties security;

	private final SimulatorProperties simulator;

	private final KweblensAiProperties ai;

	private final FilesProperties files;

	/** Optional: only present when build-info.properties was generated. */
	private final ObjectProvider<BuildProperties> buildProperties;

	/**
	 * How many clusters the store holds. Guarded because for the Secret backend this is
	 * an API call, and a diagnostics panel that 500s when its own storage is unreachable
	 * is useless precisely when it is needed.
	 */
	private Object runtimeClusterCount() {
		try {
			return this.clusterStore.load().size();
		}
		catch (RuntimeException ex) {
			log.debug("Could not count runtime clusters: {}", ex.getMessage());
			return "unavailable";
		}
	}

	/**
	 * What this cluster can support. Every probe is individually guarded: a cluster that
	 * is unreachable, or a service account that cannot list services, must still produce
	 * a useful answer for the other rows rather than failing the whole panel.
	 */
	public Map<String, Object> clusterDiagnostics(String clusterId) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("clusterId", clusterId);
		out.put("kubernetesVersion", kubernetesVersion(clusterId));
		out.put("capabilities", capabilities(clusterId));
		return out;
	}

	private String kubernetesVersion(String clusterId) {
		try {
			VersionInfo info = this.clusters.require(clusterId).getKubernetesVersion();
			return (info != null) ? info.getGitVersion() : "unknown";
		}
		catch (RuntimeException ex) {
			log.debug("Version lookup failed for cluster '{}': {}", clusterId, ex.getMessage());
			return "unreachable";
		}
	}

	private List<Capability> capabilities(String clusterId) {
		List<Capability> caps = new ArrayList<>();
		caps.add(metricsServer(clusterId));
		caps.add(prometheusBackend(clusterId));
		caps.add(egressProxy(clusterId));
		caps.add(apiGroup(clusterId, "Gateway API", "gateway.networking.k8s.io",
				"Gateway/HTTPRoute resources can be listed", "install the Gateway API CRDs to use a Gateway view"));
		caps.add(apiGroup(clusterId, "Custom resources", "apiextensions.k8s.io",
				"CRDs are discoverable, so the Custom Resources nav section is populated",
				"CRD discovery unavailable — the Custom Resources section will be empty"));
		return caps;
	}

	/**
	 * metrics-server presence is probed by API GROUP, not by calling the metrics
	 * endpoint: the usage calls return an empty list both when metrics-server is absent
	 * and when it is present but has no samples yet, so an empty result cannot
	 * distinguish the two.
	 */
	private Capability metricsServer(String clusterId) {
		String name = "metrics-server";
		try {
			boolean present = this.clusters.require(clusterId).hasApiGroup("metrics.k8s.io", false);
			return present
					? Capability.yes(name, "metrics.k8s.io present — live CPU/memory columns and usage bars work")
					: Capability.no(name,
							"metrics.k8s.io not served — install metrics-server for live CPU/memory usage");
		}
		catch (RuntimeException ex) {
			return Capability.no(name, probeFailure(ex));
		}
	}

	/**
	 * The Prometheus-compatible backend, reported as the concrete
	 * {@code namespace/service:port} that was discovered. Naming it is what makes a wrong
	 * auto-discovery obvious — the failure mode is picking the wrong service, not picking
	 * none.
	 */
	private Capability prometheusBackend(String clusterId) {
		String name = "Prometheus-compatible metrics";
		try {
			PrometheusMetricService.Resolution resolution = this.prometheus.resolve(clusterId);
			return switch (resolution.origin()) {
				case CONFIGURED -> Capability.yes(name,
						"configured: " + resolution.value() + " (queried via the kube-apiserver service proxy)");
				case DISCOVERED -> Capability.yes(name,
						"discovered " + resolution.value() + " (queried via the kube-apiserver service proxy)");
				// The ambiguous case is the whole reason the count is reported. Discovery
				// picks by API ordering, so with several valid backends of different
				// scope —
				// a global Thanos beside a local Prometheus — the charts can be correct
				// looking and scoped to the wrong thing. Naming the alternatives is what
				// makes that visible without the user reading logs.
				case AMBIGUOUS -> Capability.yes(name,
						"picked " + resolution.value() + " from " + resolution.candidates().size()
								+ " matching services (" + String.join(", ", resolution.candidates())
								+ ") — set kweblens.metrics.prometheus-service to choose");
				case UNSUPPORTED_URL -> Capability.no(name,
						"kweblens.metrics.prometheus-service is a URL; only an in-cluster "
								+ "namespace/service:port is supported, because queries go through the "
								+ "kube-apiserver service proxy");
				default -> Capability.no(name,
						"no Prometheus/VictoriaMetrics/Thanos service found by name — node CPU/memory/disk charts "
								+ "will show 'unavailable'; set kweblens.metrics.prometheus-service to name one");
			};
		}
		catch (RuntimeException ex) {
			return Capability.no(name, "discovery failed: " + ex.getMessage());
		}
	}

	/**
	 * The egress proxy in force for this cluster, and whether a configured one is being
	 * ignored.
	 *
	 * <p>
	 * Reported because the failure it catches is invisible: an {@code http://} proxy-url
	 * against an https apiserver is never consulted, and everything keeps working, so
	 * nothing prompts anyone to check whether traffic is really taking the audited path.
	 * See {@link ProxyStatus}.
	 */
	private Capability egressProxy(String clusterId) {
		String name = "Egress proxy";
		try {
			Config config = this.clusters.require(clusterId).getConfiguration();
			ProxyStatus status = ProxyStatus.of(config.getMasterUrl(), config.getHttpProxy(), config.getHttpsProxy(),
					config.getNoProxy());
			// A bypass is reported as NOT available: it is the one case where the
			// configuration says one thing and the connection does another.
			return status.bypassed() ? Capability.no(name, status.detail()) : Capability.yes(name, status.detail());
		}
		catch (RuntimeException ex) {
			return Capability.no(name, "could not read the cluster's connection settings: " + ex.getMessage());
		}
	}

	private Capability apiGroup(String clusterId, String name, String group, String whenPresent, String whenAbsent) {
		try {
			KubernetesClient client = this.clusters.require(clusterId);
			return client.hasApiGroup(group, false) ? Capability.yes(name, whenPresent)
					: Capability.no(name, whenAbsent);
		}
		catch (RuntimeException ex) {
			return Capability.no(name, probeFailure(ex));
		}
	}

	/**
	 * A probe failure described usefully.
	 *
	 * <p>
	 * fabric8 often surfaces a connection failure as the literal message "An error has
	 * occurred.", which tells the reader nothing — and a capability whose {@code detail}
	 * says nothing defeats the point of this panel. So walk to the root cause and use the
	 * most specific text available, falling back to the exception type when every message
	 * is useless.
	 */
	private String probeFailure(Throwable ex) {
		Throwable root = ex;
		// Depth-bounded rather than checking `cause != self`: a self-referential cause
		// would
		// otherwise loop forever, and the bound handles that without a reference
		// comparison.
		for (int depth = 0; root.getCause() != null && depth < MAX_CAUSE_DEPTH; depth++) {
			root = root.getCause();
		}
		String message = informative(root.getMessage()) ? root.getMessage()
				: (informative(ex.getMessage()) ? ex.getMessage() : null);
		String cause = (message != null) ? message : root.getClass().getSimpleName();
		return "could not probe — the cluster may be unreachable (" + cause + ")";
	}

	/** Generic placeholders carry no information, so they are treated as absent. */
	private boolean informative(String message) {
		return message != null && !message.isBlank() && !message.startsWith("An error has occurred");
	}

	/**
	 * How this instance is configured and built. Never includes a secret: no admin
	 * password, no AI key, no kubeconfig contents — only whether each is set.
	 */
	public Map<String, Object> appDiagnostics() {
		Map<String, Object> out = new LinkedHashMap<>();
		BuildProperties build = this.buildProperties.getIfAvailable();
		out.put("version", (build != null) ? build.getVersion() : "unknown");
		out.put("buildTime", (build != null && build.getTime() != null) ? build.getTime().toString() : null);
		out.put("clusterCount", this.clusters.list().size());
		out.put("loadKubeconfig", this.properties.isLoadKubeconfig());
		out.put("configuredClusters", this.properties.getClusters().size());
		// Where runtime-added clusters (and their kubeconfigs) are kept. Reported the
		// same
		// way credentials always are here: what holds them and whether it survives a
		// restart, never what is in it.
		Map<String, Object> store = new LinkedHashMap<>();
		store.put("backend", this.clusterStore.describe());
		store.put("persistent", this.clusterStore.persistent());
		store.put("runtimeClusters", runtimeClusterCount());
		out.put("clusterStore", store);
		// Security posture, stated plainly. This is the project's known weak spot and the
		// panel should not soften it.
		Map<String, Object> sec = new LinkedHashMap<>();
		sec.put("mode", this.security.isOpenMode() ? "open (reads public, writes require login)" : "closed");
		sec.put("adminUsername", this.security.getAdminUsername());
		sec.put("adminPasswordConfigured",
				this.security.getAdminPassword() != null && !this.security.getAdminPassword().isBlank());
		sec.put("perUserIdentity", false);
		sec.put("rbacAware", false);
		out.put("security", sec);
		out.put("aiEnabled", this.ai.isEnabled());
		// The pod file browser's two gates, so the UI can decide whether to offer the tab
		// on FIRST paint. Without this it can only learn by making a request that fails,
		// which means a tab that exists solely to explain that it does not work.
		Map<String, Object> podFiles = new LinkedHashMap<>();
		podFiles.put("enabled", this.files.isEnabled());
		podFiles.put("writable", this.files.isWritable());
		// The write cap, so an upload that cannot fit is refused before the browser reads
		// a gigabyte off disk to be told 413. The cap is still enforced server-side —
		// this
		// only lets the refusal happen without the round trip. Not a secret: it is a
		// limit,
		// and the same number already appears in the 413 the server sends.
		podFiles.put("maxWriteBytes", this.files.getMaxWriteBytes());
		// Where the browser may go when it is confined; empty means the whole disk.
		// Reported because otherwise the UI opens on "/", which every confined deployment
		// refuses — the confinement works, but the browser cannot know where to start.
		// This is deployment policy, not a secret: the path-outside-roots refusal already
		// quotes the list verbatim to anyone who asks for a path outside it.
		podFiles.put("allowedRoots", List.copyOf(this.files.getAllowedRoots()));
		out.put("podFiles", podFiles);
		Map<String, Object> sim = new LinkedHashMap<>();
		sim.put("enabled", this.simulator.isEnabled());
		if (this.simulator.isEnabled()) {
			sim.put("clusterId", this.simulator.getClusterId());
			sim.put("size", this.simulator.getSize());
			sim.put("namespaces", this.simulator.getNamespaces());
		}
		out.put("simulator", sim);
		return out;
	}

}

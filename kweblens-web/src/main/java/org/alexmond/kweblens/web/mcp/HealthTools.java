package org.alexmond.kweblens.web.mcp;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import org.alexmond.kweblens.health.ConfigUsageService;
import org.alexmond.kweblens.health.HealthService;
import org.alexmond.kweblens.health.KindHealth;
import org.alexmond.kweblens.health.NetworkHealthService;
import org.alexmond.kweblens.health.StorageHealthService;
import org.alexmond.kweblens.health.WorkloadHealth;
import org.alexmond.kweblens.metric.MetricService;
import org.alexmond.kweblens.metric.UsageSummary;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.web.helm.HelmReleaseSummary;
import org.alexmond.kweblens.web.helm.HelmService;
import org.alexmond.kweblens.web.nav.NavCatalog;

/**
 * "What is wrong here?" — the same server-side checks the dashboard's overviews render.
 *
 * <p>
 * Exposed rather than left for the assistant to reconstruct, for two reasons. The checks
 * already do joins a model would otherwise have to perform by hand across several tool
 * calls — a Service against its Endpoints, a workload against its replica counts — and
 * every one of them returns a <b>reason</b>, not just a count. And sharing the
 * implementation means the assistant and the dashboard cannot disagree about whether
 * something is broken, which they would if each had its own rules.
 */
@Component
@RequiredArgsConstructor
public class HealthTools {

	private static final String WORKLOADS = "Workloads";

	private final NavCatalog navCatalog;

	private final HealthService health;

	private final NetworkHealthService network;

	private final StorageHealthService storage;

	private final ConfigUsageService config;

	private final MetricService metrics;

	private final HelmService helm;

	@Tool(description = "Check workload health: per-kind tallies plus the NAMED objects needing attention, "
			+ "each with a reason such as '2/3 ready', 'CrashLoopBackOff' or 'ImagePullBackOff'. "
			+ "Start here to find what is broken, then use describeResource and getPodLogs on what it names.")
	public List<KindHealth> checkWorkloadHealth(@ToolParam(description = "kweblens cluster id") String clusterId,
			@ToolParam(required = false, description = "namespace, or omit for the whole cluster") String namespace) {
		List<ResourceDescriptor> kinds = this.navCatalog.categories()
			.stream()
			.filter((c) -> WORKLOADS.equals(c.label()))
			.flatMap((c) -> c.items().stream())
			.filter((d) -> WorkloadHealth.supports(d.kind()))
			.toList();
		return this.health.summarise(clusterId, kinds, namespace);
	}

	@Tool(description = "Check Services for having nothing behind them — the failure where DNS resolves and "
			+ "every request still fails. Distinguishes 'no endpoints' (selector wrong or workload gone) "
			+ "from 'N pods matched, none ready' (deployed but failing readiness), which have different fixes.")
	public List<KindHealth> checkNetworkHealth(@ToolParam(description = "kweblens cluster id") String clusterId,
			@ToolParam(required = false, description = "namespace, or omit for the whole cluster") String namespace) {
		return this.network.summarise(clusterId, namespace);
	}

	@Tool(description = "Check PersistentVolumeClaims that are not bound, naming the StorageClass. "
			+ "An unbound claim is a pod that will never start. Does NOT check capacity — that needs a "
			+ "metrics source, not the Kubernetes API.")
	public List<KindHealth> checkStorageHealth(@ToolParam(description = "kweblens cluster id") String clusterId,
			@ToolParam(required = false, description = "namespace, or omit for the whole cluster") String namespace) {
		return this.storage.summarise(clusterId, namespace);
	}

	@Tool(description = "List ConfigMaps and Secrets nothing in the namespace references. ADVISORY ONLY: "
			+ "this is not evidence an object is unused. It cannot see references from a workload template "
			+ "whose pods do not exist yet, from another namespace, or from a custom resource, so on a real "
			+ "cluster most results are in use. Never recommend deleting on the strength of this alone.")
	public List<KindHealth> checkConfigUsage(@ToolParam(description = "kweblens cluster id") String clusterId,
			@ToolParam(required = false, description = "namespace, or omit for the whole cluster") String namespace) {
		return this.config.summarise(clusterId, namespace);
	}

	@Tool(description = "Current CPU and memory usage per pod, from metrics-server. Use for OOM questions: "
			+ "compare against the container's resources.limits.memory from describeResource.")
	public List<UsageSummary> getPodUsage(@ToolParam(description = "kweblens cluster id") String clusterId,
			@ToolParam(required = false, description = "namespace, or omit for all namespaces") String namespace) {
		return this.metrics.podUsage(clusterId, namespace);
	}

	@Tool(description = "Current CPU and memory usage per node, from metrics-server. "
			+ "Use when pods are Pending for lack of resources.")
	public List<UsageSummary> getNodeUsage(@ToolParam(description = "kweblens cluster id") String clusterId) {
		return this.metrics.nodeUsage(clusterId);
	}

	@Tool(description = "List Helm releases with their status and chart version. "
			+ "Use when a workload is managed by Helm and the question is what was deployed.")
	public List<HelmReleaseSummary> listHelmReleases(@ToolParam(description = "kweblens cluster id") String clusterId,
			@ToolParam(required = false, description = "namespace, or omit for all namespaces") String namespace) {
		return this.helm.listReleases(clusterId, namespace);
	}

}

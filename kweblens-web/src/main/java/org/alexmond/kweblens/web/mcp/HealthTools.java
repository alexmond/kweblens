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
import org.alexmond.kweblens.health.SecurityAuditService;
import org.alexmond.kweblens.health.SecurityFinding;
import org.alexmond.kweblens.health.StorageHealthService;
import org.alexmond.kweblens.health.WorkloadHealth;
import org.alexmond.kweblens.metric.MetricService;
import org.alexmond.kweblens.metric.UsageSummary;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.web.ai.DeterministicDiagnosis;
import org.alexmond.kweblens.web.ai.DiagnoseResult;
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

	private final SecurityAuditService security;

	private final MetricService metrics;

	private final HelmService helm;

	/**
	 * The read half of the diagnosis. Deliberately <b>not</b> {@code DiagnoseService}:
	 * the port has no {@code analyse} method, so the one call in kweblens that reaches an
	 * LLM cannot be made from a surface a model drives. See
	 * {@link DeterministicDiagnosis}.
	 */
	private final DeterministicDiagnosis diagnosis;

	/**
	 * The whole finding list, which is a verdict — which is why it is here and not in
	 * {@code DiagnosticTools}, whose job is to hand over raw evidence.
	 *
	 * <p>
	 * One tool rather than one per dimension. The dimensions already have tools; what was
	 * missing is the question an operator actually asks first, which is not about a
	 * dimension at all. Splitting it would also hand the model the job the checks do
	 * themselves — deduplicating an event against the container state that already
	 * explained it, sorting by severity, and not reporting a Service twice — and a model
	 * that called three of the four would get a confidently partial answer with nothing
	 * saying so.
	 *
	 * <p>
	 * {@code DiagnoseResult} is returned unchanged, so the assistant and the dashboard's
	 * diagnosis panel read the same bytes from the same call. Its {@code summary} is
	 * served only from the cache and only when the findings still fingerprint the same —
	 * reading it never buys one.
	 *
	 * <p>
	 * <b>No {@link ToolRedaction}, and the reason is the return type.</b> That guard
	 * takes a {@code GenericKubernetesResource} and strips the two places an object
	 * carries a secret: a Secret's {@code data}/{@code stringData} maps, and the verbatim
	 * copy of the manifest in {@code last-applied-configuration}. A
	 * {@code DiagnoseResult} holds no object at all — every field is a {@code String}, a
	 * {@code boolean} or an {@code Instant}, and no validator copies a {@code spec}, a
	 * {@code data} map or an annotation into one. The same is already true of
	 * {@code checkSecurity} and {@code getEvents}; the standing rule is about tools
	 * returning <i>raw objects</i>.
	 *
	 * <p>
	 * What that does <b>not</b> claim: a finding's {@code detail} can be an event
	 * message, which is cluster-controlled text, so an operator or controller that writes
	 * a credential into an event has published it to anything that can read events — and
	 * {@code ToolRedaction} would not have caught it either, because it recognises Secret
	 * fields, not secret-shaped strings.
	 */
	@Tool(description = "Every deterministic check at once, as one prioritised finding list: failing pods with "
			+ "the container state and exit code behind them, Services with nothing ready, unbound volume claims, "
			+ "deduplicated warning events, and what the scope permits. Each finding names an object, the observed "
			+ "evidence and a suggested fix. Ask this first for an open-ended 'what is wrong here?'; the check* "
			+ "tools answer one dimension each. 'incomplete' lists checks that did not fully run over this scope — "
			+ "what it names is UNCHECKED, not clean. No model is consulted; a 'summary', when present, is a cached "
			+ "one an operator paid for about exactly these findings.")
	public DiagnoseResult diagnose(@ToolParam(description = "kweblens cluster id") String clusterId,
			@ToolParam(required = false, description = "namespace, or omit for the whole cluster") String namespace) {
		return this.diagnosis.diagnose(clusterId, namespace);
	}

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
			+ "An unbound claim is a pod that will never start. Also flags a BOUND claim that is at or "
			+ "above 90% full, but only when a metrics source is configured and reports a figure "
			+ "plausible for the claim's requested size; without one, capacity is simply not reported.")
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

	@Tool(description = "Check what this scope is configured to PERMIT: containers claiming privilege from "
			+ "their own spec, and RBAC bindings that make an identity cluster administrator — including "
			+ "which pods actually run as such an identity, which no single object states. These describe "
			+ "the cluster's configuration, not a failure and not who is asking; nothing here is broken.")
	public List<SecurityFinding> checkSecurity(@ToolParam(description = "kweblens cluster id") String clusterId,
			@ToolParam(required = false, description = "namespace, or omit for the whole cluster") String namespace) {
		// The findings only. The audit's coverage gaps summarise this same list and each
		// one already has its finding here — so nothing is lost, the tool's shape is
		// unchanged, and no new field crosses the MCP boundary to be redacted.
		return this.security.audit(clusterId, namespace).findings();
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

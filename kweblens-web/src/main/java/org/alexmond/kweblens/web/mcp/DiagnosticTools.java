package org.alexmond.kweblens.web.mcp;

import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import lombok.RequiredArgsConstructor;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import org.alexmond.kweblens.event.EventService;
import org.alexmond.kweblens.event.EventSummary;
import org.alexmond.kweblens.log.LogService;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.web.nav.ClusterNavService;

/**
 * The tools an assistant needs to actually diagnose something, rather than merely list
 * it.
 *
 * <p>
 * Each one exists because {@code docs/design/failure-taxonomy.md} walked a real failure
 * and found the evidence somewhere a summary does not reach. In particular:
 * {@link #describeResource} returns the <b>raw</b> object because a crashloop's cause
 * lives in {@code lastState.terminated} and an unschedulable pod's in a condition
 * message; {@link #getEvents} exists because a PVC stuck Pending says nothing at all
 * while its events name the missing StorageClass; and {@link #getPodLogs} takes
 * {@code previous} because after a restart the current log begins at the new process, so
 * the output that explains the crash is only in the terminated instance's.
 */
@Component
@RequiredArgsConstructor
public class DiagnosticTools {

	/**
	 * Cap on log lines returned to a tool caller. Generous enough for a stack trace,
	 * bounded because this output goes into a context window — and the cap is stated in
	 * the result rather than applied silently, since a model told "here is the log" will
	 * reason as though it is complete.
	 */
	private static final int MAX_LOG_LINES = 500;

	private final ResourceService resources;

	private final EventService events;

	private final LogService logs;

	private final ClusterNavService clusterNav;

	@Tool(description = "Describe one Kubernetes object in full, as raw YAML-equivalent JSON. "
			+ "Use this when a summary is not enough: a crashlooping container's exit code is in "
			+ "status.containerStatuses[].lastState.terminated, and an unschedulable pod's reason is in "
			+ "status.conditions[type=PodScheduled].message. Secret values are redacted.")
	public GenericKubernetesResource describeResource(@ToolParam(description = "kweblens cluster id") String clusterId,
			@ToolParam(
					description = "resource id from listResourceKinds, e.g. 'pods' or 'deployments'") String resourceId,
			@ToolParam(required = false, description = "namespace; omit for cluster-scoped kinds") String namespace,
			@ToolParam(description = "object name") String name) {
		ResourceDescriptor descriptor = descriptor(clusterId, resourceId);
		return ToolRedaction.forTool(this.resources.getRaw(clusterId, descriptor, namespace, name));
	}

	@Tool(description = "List objects of one kind, optionally in one namespace. "
			+ "Use this to navigate from a workload to its pods, replica sets or claims.")
	public List<?> listResources(@ToolParam(description = "kweblens cluster id") String clusterId,
			@ToolParam(description = "resource id, e.g. 'pods' or 'persistentvolumeclaims'") String resourceId,
			@ToolParam(required = false, description = "namespace, or omit for all namespaces") String namespace) {
		return this.resources.list(clusterId, descriptor(clusterId, resourceId), namespace);
	}

	@Tool(description = "Kubernetes events, newest first. Scope to one object by passing kind and name. "
			+ "Events are the ONLY evidence for some failures: a PersistentVolumeClaim stuck Pending carries "
			+ "no reason on the object, but its events name the missing StorageClass. Also where quota "
			+ "rejections, admission-webhook refusals and probe failures appear.")
	public List<EventSummary> getEvents(@ToolParam(description = "kweblens cluster id") String clusterId,
			@ToolParam(required = false, description = "namespace, or omit for the whole cluster") String namespace,
			@ToolParam(required = false, description = "object kind, e.g. 'Pod', to scope to one object") String kind,
			@ToolParam(required = false, description = "object name, used with kind") String name) {
		if (kind != null && !kind.isBlank() && name != null && !name.isBlank()) {
			return this.events.listForObject(clusterId, namespace, kind, name);
		}
		return this.events.list(clusterId, namespace);
	}

	@Tool(description = "A pod container's log. Set previous=true for the log of the PREVIOUS run, which is "
			+ "the crashloop diagnostic: after a restart the current log begins at the new process, so the "
			+ "output explaining the crash exists only in the terminated instance's log.")
	public Map<String, Object> getPodLogs(@ToolParam(description = "kweblens cluster id") String clusterId,
			@ToolParam(description = "namespace") String namespace, @ToolParam(description = "pod name") String pod,
			@ToolParam(required = false, description = "container name; omit for the pod's default") String container,
			@ToolParam(required = false, description = "lines to return, capped at 500") Integer tailLines,
			@ToolParam(required = false, description = "true for the previous run's log") Boolean previous) {
		int lines = Math.min((tailLines != null && tailLines > 0) ? tailLines : 200, MAX_LOG_LINES);
		boolean wantPrevious = Boolean.TRUE.equals(previous);
		String log = wantPrevious ? this.logs.previous(clusterId, namespace, pod, container, lines)
				: this.logs.tail(clusterId, namespace, pod, container, lines);
		if (log == null || log.isBlank()) {
			// "There is nothing here" is reported as unavailable rather than as an empty
			// log, because the two invite opposite conclusions: a model handed
			// {log: ""} reads it as "the container ran and printed nothing" and will
			// reason from that. The wording does not overclaim either — a container that
			// restarted but logged nothing is indistinguishable from one that never
			// restarted, and the API does not separate them.
			return Map.of("available", false, "reason", wantPrevious
					? "no previous run, or its logs are no longer retained" : "the container has produced no output");
		}
		return Map.of("available", true, "requestedLines", lines, "maxLines", MAX_LOG_LINES, "log", log);
	}

	private ResourceDescriptor descriptor(String clusterId, String resourceId) {
		return this.clusterNav.find(clusterId, resourceId)
			.orElseThrow(() -> new IllegalArgumentException("Unknown resource id '" + resourceId
					+ "'. Call listResourceKinds to see what this cluster serves."));
	}

}

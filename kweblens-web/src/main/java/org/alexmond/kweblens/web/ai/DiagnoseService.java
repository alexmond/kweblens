package org.alexmond.kweblens.web.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import org.alexmond.kweblens.event.EventService;
import org.alexmond.kweblens.event.EventSummary;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.WellKnownKinds;
import org.alexmond.kweblens.resource.ResourceService;

/**
 * Validates and troubleshoots a cluster/namespace. Deterministic validators run first —
 * no LLM, so this always returns grounded findings and works in CI. When
 * {@code kweblens.ai.enabled} is on and an Anthropic {@link ChatClient} is available, the
 * LLM is fed those real findings to produce a prioritized root-cause summary (it reasons
 * over observed evidence, not invented state). Read-only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnoseService {

	private static final ResourceDescriptor PODS = WellKnownKinds.PODS;

	private static final Set<String> HEALTHY_PHASES = Set.of("Running", "Succeeded");

	private static final Set<String> CRITICAL_WAIT_REASONS = Set.of("CrashLoopBackOff", "ImagePullBackOff",
			"ErrImagePull", "CreateContainerConfigError", "CreateContainerError");

	private static final Comparator<Finding> BY_SEVERITY = Comparator.comparingInt((Finding f) -> severityRank(f));

	private final ResourceService resources;

	private final EventService events;

	private final ObjectProvider<ChatClient.Builder> chatClientBuilder;

	private final KweblensAiProperties aiProperties;

	public DiagnoseResult diagnose(String clusterId, String namespace) {
		List<Finding> findings = new ArrayList<>();
		findings.addAll(checkPods(clusterId, namespace));
		findings.addAll(checkEvents(clusterId, namespace));
		findings.sort(BY_SEVERITY);

		String summary = null;
		boolean enriched = false;
		if (aiProperties.isEnabled() && !findings.isEmpty()) {
			ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
			if (builder != null) {
				summary = summarize(builder, findings);
				enriched = summary != null;
			}
		}
		return new DiagnoseResult(List.copyOf(findings), summary, enriched);
	}

	private List<Finding> checkPods(String clusterId, String namespace) {
		List<Finding> findings = new ArrayList<>();
		for (GenericKubernetesResource pod : resources.listRaw(clusterId, PODS, namespace)) {
			String name = objectName(pod);
			Map<String, Object> status = asMap(pod.getAdditionalProperties().get("status"));
			String phase = str(status.get("phase"));
			if (phase != null && !HEALTHY_PHASES.contains(phase)) {
				findings.add(new Finding("warning", "Pod not running", "Pod/" + name, "phase=" + phase,
						"Check the pod's events and describe output.", "validator"));
			}
			findings.addAll(checkContainerStatuses(name, status));
		}
		return findings;
	}

	private List<Finding> checkContainerStatuses(String podName, Map<String, Object> status) {
		List<Finding> findings = new ArrayList<>();
		for (Object container : asList(status.get("containerStatuses"))) {
			Map<String, Object> waiting = asMap(asMap(asMap(container).get("state")).get("waiting"));
			String reason = str(waiting.get("reason"));
			if (reason != null && CRITICAL_WAIT_REASONS.contains(reason)) {
				findings.add(new Finding("critical", reason, "Pod/" + podName, str(waiting.get("message")),
						suggestFor(reason), "validator"));
			}
		}
		return findings;
	}

	private List<Finding> checkEvents(String clusterId, String namespace) {
		List<Finding> findings = new ArrayList<>();
		for (EventSummary event : events.list(clusterId, namespace)) {
			if ("Warning".equals(event.type())) {
				findings.add(new Finding("warning", event.reason(), event.object(), event.message(),
						"Investigate the object referenced by this warning event.", "validator"));
			}
		}
		return findings;
	}

	private String summarize(ChatClient.Builder builder, List<Finding> findings) {
		try {
			StringBuilder evidence = new StringBuilder();
			for (Finding f : findings) {
				evidence.append("- [")
					.append(f.severity())
					.append("] ")
					.append(f.object())
					.append(": ")
					.append(f.title())
					.append(" (")
					.append(f.detail())
					.append(")\n");
			}
			return builder.build()
				.prompt()
				.system("You are a senior Kubernetes SRE. Given these observed findings, give a short "
						+ "prioritized root-cause summary and the single most important next action. "
						+ "Only use the evidence provided; do not invent resources.")
				.user(evidence.toString())
				.call()
				.content();
		}
		catch (RuntimeException ex) {
			log.warn("AI enrichment failed; returning deterministic findings only: {}", ex.getMessage());
			return null;
		}
	}

	private String suggestFor(String reason) {
		return switch (reason) {
			case "CrashLoopBackOff" -> "Inspect container logs (previous run) and the exit code; fix the crash cause.";
			case "ImagePullBackOff", "ErrImagePull" -> "Verify the image name/tag and pull credentials.";
			case "CreateContainerConfigError" -> "Check referenced ConfigMaps/Secrets exist and keys match.";
			default -> "Describe the pod and inspect its container state.";
		};
	}

	private static int severityRank(Finding finding) {
		return switch (finding.severity()) {
			case "critical" -> 0;
			case "warning" -> 1;
			default -> 2;
		};
	}

	private String objectName(GenericKubernetesResource resource) {
		return (resource.getMetadata() != null) ? resource.getMetadata().getName() : "?";
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> asMap(Object value) {
		return (value instanceof Map) ? (Map<String, Object>) value : Map.of();
	}

	private List<?> asList(Object value) {
		return (value instanceof List) ? (List<?>) value : List.of();
	}

	private String str(Object value) {
		return (value != null) ? value.toString() : null;
	}

}

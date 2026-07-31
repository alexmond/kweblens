package org.alexmond.kweblens.web.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import org.alexmond.kweblens.event.EventService;
import org.alexmond.kweblens.health.KindHealth;
import org.alexmond.kweblens.health.NetworkHealthService;
import org.alexmond.kweblens.health.StorageHealthService;
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

	/**
	 * Cap on event-derived findings, so one noisy namespace cannot bury everything else.
	 */
	private static final int MAX_EVENT_FINDINGS = 15;

	private final ResourceService resources;

	private final EventService events;

	private final NetworkHealthService network;

	private final StorageHealthService storage;

	private final ObjectProvider<ChatClient.Builder> chatClientBuilder;

	private final KweblensAiProperties aiProperties;

	public DiagnoseResult diagnose(String clusterId, String namespace) {
		List<Finding> findings = new ArrayList<>();
		findings.addAll(checkPods(clusterId, namespace));
		findings.addAll(checkRelational(clusterId, namespace));
		// Events last, and told what the checks above already explained, so they add
		// evidence rather than repeat it.
		findings.addAll(checkEvents(clusterId, namespace, findings));
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
			findings.addAll(PodDiagnosis.forPod(pod));
		}
		return findings;
	}

	/**
	 * The relational failures a pod-by-pod scan cannot see, from the same checks the
	 * dashboard renders.
	 *
	 * <p>
	 * A Service with no ready endpoints and an unbound claim are both invisible on any
	 * one object — the answer needs a second one. Reusing the overview's services rather
	 * than reimplementing the joins also means a diagnosis and the Network/Storage pages
	 * cannot disagree about whether something is broken.
	 */
	private List<Finding> checkRelational(String clusterId, String namespace) {
		List<Finding> findings = new ArrayList<>();
		for (KindHealth kind : this.network.summarise(clusterId, namespace)) {
			for (KindHealth.UnhealthyItem item : kind.needsAttention()) {
				findings.add(new Finding("critical", "Service has nothing behind it", item.kind() + "/" + item.name(),
						item.reason(),
						"no endpoints".equals(item.reason())
								? "Check the Service's selector against the pod labels it is meant to match."
								: "The pods exist but are not ready — check their readiness probe and logs.",
						"validator"));
			}
		}
		for (KindHealth kind : this.storage.summarise(clusterId, namespace)) {
			for (KindHealth.UnhealthyItem item : kind.needsAttention()) {
				findings.add(new Finding("warning", "Volume claim needs attention", item.kind() + "/" + item.name(),
						item.reason(),
						item.reason().startsWith("Pending")
								? "The claim is not bound — check that its StorageClass exists and has a provisioner."
								: "The volume is nearly full — free space or expand the claim.",
						"validator"));
			}
		}
		return findings;
	}

	/**
	 * Warning events, deduplicated and filtered to the ones that add something.
	 *
	 * <p>
	 * Every warning event used to become a finding. On a real namespace that is the same
	 * probe failure repeated dozens of times, plus a BackOff event for the crashloop the
	 * container check already explained in more detail — so the findings the reader needs
	 * end up buried under restatements. Events stay because for some failures they are
	 * the ONLY evidence (a claim's provisioning error lives nowhere else); they are just
	 * no longer allowed to drown it.
	 */
	private List<Finding> checkEvents(String clusterId, String namespace, List<Finding> already) {
		Set<String> explained = new HashSet<>();
		for (Finding finding : already) {
			explained.add(objectKey(finding.object()));
		}
		Set<String> seen = new HashSet<>();
		List<Finding> findings = new ArrayList<>();
		for (EventSummary event : events.list(clusterId, namespace)) {
			if (!"Warning".equals(event.type()) || explained.contains(objectKey(event.object()))) {
				continue;
			}
			// One row per object+reason: the same probe failing 1600 times is one
			// problem.
			if (!seen.add(event.object() + "/" + event.reason()) || findings.size() >= MAX_EVENT_FINDINGS) {
				continue;
			}
			findings.add(new Finding("warning", event.reason(), event.object(), event.message(),
					"This event is the only record of the problem — start from the object it names.", "validator"));
		}
		return findings;
	}

	/**
	 * Objects are named "Kind/name" by events and "Pod/name container x" by the pod
	 * checks.
	 */
	private String objectKey(String object) {
		if (object == null) {
			return "";
		}
		int space = object.indexOf(' ');
		return (space > 0) ? object.substring(0, space) : object;
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

	private static int severityRank(Finding finding) {
		return switch (finding.severity()) {
			case "critical" -> 0;
			case "warning" -> 1;
			default -> 2;
		};
	}

}

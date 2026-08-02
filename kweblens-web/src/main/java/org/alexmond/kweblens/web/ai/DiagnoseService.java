package org.alexmond.kweblens.web.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * LLM can be fed those real findings to produce a prioritized root-cause summary (it
 * reasons over observed evidence, not invented state). Read-only.
 *
 * <p>
 * <b>The two entry points are not symmetric, on purpose (#251).</b> {@link #diagnose}
 * runs the validators and serves whatever summary the {@link DiagnosisSummaryCache}
 * already holds for exactly those findings — it never calls a model. {@link #analyse} is
 * the only thing in kweblens that does, and it exists because somebody pressed a button:
 * inference costs money and ships cluster state to a third party, so it is a non-GET,
 * auth-gated in both security modes, and audited. Panel mounts and namespace switches
 * used to buy an analysis each; now they cost nothing.
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

	/** The general wording, used when the cause cannot be narrowed further. */
	private static final String SELECTOR_ADVICE = "Check the Service's selector against the pod labels"
			+ " it is meant to match.";

	/**
	 * Cap on the problem groups sent to the model — the findings are grouped first, so
	 * this is 25 <em>distinct</em> problems, not 25 objects. A cluster whose findings all
	 * say the same thing costs one group; only a cluster with more than 25 genuinely
	 * different problems is truncated, and then the least severe go (the list is sorted
	 * by severity before grouping) and the drop is stated in the summary rather than
	 * happening silently.
	 */
	private static final int MAX_PROMPT_GROUPS = 25;

	/**
	 * Objects named per group before the rest become "(+N more)". Generous on purpose: a
	 * name is the cheapest thing in the block (the repetition that had to go was the
	 * title, evidence and fix restated per object), and it is what lets the model tell a
	 * group apart from itself. At 6 it answered a group of 16 Services by assuming 15
	 * were the 3 control-plane scrape targets it could see — a wrong count, which is
	 * exactly the failure grouping is supposed to prevent.
	 */
	private static final int MAX_OBJECTS_PER_GROUP = 15;

	/** Distinct evidence messages quoted per group. */
	private static final int MAX_EVIDENCE_PER_GROUP = 4;

	/** A scheduler or image-pull message can run to several hundred characters. */
	private static final int MAX_DETAIL_CHARS = 220;

	/**
	 * The summary is read <em>above</em> a table that already lists every finding, its
	 * evidence and its suggested fix. So the only thing it can add is judgement: what to
	 * do first, what is one problem wearing many hats, and what is not a fault at all.
	 * Every rule here is a correction of something the earlier prompt produced against a
	 * real cluster — a two-section essay with invented headings that re-listed the table
	 * and ranked a monitoring Service that never has endpoints as the top problem.
	 */
	private static final String SUMMARY_SYSTEM_PROMPT = """
			You are a senior Kubernetes SRE. The reader can already see a table of every \
			finding, its evidence and a suggested fix, directly below your text. Your job \
			is to tell them what to do about it, not to restate it.

			Write, in this order and nothing else:
			1. One sentence: the single most important next action, naming the object to \
			take it on. One action — not a list of them.
			2. Then at most three bullets, each starting "- ", and only for something that \
			changes what the reader would do: a second unrelated problem, one cause that \
			explains several groups, or a group that is probably not a fault.

			Rules:
			- 90 words maximum. No headings, no titles, no closing paragraph.
			- Use only the evidence given. Never invent an object, a cause or a number.
			- Each group's object count is exact. Say "14 Services", never write about a \
			group as if it were a single object, and never attribute a group's whole \
			count to a subset of it: if 3 of 16 objects are of some kind, the number is 3.
			- The biggest group is not automatically the biggest problem, and not every \
			finding is a fault — a Service that exists only as a scrape target for a \
			control-plane component has no endpoints by design. Split such a group in \
			two: how many you can account for from the names and evidence, and how many \
			still need checking. Never assign a reason to objects you cannot account for.
			""";

	private final ResourceService resources;

	private final EventService events;

	private final NetworkHealthService network;

	private final StorageHealthService storage;

	private final ObjectProvider<ChatClient.Builder> chatClientBuilder;

	private final KweblensAiProperties aiProperties;

	private final WorkloadLookup workloads;

	private final DiagnosisSummaryCache summaries;

	/**
	 * Read the diagnosis. Deterministic only — this MUST NOT reach a model, however
	 * tempting a cache miss makes it. The summary it returns was bought by an earlier
	 * {@link #analyse} and is served only when the findings still fingerprint the same.
	 */
	public DiagnoseResult diagnose(String clusterId, String namespace) {
		List<Finding> findings = findings(clusterId, namespace);
		return served(clusterId, namespace, findings, DiagnosisSummaryCache.fingerprint(findings));
	}

	/**
	 * Buy an analysis of this scope, replacing any cached one.
	 *
	 * <p>
	 * Always re-runs on a hit: the caller pressed "Re-analyse" against a summary they can
	 * already see, so quietly returning that same summary would answer a request nobody
	 * made. Empty findings short-circuit — there is nothing to summarise and no reason to
	 * spend a call saying so.
	 */
	public DiagnoseResult analyse(String clusterId, String namespace) {
		ChatClient.Builder builder = availableClient();
		if (builder == null) {
			throw new AiUnavailableException(
					"AI analysis is not configured on this server (kweblens.ai.enabled and an API key are required).");
		}
		List<Finding> findings = findings(clusterId, namespace);
		String fingerprint = DiagnosisSummaryCache.fingerprint(findings);
		if (!findings.isEmpty()) {
			String summary = summarize(builder, findings);
			if (summary != null) {
				this.summaries.put(clusterId, namespace, fingerprint, summary, findings.size());
			}
			// A null summary means the model was called and did not answer usably.
			// Nothing is cached, so the trigger stays live rather than pinning a
			// failure until the findings happen to change.
		}
		return served(clusterId, namespace, findings, fingerprint);
	}

	private List<Finding> findings(String clusterId, String namespace) {
		List<Finding> findings = new ArrayList<>();
		findings.addAll(checkPods(clusterId, namespace));
		findings.addAll(checkRelational(clusterId, namespace));
		// Events last, and told what the checks above already explained, so they add
		// evidence rather than repeat it.
		findings.addAll(checkEvents(clusterId, namespace, findings));
		findings.sort(BY_SEVERITY);
		return List.copyOf(findings);
	}

	/**
	 * What a caller is shown for this scope — the single place that decides whether a
	 * stored summary is still allowed to be seen, so the read and the trigger cannot
	 * drift apart on the question.
	 */
	private DiagnoseResult served(String clusterId, String namespace, List<Finding> findings, String fingerprint) {
		boolean available = availableClient() != null;
		DiagnosisSummaryCache.CachedSummary cached = this.summaries.find(clusterId, namespace, fingerprint);
		if (cached != null) {
			return new DiagnoseResult(findings, cached.summary(), true, cached.producedAt(), false, available);
		}
		// No usable summary. If this scope WAS analysed, say when — an unexplained
		// "never analysed" after the reader has seen one reads as the panel losing it.
		Instant superseded = this.summaries.supersededAt(clusterId, namespace, fingerprint);
		return new DiagnoseResult(findings, null, false, superseded, superseded != null, available);
	}

	/**
	 * The chat client to use, or null when this instance cannot run an analysis at all.
	 */
	private ChatClient.Builder availableClient() {
		return this.aiProperties.isEnabled() ? this.chatClientBuilder.getIfAvailable() : null;
	}

	private List<Finding> checkPods(String clusterId, String namespace) {
		List<Finding> findings = new ArrayList<>();
		for (GenericKubernetesResource pod : resources.listRaw(clusterId, PODS, namespace)) {
			findings.addAll(PodDiagnosis.forPod(pod));
		}
		return findings;
	}

	/**
	 * A Service with nothing behind it, told what to do about the cause it actually has.
	 *
	 * <p>
	 * Every case used to get "check the Service's selector against the pod labels it is
	 * meant to match". That is wrong advice for the commonest one: when a single workload
	 * carries the selector and sits at zero replicas, the selector is the one thing that
	 * IS right, and scaling up is the fix — which {@code RemediationService} already
	 * offers as an action.
	 *
	 * <p>
	 * {@code backing} is null when the lookup could not speak to the cluster, or when the
	 * Service has no selector at all. Both fall back to the general wording rather than
	 * guessing.
	 */
	private Finding serviceFinding(KindHealth.UnhealthyItem item, ServiceBacking backing) {
		String object = item.kind() + "/" + item.name();
		if (!"no endpoints".equals(item.reason())) {
			return new Finding("critical", "Service has nothing behind it", object, item.reason(),
					"The pods exist but are not ready — check their readiness probe and logs.", "validator");
		}
		// The detail is left EXACTLY as the health check produced it. RemediationService
		// selects the scale-up proposal with `NO_ENDPOINTS.equals(finding.detail())`, so
		// appending the workload name here — which the first version of this did —
		// matches
		// nothing and silently stops the fix being offered at all. Everything this knows
		// goes in the advice instead, which no one matches on.
		String fix = (backing != null) ? fixFor(backing) : SELECTOR_ADVICE;
		return new Finding("critical", "Service has nothing behind it", object, item.reason(), fix, "validator");
	}

	private String fixFor(ServiceBacking backing) {
		return switch (backing.cause()) {
			case IDLE_WORKLOAD -> "Nothing is running behind it: " + backing.workload().ref()
					+ " is scaled to zero. Scale it up — the selector is correct.";
			case NO_MATCH ->
				"No workload's pod template carries this selector, so nothing will ever back it. " + SELECTOR_ADVICE;
			case AMBIGUOUS -> "Several workloads carry this selector, so which one it was meant for is unclear.";
			case MATCHED_RUNNING -> "A workload carries this selector and is not scaled down,"
					+ " so look at why its pods are not becoming endpoints.";
		};
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
		List<KindHealth> services = this.network.summarise(clusterId, namespace);
		// Resolved once for the whole scope rather than per finding — see
		// backingByService.
		// Only worth the three calls if something is actually empty.
		Map<String, ServiceBacking> backing = services.stream().anyMatch((k) -> !k.needsAttention().isEmpty())
				? this.workloads.backingByService(clusterId, namespace) : Map.of();
		for (KindHealth kind : services) {
			for (KindHealth.UnhealthyItem item : kind.needsAttention()) {
				findings.add(serviceFinding(item, backing.get(item.namespace() + "/" + item.name())));
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
		PromptInput input = promptInput(findings);
		log.debug("Diagnosis prompt built from {} findings:\n{}", findings.size(), input.evidence());
		try {
			String content = builder.build()
				.prompt()
				.system(SUMMARY_SYSTEM_PROMPT)
				.user(input.evidence())
				.call()
				.content();
			if (content == null || content.isBlank()) {
				return null;
			}
			return (input.truncationNote() != null) ? content.strip() + "\n\n" + input.truncationNote()
					: content.strip();
		}
		catch (RuntimeException ex) {
			log.warn("AI enrichment failed; returning deterministic findings only: {}", ex.getMessage());
			return null;
		}
	}

	/**
	 * The model's input: one block per distinct problem, not one line per object.
	 *
	 * <p>
	 * The raw list is the wrong shape to reason over. On a real cluster sixteen of the
	 * thirty-one findings were the same sentence about a different Service, so most of
	 * the model's input was repetition and the five other criticals were a footnote in
	 * it. Grouping does the deduplication the UI already does (#226) before the tokens
	 * are spent — and keeps the count, because "16 Services have no endpoints" is the
	 * finding, while quietly showing one of them would be a lie.
	 */
	static PromptInput promptInput(List<Finding> findings) {
		Map<String, List<Finding>> groups = new LinkedHashMap<>();
		for (Finding finding : findings) {
			groups.computeIfAbsent(finding.severity() + ' ' + finding.title(), (key) -> new ArrayList<>()).add(finding);
		}
		List<List<Finding>> ordered = new ArrayList<>(groups.values());
		int shown = Math.min(ordered.size(), MAX_PROMPT_GROUPS);
		StringBuilder evidence = new StringBuilder(512);
		evidence.append(findings.size())
			.append(" findings, in ")
			.append(ordered.size())
			.append(" distinct problem groups (most severe first).\n\n");
		for (List<Finding> group : ordered.subList(0, shown)) {
			renderGroup(evidence, group);
		}
		int omitted = 0;
		for (List<Finding> group : ordered.subList(shown, ordered.size())) {
			omitted += group.size();
		}
		if (omitted == 0) {
			return new PromptInput(evidence.toString(), null);
		}
		evidence.append('(')
			.append(ordered.size() - shown)
			.append(" further, less severe groups covering ")
			.append(omitted)
			.append(" findings are not listed here.)\n");
		return new PromptInput(evidence.toString(), "Summarised from the " + (findings.size() - omitted)
				+ " most severe of " + findings.size() + " findings; the rest were not sent to the model.");
	}

	/** One group: what it is, how many objects, which ones, and what they said. */
	private static void renderGroup(StringBuilder out, List<Finding> group) {
		out.append('[').append(group.get(0).severity()).append("] ").append(group.get(0).title());
		out.append(" — ").append(group.size()).append(" object");
		if (group.size() > 1) {
			out.append('s');
		}
		int named = Math.min(group.size(), MAX_OBJECTS_PER_GROUP);
		out.append("\n  objects: ");
		for (int i = 0; i < named; i++) {
			if (i > 0) {
				out.append(", ");
			}
			out.append(group.get(i).object());
		}
		if (group.size() > named) {
			out.append(" (+").append(group.size() - named).append(" more)");
		}
		out.append('\n');
		renderEvidence(out, group);
	}

	/**
	 * Distinct evidence messages with their multiplicity, so identical detail collapses
	 * but a group whose members failed for different reasons still says so.
	 *
	 * <p>
	 * The header says how many of the distinct messages are shown when not all are.
	 * Without it the model reads a sample as the whole group: shown three of six probe
	 * failures, it reported that all six pods were failing on the one port those three
	 * named. A partial list has to look partial.
	 */
	private static void renderEvidence(StringBuilder out, List<Finding> group) {
		Map<String, Integer> messages = new LinkedHashMap<>();
		for (Finding finding : group) {
			messages.merge(abbreviate(finding.detail()), 1, Integer::sum);
		}
		if (messages.size() > MAX_EVIDENCE_PER_GROUP) {
			out.append("  evidence (")
				.append(MAX_EVIDENCE_PER_GROUP)
				.append(" of ")
				.append(messages.size())
				.append(" distinct messages): ");
		}
		else {
			out.append("  evidence: ");
		}
		int written = 0;
		for (Map.Entry<String, Integer> message : messages.entrySet()) {
			if (written == MAX_EVIDENCE_PER_GROUP) {
				break;
			}
			if (written > 0) {
				out.append("; ");
			}
			out.append(message.getKey());
			if (message.getValue() > 1) {
				out.append(" (x").append(message.getValue()).append(')');
			}
			written++;
		}
		out.append("\n\n");
	}

	private static String abbreviate(String detail) {
		if (detail == null || detail.isBlank()) {
			return "(no detail)";
		}
		String trimmed = detail.strip();
		return (trimmed.length() <= MAX_DETAIL_CHARS) ? trimmed
				: trimmed.substring(0, MAX_DETAIL_CHARS) + "…(truncated)";
	}

	private static int severityRank(Finding finding) {
		return switch (finding.severity()) {
			case "critical" -> 0;
			case "warning" -> 1;
			default -> 2;
		};
	}

	/**
	 * What is sent to the model, and the sentence appended to its answer when the cap bit
	 * — so a truncated diagnosis says so instead of reading like a complete one.
	 *
	 * @param evidence the grouped findings block
	 * @param truncationNote null when everything was sent
	 */
	record PromptInput(String evidence, String truncationNote) {
	}

}

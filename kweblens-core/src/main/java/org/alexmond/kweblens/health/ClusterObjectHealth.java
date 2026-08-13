package org.alexmond.kweblens.health;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

/**
 * What state a <b>cluster-scoped</b> object of the Cluster category is in — a Node or a
 * Namespace.
 *
 * <p>
 * The sibling of {@link WorkloadHealth}, reached through the same seam
 * ({@link StatusVocabulary}), so a Nodes card's numbers and a {@code status:} query over
 * the Nodes list are one definition rather than two that agree today.
 *
 * <h2>The vocabulary is kubectl's, deliberately</h2>
 *
 * A node prints as {@code Ready}, {@code NotReady}, {@code Ready,SchedulingDisabled} or
 * {@code NotReady,SchedulingDisabled} — the exact strings {@code kubectl get nodes} puts
 * in its STATUS column, assembled the same way (the Ready condition, then the cordon
 * suffix when {@code spec.unschedulable} is set). Borrowing an existing vocabulary rather
 * than minting one means the state on a card is the word the operator already ran
 * {@code kubectl} to see, and it keeps a cordoned-but-healthy node out of the plain
 * {@code Ready} count, which is what someone draining a node is looking for.
 *
 * <h2>What is deliberately NOT a state here</h2>
 *
 * <ul>
 * <li><b>The pressure conditions</b> — {@code MemoryPressure}, {@code DiskPressure},
 * {@code PIDPressure}, {@code NetworkUnavailable}. They are real and they are already on
 * screen: the Nodes list has a Conditions column listing every condition that is True. A
 * node under disk pressure is still {@code Ready}, and both kubelet and kubectl say so,
 * so folding pressure into the state would invent a status kubectl never prints
 * <i>and</i> split the Ready count into a vocabulary no other tool shares. The signal
 * belongs in a column, not in the state.
 * <li><b>A "Cordoned" state of its own.</b> Cordoning does not replace readiness — a
 * cordoned node is still either ready or not, and a card that said {@code Cordoned}
 * instead of {@code Ready,SchedulingDisabled} would be hiding which.
 * <li><b>Anything about a Namespace beyond its phase.</b> The API has exactly two —
 * {@code Active} and {@code Terminating} — and nothing else about a Namespace object is a
 * health claim. "Empty", "no quota" or "no limit range" would be judgements invented here
 * about objects that are not in the Namespace at all; GH#248 is this repo's precedent for
 * refusing that, and an invented state is worse than an absent one because it is
 * countable, colourable and wrong.
 * </ul>
 *
 * <p>
 * A value the object carries is not an invention, though: an unrecognised namespace phase
 * is reported <i>as that phase</i> rather than collapsed into "Unknown", because the
 * cluster said it and swallowing it would be the one reading nobody could act on.
 */
public final class ClusterObjectHealth {

	private static final String NODE = "Node";

	private static final String NAMESPACE = "Namespace";

	/** Kubectl's suffix for a node whose {@code spec.unschedulable} is set. */
	private static final String CORDONED = ",SchedulingDisabled";

	private ClusterObjectHealth() {
	}

	/** The kinds this judges. Note that Event is not one of them — see the class docs. */
	public static boolean supports(String kind) {
		return NODE.equals(kind) || NAMESPACE.equals(kind);
	}

	public static WorkloadHealth.Verdict verdict(String kind, GenericKubernetesResource o) {
		return NODE.equals(kind) ? node(o) : namespace(o);
	}

	/**
	 * A node's state: the Ready condition, plus the cordon suffix.
	 *
	 * <p>
	 * {@code Ready=Unknown} — the kubelet stopped posting status — reports as
	 * {@code NotReady} rather than as a third word, matching both kubectl and the list's
	 * own Status column; the condition's reason ("NodeStatusUnknown") carries the
	 * distinction into the named-offenders line, which is where a per-object detail
	 * belongs. A node with no Ready condition at all is {@code Unknown} and amber: we did
	 * not learn that it is broken, so colouring it red would be a claim, and counting it
	 * as Ready would be a worse one.
	 */
	private static WorkloadHealth.Verdict node(GenericKubernetesResource o) {
		boolean cordoned = Boolean.TRUE.equals(WorkloadHealth.get(o, "spec", "unschedulable"));
		Map<String, Object> ready = condition(o, "Ready");
		if (ready == null) {
			return WorkloadHealth.Verdict.attentionSoft(withCordon("Unknown", cordoned), "no Ready condition reported");
		}
		if ("True".equals(ready.get("status"))) {
			// Cordoned is an operator's own doing, like a suspended CronJob: worth
			// separating out, not worth painting the same red as a broken node.
			return cordoned ? WorkloadHealth.Verdict.suspended("Ready" + CORDONED, "cordoned")
					: WorkloadHealth.Verdict.ok("Ready");
		}
		return WorkloadHealth.Verdict.attention(withCordon("NotReady", cordoned), notReadyReason(ready));
	}

	/**
	 * Why a node is not ready, from the condition itself — "KubeletNotReady",
	 * "NodeStatusUnknown". The reason field is what {@code kubectl describe node} leads
	 * with, and it names the fix in a way "not ready" never can.
	 */
	private static String notReadyReason(Map<String, Object> ready) {
		String reason = str(ready.get("reason"));
		return reason.isEmpty() ? ("Ready=" + str(ready.get("status"))) : reason;
	}

	private static String withCordon(String label, boolean cordoned) {
		return cordoned ? (label + CORDONED) : label;
	}

	/**
	 * A namespace is {@code Active} or {@code Terminating}; there is no third phase in
	 * the API.
	 *
	 * <p>
	 * Terminating is amber rather than red because it is usually a delete in progress
	 * rather than a fault — and it is not silent either, because the one that never
	 * finishes (a finalizer nothing will satisfy) is exactly the case this state exists
	 * to surface.
	 */
	private static WorkloadHealth.Verdict namespace(GenericKubernetesResource o) {
		String phase = str(WorkloadHealth.get(o, "status", "phase"));
		if ("Active".equals(phase)) {
			return WorkloadHealth.Verdict.ok("Active");
		}
		if (phase.isEmpty()) {
			return WorkloadHealth.Verdict.attentionSoft("Unknown", "no phase reported");
		}
		return WorkloadHealth.Verdict.attentionSoft(phase, phase.toLowerCase(Locale.ROOT));
	}

	/** The named condition, or {@code null} when the object does not report it. */
	private static Map<String, Object> condition(GenericKubernetesResource o, String type) {
		for (Map<String, Object> condition : conditions(o)) {
			if (type.equals(condition.get("type"))) {
				return condition;
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> conditions(GenericKubernetesResource o) {
		Object raw = WorkloadHealth.get(o, "status", "conditions");
		return (raw instanceof List<?> l) ? (List<Map<String, Object>>) l : List.of();
	}

	private static String str(Object v) {
		return (v != null) ? String.valueOf(v) : "";
	}

}

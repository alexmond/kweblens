package org.alexmond.kweblens.health;

import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

/**
 * Whether a workload object is healthy, and — when it is not — <b>why</b>.
 *
 * <p>
 * This lives server-side rather than in the SPA on purpose. Health is a property of the
 * object, not of a particular UI, so computing it here means one implementation serves
 * the dashboard, a future TUI and the agent tool surface. It also lets the server send a
 * small summary instead of every object: the browser previously fetched seven whole
 * collections to produce seven numbers.
 *
 * <p>
 * The {@code reason} is the point. "3 unhealthy" sends someone hunting; "web-7d9f — 2/3
 * ready" and "api-x — CrashLoopBackOff" are answers.
 */
public final class WorkloadHealth {

	private WorkloadHealth() {
	}

	/**
	 * Kinds this knows how to judge; anything else is reported OK rather than guessed at.
	 */
	public static boolean supports(String kind) {
		return List.of("Pod", "Deployment", "StatefulSet", "ReplicaSet", "DaemonSet", "Job", "CronJob").contains(kind);
	}

	public static Verdict verdict(String kind, GenericKubernetesResource o) {
		return switch (kind) {
			case "Pod" -> pod(o);
			case "Deployment", "StatefulSet", "ReplicaSet" -> replicas(o);
			case "DaemonSet" -> daemonSet(o);
			case "Job" -> job(o);
			case "CronJob" -> cronJob(o);
			default -> Verdict.ok("OK");
		};
	}

	/**
	 * A pod's reason comes from the container that is actually stuck, not from the phase
	 * alone: "Pending" and "Running" are far less useful than "CrashLoopBackOff" or
	 * "ImagePullBackOff", which name the fix.
	 */
	private static Verdict pod(GenericKubernetesResource o) {
		String phase = str(get(o, "status", "phase"));
		if ("Running".equals(phase)) {
			return Verdict.ok("Running");
		}
		// A finished pod is not healthy-and-running; grouping it under Running would
		// overstate how much is actually serving.
		if ("Succeeded".equals(phase)) {
			return Verdict.idle("Completed");
		}
		String waiting = firstWaitingReason(o);
		if (waiting != null) {
			// The waiting reason IS the state name — CrashLoopBackOff and
			// ImagePullBackOff
			// are what someone scans a card for.
			return Verdict.attention(waiting, waiting);
		}
		String label = phase.isEmpty() ? "not running" : phase;
		// Pending with no failing container is scheduling, not breakage.
		return "Pending".equals(label) ? Verdict.attentionSoft(label, label) : Verdict.attention(label, label);
	}

	/**
	 * The first waiting reason across init and app containers — init first, since it
	 * blocks.
	 */
	private static String firstWaitingReason(GenericKubernetesResource o) {
		for (String key : List.of("initContainerStatuses", "containerStatuses")) {
			for (Map<String, Object> status : list(get(o, "status", key))) {
				Object waiting = nested(status.get("state"), "waiting");
				String reason = (waiting instanceof Map<?, ?> w) ? str(w.get("reason")) : "";
				// "PodInitializing" and "ContainerCreating" are normal transient states,
				// not
				// problems — reporting them would make every starting pod look broken.
				if (!reason.isEmpty() && !"PodInitializing".equals(reason) && !"ContainerCreating".equals(reason)) {
					return reason;
				}
			}
		}
		return null;
	}

	/**
	 * Replica workloads. Scaled to zero is OK on purpose: intentionally scaled down is
	 * not failing, and flagging it would light up every deliberately idle workload.
	 */
	private static Verdict replicas(GenericKubernetesResource o) {
		int desired = num(get(o, "spec", "replicas"));
		int ready = num(get(o, "status", "readyReplicas"));
		if (desired == 0) {
			return Verdict.idle("Idle");
		}
		return (ready == desired) ? Verdict.ok("Healthy")
				: Verdict.attention("Unavailable", ready + "/" + desired + " ready");
	}

	private static Verdict daemonSet(GenericKubernetesResource o) {
		int desired = num(get(o, "status", "desiredNumberScheduled"));
		int ready = num(get(o, "status", "numberReady"));
		if (desired == 0) {
			return Verdict.idle("Idle");
		}
		return (ready == desired) ? Verdict.ok("Ready")
				: Verdict.attention("Unavailable", ready + "/" + desired + " ready");
	}

	/**
	 * A Job needs attention when it has FAILED — not when it merely has not finished.
	 *
	 * <p>
	 * The predicate this replaces was "succeeded > 0", which is false for the entire
	 * duration of a running Job, so every in-flight Job was reported unhealthy. False
	 * alarms train people to ignore the signal, which is worse than having none.
	 */
	private static Verdict job(GenericKubernetesResource o) {
		if (conditionTrue(o, "Failed")) {
			return Verdict.attention("Failed", "failed");
		}
		if (conditionTrue(o, "Complete")) {
			return Verdict.idle("Succeeded");
		}
		return Verdict.ok("Active");
	}

	/**
	 * A CronJob is suspended or OK.
	 *
	 * <p>
	 * Deliberately no "last run failed" heuristic: a CronJob's status carries only
	 * lastScheduleTime / lastSuccessfulTime / active, so inferring failure means
	 * comparing timestamps against a parsed cron expression — fragile, and a wrong guess
	 * reintroduces exactly the false alarms this design removes. A failed run surfaces as
	 * a failed Job, which {@link #job} reports, so the information comes from the object
	 * that knows it.
	 */
	private static Verdict cronJob(GenericKubernetesResource o) {
		return Boolean.TRUE.equals(get(o, "spec", "suspend")) ? Verdict.suspended("Suspended", "suspended")
				: Verdict.ok("Scheduled");
	}

	private static boolean conditionTrue(GenericKubernetesResource o, String type) {
		for (Map<String, Object> condition : list(get(o, "status", "conditions"))) {
			if (type.equals(condition.get("type")) && "True".equals(condition.get("status"))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Package-private rather than private: {@link ClusterObjectHealth} reads the same
	 * untyped object graph, and one reader is one set of null rules.
	 */
	static Object get(GenericKubernetesResource o, String... path) {
		return o.get((Object[]) path);
	}

	/**
	 * The object's namespace, or null for a cluster-scoped one. Package-private for the
	 * same reason as {@link #get}: every check names its offenders, and one reader is one
	 * set of null rules.
	 */
	static String namespaceOf(GenericKubernetesResource o) {
		return (o.getMetadata() != null) ? o.getMetadata().getNamespace() : null;
	}

	/** The object's name, or "" when it somehow has no metadata. */
	static String nameOf(GenericKubernetesResource o) {
		return (o.getMetadata() != null) ? o.getMetadata().getName() : "";
	}

	private static Object nested(Object v, String key) {
		return (v instanceof Map<?, ?> map) ? map.get(key) : null;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object v) {
		return (v instanceof List<?> l) ? (List<Map<String, Object>>) l : List.of();
	}

	private static String str(Object v) {
		return (v != null) ? String.valueOf(v) : "";
	}

	private static int num(Object v) {
		return (v instanceof Number n) ? n.intValue() : 0;
	}

	// Nested types last (Checkstyle InnerTypeLast).

	/**
	 * Three states, not a boolean. {@code SUSPENDED} exists because a suspended CronJob
	 * is a deliberate operator choice, not a fault — colouring it red beside genuine
	 * failures is how a health signal earns its way into being ignored, which then makes
	 * it useless when something is actually broken.
	 */
	public enum State {

		OK, ATTENTION, SUSPENDED,
		/**
		 * Neither healthy nor broken — a workload scaled to zero, a pod that has
		 * finished. Separate from OK so a card does not report "everything is fine" about
		 * objects that are simply not doing anything.
		 */
		IDLE

	}

	/**
	 * The state of an object, its name in the kind's own vocabulary, and a human reason
	 * when it needs attention.
	 *
	 * @param label what to call this state on a card — "Running", "Unavailable", "Idle".
	 * Distinct from {@code reason}, which is the specific detail for ONE object ("2/3
	 * ready"); a label has to be shared by every object in the same state or it cannot be
	 * counted.
	 */
	public record Verdict(State state, String reason, String label, String tone) {

		static Verdict ok(String label) {
			return new Verdict(State.OK, null, label, StateCount.OK);
		}

		/** Not a fault: scaled to zero, finished. Counted apart from healthy. */
		static Verdict idle(String label) {
			return new Verdict(State.IDLE, null, label, StateCount.IDLE);
		}

		static Verdict attention(String label, String reason) {
			return new Verdict(State.ATTENTION, reason, label, StateCount.ERR);
		}

		/**
		 * Needs attention, but shown amber rather than red.
		 *
		 * <p>
		 * Severity and colour are not the same axis. A Pending pod is worth surfacing —
		 * it is not serving — but most Pending pods are simply being scheduled, and
		 * painting them the same red as a CrashLoopBackOff on every card would make
		 * normal startup look like failure and dull the colour that means something is
		 * broken.
		 */
		static Verdict attentionSoft(String label, String reason) {
			return new Verdict(State.ATTENTION, reason, label, StateCount.WARN);
		}

		static Verdict suspended(String label, String reason) {
			return new Verdict(State.SUSPENDED, reason, label, StateCount.WARN);
		}

	}

}

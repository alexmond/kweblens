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
			default -> Verdict.ok();
		};
	}

	/**
	 * A pod's reason comes from the container that is actually stuck, not from the phase
	 * alone: "Pending" and "Running" are far less useful than "CrashLoopBackOff" or
	 * "ImagePullBackOff", which name the fix.
	 */
	private static Verdict pod(GenericKubernetesResource o) {
		String phase = str(get(o, "status", "phase"));
		if ("Running".equals(phase) || "Succeeded".equals(phase)) {
			return Verdict.ok();
		}
		String waiting = firstWaitingReason(o);
		if (waiting != null) {
			return Verdict.attention(waiting);
		}
		return Verdict.attention(phase.isEmpty() ? "not running" : phase);
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
		return (ready == desired) ? Verdict.ok() : Verdict.attention(ready + "/" + desired + " ready");
	}

	private static Verdict daemonSet(GenericKubernetesResource o) {
		int desired = num(get(o, "status", "desiredNumberScheduled"));
		int ready = num(get(o, "status", "numberReady"));
		return (ready == desired) ? Verdict.ok() : Verdict.attention(ready + "/" + desired + " ready");
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
			return Verdict.attention("failed");
		}
		return Verdict.ok();
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
		return Boolean.TRUE.equals(get(o, "spec", "suspend")) ? Verdict.suspended("suspended") : Verdict.ok();
	}

	private static boolean conditionTrue(GenericKubernetesResource o, String type) {
		for (Map<String, Object> condition : list(get(o, "status", "conditions"))) {
			if (type.equals(condition.get("type")) && "True".equals(condition.get("status"))) {
				return true;
			}
		}
		return false;
	}

	private static Object get(GenericKubernetesResource o, String... path) {
		return o.get((Object[]) path);
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

		OK, ATTENTION, SUSPENDED

	}

	/** The state of an object, plus a human reason when it needs attention. */
	public record Verdict(State state, String reason) {

		static Verdict ok() {
			return new Verdict(State.OK, null);
		}

		static Verdict attention(String reason) {
			return new Verdict(State.ATTENTION, reason);
		}

		static Verdict suspended(String reason) {
			return new Verdict(State.SUSPENDED, reason);
		}

	}

}

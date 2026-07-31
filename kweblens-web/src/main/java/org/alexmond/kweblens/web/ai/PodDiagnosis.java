package org.alexmond.kweblens.web.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

/**
 * Why one pod is unhealthy, read from the places the evidence actually lives.
 *
 * <p>
 * Built from {@code docs/design/failure-taxonomy.md}, whose two central findings this
 * class exists to act on.
 *
 * <p>
 * <b>A pod's current state names the symptom; its previous state holds the diagnosis.</b>
 * A crashlooping container's {@code state.waiting.message} is "back-off 5m0s restarting
 * failed container" — true, and useless. The exit code is in
 * {@code lastState.terminated}, and 137 versus 1 is the difference between "the kernel
 * killed it for memory" and "the application failed". So every crashloop finding carries
 * the exit code.
 *
 * <p>
 * <b>An unschedulable pod's reason is on the object, not in an event.</b> The scheduler
 * writes its verdict into {@code status.conditions[type=PodScheduled].message} — "0/4
 * nodes are available: 4 node(s) didn't match Pod's node affinity/selector" — which is
 * the whole answer. Reporting {@code phase=Pending} and telling someone to go and look is
 * strictly less than what was already known.
 *
 * <p>
 * Pure and static so it can be tested against real object YAML without a cluster.
 */
final class PodDiagnosis {

	/** Waiting reasons that mean the container is not going to start on its own. */
	private static final Set<String> CRITICAL_WAITING = Set.of("CrashLoopBackOff", "ImagePullBackOff", "ErrImagePull",
			"CreateContainerConfigError", "CreateContainerError", "InvalidImageName");

	/**
	 * Waiting reasons that are normal during startup. Reporting these would flag every
	 * pod that is merely young, which is the false alarm that gets a diagnosis screen
	 * ignored.
	 */
	private static final Set<String> TRANSIENT_WAITING = Set.of("PodInitializing", "ContainerCreating");

	/**
	 * 128 + SIGKILL(9). The kernel's OOM killer and a `docker kill` look the same here.
	 */
	private static final int EXIT_SIGKILL = 137;

	private PodDiagnosis() {
	}

	/**
	 * Findings for one pod, most specific first. Empty when the pod is healthy.
	 *
	 * <p>
	 * A pod produces at most ONE generic finding: if a container explains the problem,
	 * the "this pod is not running" line is left off, because three findings for one
	 * broken pod is how a list stops being read.
	 */
	static List<Finding> forPod(GenericKubernetesResource pod) {
		Map<String, Object> status = map(pod.getAdditionalProperties().get("status"));
		String phase = str(status.get("phase"));
		if ("Running".equals(phase) || "Succeeded".equals(phase)) {
			return containerFindings(name(pod), status);
		}
		List<Finding> findings = containerFindings(name(pod), status);
		if (!findings.isEmpty()) {
			return findings;
		}
		Finding scheduling = schedulingFinding(name(pod), status, phase);
		if (scheduling != null) {
			findings.add(scheduling);
			return findings;
		}
		// A pod whose containers are merely being created is starting normally, so it is
		// reported as INFO rather than as a warning — but it IS reported. Dropping it
		// would
		// hide a pod wedged in ContainerCreating for twenty minutes, which is a volume
		// that
		// will not mount wearing a normal-looking state.
		boolean starting = starting(status);
		findings.add(new Finding(starting ? "info" : "warning", starting ? "Pod still starting" : "Pod not running",
				"Pod/" + name(pod), "phase=" + phase,
				starting ? "Normal for a new pod. If it persists, check the pod's events for a volume or image problem."
						: "Check the pod's events and container states.",
				"validator"));
		return findings;
	}

	/**
	 * True when every container is in a normal startup state rather than a failed one.
	 */
	private static boolean starting(Map<String, Object> status) {
		for (String key : List.of("initContainerStatuses", "containerStatuses")) {
			for (Map<String, Object> container : list(status.get(key))) {
				String reason = str(map(map(container.get("state")).get("waiting")).get("reason"));
				if (reason != null && TRANSIENT_WAITING.contains(reason)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The scheduler's own verdict, when it could not place the pod.
	 *
	 * <p>
	 * This message is the diagnosis: it names how many nodes were considered and why each
	 * was rejected — insufficient CPU, a taint, an unmatched affinity, an unbound claim.
	 */
	private static Finding schedulingFinding(String pod, Map<String, Object> status, String phase) {
		Map<String, Object> unscheduled = list(status.get("conditions")).stream()
			.filter((c) -> "PodScheduled".equals(c.get("type")) && "False".equals(str(c.get("status"))))
			.findFirst()
			.orElse(Map.of());
		if (unscheduled.isEmpty()) {
			return null;
		}
		String reason = str(unscheduled.get("reason"));
		String message = str(unscheduled.get("message"));
		return new Finding("critical", (reason != null) ? reason : "Not scheduled", "Pod/" + pod,
				(message != null) ? message : "phase=" + phase,
				"The scheduler's message says which constraint failed — resolve that, "
						+ "or relax the request, affinity or toleration it names.",
				"validator");
	}

	/** Init containers first: they block everything after them, so they are the cause. */
	private static List<Finding> containerFindings(String pod, Map<String, Object> status) {
		List<Finding> findings = new ArrayList<>();
		findings.addAll(scan(pod, status.get("initContainerStatuses"), true));
		findings.addAll(scan(pod, status.get("containerStatuses"), false));
		return findings;
	}

	private static List<Finding> scan(String pod, Object statuses, boolean init) {
		List<Finding> findings = new ArrayList<>();
		for (Map<String, Object> container : list(statuses)) {
			Finding finding = forContainer(pod, container, init);
			if (finding != null) {
				findings.add(finding);
			}
		}
		return findings;
	}

	private static Finding forContainer(String pod, Map<String, Object> container, boolean init) {
		String containerName = str(container.get("name"));
		Map<String, Object> waiting = map(map(container.get("state")).get("waiting"));
		Map<String, Object> lastTerminated = map(map(container.get("lastState")).get("terminated"));
		String reason = str(waiting.get("reason"));
		if (reason != null && !TRANSIENT_WAITING.contains(reason) && CRITICAL_WAITING.contains(reason)) {
			return waitingFinding(pod, containerName, init, reason, waiting, lastTerminated);
		}
		// A terminated init container is a failure the app containers never explain: they
		// sit in PodInitializing, which reads like "starting" rather than "blocked".
		Map<String, Object> terminated = map(map(container.get("state")).get("terminated"));
		int exit = num(terminated.get("exitCode"), 0);
		if (init && !terminated.isEmpty() && exit != 0) {
			return new Finding("critical", "Init container failed", where(pod, containerName, true),
					"exit code " + exit + describeReason(terminated) + " — the app containers stay in PodInitializing",
					"Read this init container's log (including the previous run) to see why it exited.", "validator");
		}
		return null;
	}

	private static Finding waitingFinding(String pod, String container, boolean init, String reason,
			Map<String, Object> waiting, Map<String, Object> lastTerminated) {
		int exit = num(lastTerminated.get("exitCode"), 0);
		String lastReason = str(lastTerminated.get("reason"));
		boolean oom = "OOMKilled".equals(lastReason);
		if (oom || (exit == EXIT_SIGKILL && "CrashLoopBackOff".equals(reason))) {
			// Worth its own title: the fix is a memory limit or a leak, not the
			// application
			// error a bare CrashLoopBackOff sends people looking for.
			return new Finding("critical", oom ? "OOMKilled" : "CrashLoopBackOff — killed (exit 137)",
					where(pod, container, init),
					"the container was killed rather than exiting on its own"
							+ ((exit != 0) ? " (exit code " + exit + ")" : ""),
					"Compare the container's memory usage against its limit; raise the limit or fix the leak.",
					"validator");
		}
		String detail = (exit != 0) ? "last exit code " + exit + describeReason(lastTerminated)
				: str(waiting.get("message"));
		return new Finding("critical", reason, where(pod, container, init), detail, suggestFor(reason), "validator");
	}

	private static String describeReason(Map<String, Object> terminated) {
		String reason = str(terminated.get("reason"));
		return (reason != null && !reason.isBlank()) ? " (" + reason + ")" : "";
	}

	private static String where(String pod, String container, boolean init) {
		if (container == null || container.isBlank()) {
			return "Pod/" + pod;
		}
		return "Pod/" + pod + (init ? " init container " : " container ") + container;
	}

	private static String suggestFor(String reason) {
		return switch (reason) {
			case "CrashLoopBackOff" ->
				"Read the PREVIOUS run's log — the current one starts at the new process, so the "
						+ "output that explains the crash is only in the terminated instance's.";
			case "ImagePullBackOff", "ErrImagePull", "InvalidImageName" ->
				"Check the image name and tag, and whether this namespace has pull credentials.";
			case "CreateContainerConfigError" ->
				"A referenced ConfigMap or Secret is missing, or a key in it is. The waiting message names which.";
			default -> "Describe the pod and inspect its container state.";
		};
	}

	private static String name(GenericKubernetesResource pod) {
		return (pod.getMetadata() != null) ? pod.getMetadata().getName() : "?";
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return (value instanceof Map) ? (Map<String, Object>) value : Map.of();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object value) {
		return (value instanceof List<?> l) ? (List<Map<String, Object>>) l : List.of();
	}

	private static String str(Object value) {
		return (value != null) ? value.toString() : null;
	}

	private static int num(Object value, int fallback) {
		return (value instanceof Number n) ? n.intValue() : fallback;
	}

}

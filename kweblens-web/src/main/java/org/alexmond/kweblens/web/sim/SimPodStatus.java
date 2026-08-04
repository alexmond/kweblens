package org.alexmond.kweblens.web.sim;

import java.util.ArrayList;
import java.util.List;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.PodConditionBuilder;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;

/**
 * A pod's {@code status} — the half of a pod the dashboard actually renders, and the half
 * the old seeder reduced to {@code phase: Running}.
 *
 * <p>
 * Everything the UI reads about a pod's health lives here: the container statuses whose
 * {@code state.waiting.reason} <em>is</em> the state name the overview cards show
 * ({@code WorkloadHealth.firstWaitingReason}), the restart counts, the conditions, the
 * IPs that make a pod look scheduled. Producing them is what makes CrashLoopBackOff,
 * ImagePullBackOff, OOMKilled and Evicted reachable in a cluster-free run.
 *
 * <p>
 * The failure shapes are the real ones, not approximations: a crash-looping container is
 * <em>waiting</em> with a back-off message while its <em>last</em> state is a non-zero
 * termination, an OOM-killed one is the same but with exit code 137 and reason OOMKilled,
 * and an unschedulable pod has no container statuses at all — only a {@code PodScheduled=
 * False} condition. Getting that wrong would make the simulator disagree with the health
 * checks about what a broken pod looks like, which is worse than having no broken pods.
 */
final class SimPodStatus {

	private static final String TRUE = "True";

	private static final String FALSE = "False";

	private SimPodStatus() {
	}

	static PodStatus status(int index, String state, List<Container> containers) {
		if (SimPods.PENDING.equals(state)) {
			return unschedulable(index);
		}
		if (SimPods.FAILED.equals(state)) {
			return evicted(index);
		}
		PodStatusBuilder status = new PodStatusBuilder().withPhase(phase(state))
			.withHostIP(SimPods.hostIp(index))
			.withPodIP(SimPods.podIp(index))
			.withStartTime(SimMeta.created("start", index))
			.withQosClass("Burstable")
			.withConditions(conditions(index, state))
			.withContainerStatuses(containerStatuses(index, state, containers));
		status.addNewPodIP().withIp(SimPods.podIp(index)).endPodIP();
		return status.build();
	}

	private static String phase(String state) {
		if (SimPods.SUCCEEDED.equals(state)) {
			return SimPods.SUCCEEDED;
		}
		// A pod that cannot pull its image is Pending; one that keeps crashing is
		// Running. Both are wrong in the UI if the phase is taken from the failure name.
		return SimPods.IMAGE_PULL.equals(state) ? SimPods.PENDING : SimPods.RUNNING;
	}

	/**
	 * Pending with nothing scheduled: no node, no IP, no container statuses, and a
	 * PodScheduled=False condition carrying the scheduler's own explanation — the message
	 * an operator reads to find out which constraint failed.
	 */
	private static PodStatus unschedulable(int index) {
		PodCondition scheduled = new PodConditionBuilder().withType("PodScheduled")
			.withStatus(FALSE)
			.withReason("Unschedulable")
			.withMessage("0/4 nodes are available: 1 node(s) had untolerated taint "
					+ "{node-role.kubernetes.io/control-plane: }, 3 Insufficient cpu. "
					+ "preemption: 0/4 nodes are available: 4 No preemption victims found for incoming pod.")
			.withLastTransitionTime(SimMeta.created("cond", index))
			.build();
		return new PodStatusBuilder().withPhase(SimPods.PENDING)
			.withQosClass("Burstable")
			.withConditions(scheduled)
			.build();
	}

	/**
	 * An evicted pod: Failed, with the kubelet's reason and message and no containers.
	 */
	private static PodStatus evicted(int index) {
		return new PodStatusBuilder().withPhase(SimPods.FAILED)
			.withReason("Evicted")
			.withMessage("The node was low on resource: ephemeral-storage. "
					+ "Threshold quantity: 2Gi, available: 1.6Gi. Container app was using 4.1Gi.")
			.withStartTime(SimMeta.created("start", index))
			.withQosClass("Burstable")
			.build();
	}

	private static List<PodCondition> conditions(int index, String state) {
		boolean ready = SimPods.RUNNING.equals(state);
		String time = SimMeta.created("cond", index);
		List<PodCondition> conditions = new ArrayList<>();
		conditions.add(condition("PodReadyToStartContainers", TRUE, null, time));
		conditions.add(condition("Initialized", TRUE, null, time));
		conditions.add(condition("Ready", ready ? TRUE : FALSE, ready ? null : "ContainersNotReady", time));
		conditions.add(condition("ContainersReady", ready ? TRUE : FALSE, ready ? null : "ContainersNotReady", time));
		conditions.add(condition("PodScheduled", TRUE, null, time));
		return conditions;
	}

	private static PodCondition condition(String type, String status, String reason, String time) {
		PodConditionBuilder condition = new PodConditionBuilder().withType(type)
			.withStatus(status)
			.withLastTransitionTime(time)
			.withLastProbeTime((String) null);
		if (reason != null) {
			condition.withReason(reason).withMessage("containers with unready status: [app]");
		}
		return condition.build();
	}

	private static List<ContainerStatus> containerStatuses(int index, String state, List<Container> containers) {
		List<ContainerStatus> statuses = new ArrayList<>();
		for (Container container : containers) {
			// Only the first container fails: a pod where every container is broken in
			// the same way is not a shape real clusters produce, and it hides whether the
			// UI names the right one.
			boolean failing = statuses.isEmpty() && !SimPods.RUNNING.equals(state);
			statuses.add(containerStatus(index, container.getName(), failing ? state : SimPods.RUNNING));
		}
		return statuses;
	}

	private static ContainerStatus containerStatus(int index, String name, String state) {
		SimRandom random = new SimRandom("cstatus", index);
		ContainerStatusBuilder status = new ContainerStatusBuilder().withName(name)
			.withImage("registry.example.test/sim/app:1.4.2")
			.withImageID("registry.example.test/sim/app@sha256:" + random.hex(64))
			.withContainerID("containerd://" + random.hex(64))
			.withStarted(true)
			.withReady(SimPods.RUNNING.equals(state))
			.withRestartCount(restartCount(random, state));
		return SimPodStates.apply(status, state, index, random).build();
	}

	private static int restartCount(SimRandom random, String state) {
		if (SimPods.CRASH_LOOP.equals(state) || SimPods.OOM_KILLED.equals(state)) {
			return random.between(12, 940);
		}
		return random.between(0, 3);
	}

	/**
	 * The paths the kubelet owns. Proportional to the container count, exactly as the
	 * real entry is — which is why a three-container pod's managedFields are three times
	 * a one-container pod's.
	 */
	static List<String> statusPaths(int index, List<Container> containers) {
		List<String> paths = new ArrayList<>(List.of("status|conditions|k:{\"type\":\"ContainersReady\"}|.",
				"status|conditions|k:{\"type\":\"ContainersReady\"}|lastProbeTime",
				"status|conditions|k:{\"type\":\"ContainersReady\"}|lastTransitionTime",
				"status|conditions|k:{\"type\":\"ContainersReady\"}|status",
				"status|conditions|k:{\"type\":\"ContainersReady\"}|type",
				"status|conditions|k:{\"type\":\"Initialized\"}|.",
				"status|conditions|k:{\"type\":\"Initialized\"}|lastTransitionTime",
				"status|conditions|k:{\"type\":\"Ready\"}|.", "status|conditions|k:{\"type\":\"Ready\"}|status",
				"status|containerStatuses", "status|hostIP", "status|hostIPs", "status|phase", "status|podIP",
				"status|podIPs|.", "status|podIPs|k:{\"ip\":\"" + SimPods.podIp(index) + "\"}|.",
				"status|podIPs|k:{\"ip\":\"" + SimPods.podIp(index) + "\"}|ip", "status|qosClass", "status|startTime"));
		containers.forEach((c) -> paths.add("status|containerStatuses|k:{\"name\":\"" + c.getName() + "\"}"));
		return paths;
	}

}

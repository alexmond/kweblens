package org.alexmond.kweblens.web.sim;

import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;

/**
 * One container status per failure mode, written the way the kubelet writes it.
 *
 * <p>
 * The distinction that matters is {@code state} versus {@code lastState}: a crash-looping
 * container is <em>currently waiting</em> with a back-off reason, and what actually
 * happened to it is in {@code lastState.terminated}. The health check reads the first and
 * the drawer shows the second, so a simulator that put "OOMKilled" in
 * {@code state.waiting} would light up the card with a reason no kubelet ever writes
 * there — a fixture that teaches the reader something false is worse than no fixture.
 */
final class SimPodStates {

	private SimPodStates() {
	}

	static ContainerStatusBuilder apply(ContainerStatusBuilder status, String state, int index, SimRandom random) {
		if (SimPods.CRASH_LOOP.equals(state)) {
			return crashLoop(status, index, "Error", 1);
		}
		if (SimPods.OOM_KILLED.equals(state)) {
			// Exit 137 is SIGKILL: the OOM killer, and the number people search for.
			return crashLoop(status, index, SimPods.OOM_KILLED, 137);
		}
		if (SimPods.IMAGE_PULL.equals(state)) {
			return imagePull(status, random);
		}
		if (SimPods.SUCCEEDED.equals(state)) {
			return completed(status, index);
		}
		return status.withNewState()
			.withNewRunning()
			.withStartedAt(SimMeta.created("start", index))
			.endRunning()
			.endState();
	}

	private static ContainerStatusBuilder crashLoop(ContainerStatusBuilder status, int index, String reason,
			int exitCode) {
		status.withNewState()
			.withNewWaiting()
			.withReason(SimPods.CRASH_LOOP)
			.withMessage("back-off 5m0s restarting failed container=app pod=sim-pod-" + index + '_' + "sim-ns")
			.endWaiting()
			.endState();
		return status.withNewLastState()
			.withNewTerminated()
			.withExitCode(exitCode)
			.withReason(reason)
			.withStartedAt(SimMeta.created("start", index))
			.withFinishedAt(SimMeta.created("finish", index))
			.withContainerID("containerd://" + new SimRandom("last", index).hex(64))
			.endTerminated()
			.endLastState();
	}

	private static ContainerStatusBuilder imagePull(ContainerStatusBuilder status, SimRandom random) {
		return status.withStarted(false)
			.withNewState()
			.withNewWaiting()
			.withReason(SimPods.IMAGE_PULL)
			.withMessage("Back-off pulling image \"registry.example.test/sim/app:1.4.2\": "
					+ "ErrImagePull: failed to pull and unpack image: failed to resolve reference: "
					+ "unexpected status from HEAD request: 401 Unauthorized (attempt " + random.between(2, 40) + ')')
			.endWaiting()
			.endState();
	}

	private static ContainerStatusBuilder completed(ContainerStatusBuilder status, int index) {
		return status.withNewState()
			.withNewTerminated()
			.withExitCode(0)
			.withReason("Completed")
			.withStartedAt(SimMeta.created("start", index))
			.withFinishedAt(SimMeta.created("finish", index))
			.withContainerID("containerd://" + new SimRandom("done", index).hex(64))
			.endTerminated()
			.endState();
	}

}

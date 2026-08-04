package org.alexmond.kweblens.web.sim;

import java.util.List;

import io.fabric8.kubernetes.api.model.apps.DeploymentCondition;
import io.fabric8.kubernetes.api.model.apps.DeploymentConditionBuilder;

/**
 * A Deployment's status conditions, including the message a stuck rollout actually
 * carries.
 *
 * <p>
 * The messages are the reason this is not inlined: {@code ProgressDeadlineExceeded} with
 * "has timed out progressing" is the string an operator greps for, it is several hundred
 * bytes of every unhealthy deployment's payload, and a fixture that says only
 * {@code status: False} cannot be used to check that the UI surfaces it.
 */
final class SimConditions {

	private SimConditions() {
	}

	static List<DeploymentCondition> deployment(int index, boolean healthy) {
		String time = SimMeta.created("cond", index);
		DeploymentCondition available = new DeploymentConditionBuilder().withType("Available")
			.withStatus(healthy ? "True" : "False")
			.withReason(healthy ? "MinimumReplicasAvailable" : "MinimumReplicasUnavailable")
			.withMessage(
					healthy ? "Deployment has minimum availability." : "Deployment does not have minimum availability.")
			.withLastTransitionTime(time)
			.withLastUpdateTime(time)
			.build();
		DeploymentCondition progressing = new DeploymentConditionBuilder().withType("Progressing")
			.withStatus(healthy ? "True" : "False")
			.withReason(healthy ? "NewReplicaSetAvailable" : "ProgressDeadlineExceeded")
			.withMessage(healthy ? "ReplicaSet \"sim-rs-" + index + "\" has successfully progressed."
					: "ReplicaSet \"sim-rs-" + index + "\" has timed out progressing.")
			.withLastTransitionTime(time)
			.withLastUpdateTime(time)
			.build();
		return List.of(available, progressing);
	}

}

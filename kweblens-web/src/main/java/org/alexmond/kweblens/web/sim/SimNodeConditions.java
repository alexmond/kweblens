package org.alexmond.kweblens.web.sim;

import java.util.ArrayList;
import java.util.List;

import io.fabric8.kubernetes.api.model.NodeCondition;
import io.fabric8.kubernetes.api.model.NodeConditionBuilder;

/**
 * The five conditions a kubelet reports, with their real reasons and messages.
 *
 * <p>
 * A NotReady node is not "Ready: False" and nothing else: the kubelet stops posting
 * status, so the reason becomes {@code NodeStatusUnknown} and the message says the
 * controller stopped hearing from it. That distinction is what an operator reads, and it
 * is why this is spelled out rather than flipped from a boolean.
 */
final class SimNodeConditions {

	private static final String TRUE = "True";

	private static final String FALSE = "False";

	private SimNodeConditions() {
	}

	static List<NodeCondition> conditions(int index, boolean ready) {
		String time = SimMeta.created("nodecond", index);
		List<NodeCondition> conditions = new ArrayList<>();
		conditions.add(condition("MemoryPressure", FALSE, "KubeletHasSufficientMemory",
				"kubelet has sufficient memory available", time));
		conditions
			.add(condition("DiskPressure", FALSE, "KubeletHasNoDiskPressure", "kubelet has no disk pressure", time));
		conditions.add(condition("PIDPressure", FALSE, "KubeletHasSufficientPID",
				"kubelet has sufficient PID available", time));
		conditions.add(condition("NetworkUnavailable", FALSE, "FlannelIsUp", "Flannel is running on this node", time));
		if (ready) {
			conditions.add(condition("Ready", TRUE, "KubeletReady", "kubelet is posting ready status", time));
		}
		else {
			conditions
				.add(condition("Ready", "Unknown", "NodeStatusUnknown", "Kubelet stopped posting node status.", time));
		}
		return conditions;
	}

	private static NodeCondition condition(String type, String status, String reason, String message, String time) {
		return new NodeConditionBuilder().withType(type)
			.withStatus(status)
			.withReason(reason)
			.withMessage(message)
			.withLastHeartbeatTime(time)
			.withLastTransitionTime(time)
			.build();
	}

}

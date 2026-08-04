package org.alexmond.kweblens.web.sim;

import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;

/**
 * Events, which the simulator had none of — so the cluster overview's Warnings card
 * always read zero, and {@code .ov-card.danger} (the styling that only appears when it
 * does not) has never been measurable by the contrast checker.
 *
 * <p>
 * Each Warning names a pod that really is in the state the message describes, because
 * {@link SimPods#state} is deterministic and this reads it. An events list full of
 * complaints about healthy pods would be worse than an empty one: the drawer's Events tab
 * filters by involved object, so the two have to agree.
 *
 * <p>
 * The count is <b>capped, not proportional</b> to {@code size}. Kubernetes expires events
 * after an hour, so the live cluster shows 72 of them whether it runs 90 pods or 9 000; a
 * simulator that seeded one per object would have produced a kind that is small in
 * reality and enormous in the rig — the same class of error this whole change is about.
 */
final class SimEvents {

	/** Roughly what an hour's retention leaves on a busy cluster. */
	static final int MAX_EVENTS = 120;

	private SimEvents() {
	}

	/**
	 * Whether this pod index is worth an event — the broken ones, and a few normal ones.
	 */
	static boolean interesting(int index) {
		return !SimPods.RUNNING.equals(SimPods.state(index)) || index % 11 == 0;
	}

	static Event event(int index, String namespace) {
		String state = SimPods.state(index);
		Reason reason = reason(state, index);
		String pod = "sim-pod-" + index;
		ObjectMeta meta = SimMeta.meta("Event", index, pod + ".17f" + new SimRandom("ev", index).hex(12), namespace,
				Map.of(), Map.of());
		meta.setManagedFields(List.of(SimFields.entry("kubelet", "Update", SimMeta.created("mf", index),
				List.of("count", "firstTimestamp", "involvedObject", "lastTimestamp", "message", "reason", "source|.",
						"source|component", "source|host", "type"))));
		return new EventBuilder().withMetadata(meta)
			.withType(reason.type())
			.withReason(reason.reason())
			.withMessage(reason.message())
			.withCount(reason.count())
			.withFirstTimestamp(SimMeta.created("evfirst", index))
			.withLastTimestamp(SimMeta.created("evlast", index))
			.withNewInvolvedObject()
			.withApiVersion("v1")
			.withKind("Pod")
			.withName(pod)
			.withNamespace(namespace)
			.withUid(SimMeta.uid("Pod", index))
			.withResourceVersion(SimMeta.resourceVersion("Pod", index))
			.withFieldPath("spec.containers{app}")
			.endInvolvedObject()
			.withNewSource()
			.withComponent("kubelet")
			.withHost("node-" + (index % 3) + ".sim.example.test")
			.endSource()
			.withReportingComponent("kubelet")
			.withReportingInstance("node-" + (index % 3) + ".sim.example.test")
			.withEventTime(null)
			.build();
	}

	private static Reason reason(String state, int index) {
		int count = new SimRandom("evcount", index).between(1, 400);
		return switch (state) {
			case SimPods.CRASH_LOOP, SimPods.OOM_KILLED -> new Reason("Warning", "BackOff",
					"Back-off restarting failed container app in pod sim-pod-" + index, count);
			case SimPods.IMAGE_PULL -> new Reason("Warning", "Failed",
					"Failed to pull image \"registry.example.test/sim/app:1.4.2\": "
							+ "failed to resolve reference: unexpected status from HEAD request: 401 Unauthorized",
					count);
			case SimPods.PENDING ->
				new Reason("Warning", "FailedScheduling", "0/4 nodes are available: 1 node(s) had untolerated taint "
						+ "{node-role.kubernetes.io/control-plane: }, 3 Insufficient cpu.", count);
			case SimPods.FAILED -> new Reason("Warning", "Evicted",
					"The node was low on resource: ephemeral-storage. Container app was using 4.1Gi.", 1);
			case SimPods.SUCCEEDED -> new Reason("Normal", "Completed", "Job completed", 1);
			default -> new Reason("Normal", "Pulled",
					"Successfully pulled image \"registry.example.test/sim/app:1.4.2\" in 1.204s", 1);
		};
	}

	/** One event's variable part. */
	record Reason(String type, String reason, String message, int count) {
	}

}

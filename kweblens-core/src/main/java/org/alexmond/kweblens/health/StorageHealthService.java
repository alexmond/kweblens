package org.alexmond.kweblens.health;

import java.util.List;

import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * The Storage overview's check: <b>PersistentVolumeClaims that are not bound</b>.
 *
 * <p>
 * An unbound PVC is a pod that will never start — it sits {@code Pending} while the pod
 * that wants it sits {@code Pending} too, and the pod's own status says only "unbound
 * immediate PersistentVolumeClaims", pointing at the claim rather than the cause. The
 * usual causes are a missing or misspelled StorageClass, or a class with no provisioner,
 * so the reason names the class when there is one.
 *
 * <p>
 * <b>What this deliberately does not check: capacity.</b> "This volume is 95% full" is
 * the other question worth asking here, and the Kubernetes API cannot answer it — PVC
 * usage comes from kubelet's stats/summary or a metrics pipeline, not from the object.
 * Rather than infer it from {@code spec.resources.requests}, which is the size asked for
 * and says nothing about consumption, this check stays silent about capacity until
 * kweblens has a metrics source (GH#144). A blank is honest; a made-up number is not.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageHealthService {

	private static final String KIND = "PersistentVolumeClaim";

	/**
	 * {@code WaitForFirstConsumer} classes leave a claim Pending on purpose until a pod
	 * schedules. That is normal, not a fault, so the reason says which it is.
	 */
	private static final String PENDING = "Pending";

	private final ClusterRegistry clusters;

	public List<KindHealth> summarise(String clusterId, String namespace) {
		try {
			var client = this.clusters.require(clusterId).persistentVolumeClaims();
			List<PersistentVolumeClaim> claims = ((namespace != null) ? client.inNamespace(namespace)
					: client.inAnyNamespace())
				.list()
				.getItems();
			Tally tally = new Tally("persistentvolumeclaims", "Persistent Volume Claims", KIND);
			for (PersistentVolumeClaim claim : claims) {
				String phase = phase(claim);
				if ("Bound".equals(phase)) {
					tally.ok();
				}
				else {
					tally.attention(claim, reason(claim, phase));
				}
			}
			return List.of(tally.toKindHealth());
		}
		catch (RuntimeException ex) {
			log.debug("Storage summary failed: {}", ex.getMessage());
			return List.of(KindHealth.failed("persistentvolumeclaims", "Persistent Volume Claims", KIND,
					String.valueOf(ex.getMessage())));
		}
	}

	private String phase(PersistentVolumeClaim claim) {
		return (claim.getStatus() != null) ? claim.getStatus().getPhase() : null;
	}

	/**
	 * The phase, plus the StorageClass when the claim is Pending — because a Pending
	 * claim is nearly always a question about its class, and having the name in the row
	 * saves opening the object to find it.
	 */
	private String reason(PersistentVolumeClaim claim, String phase) {
		String state = (phase != null) ? phase : "unknown";
		String storageClass = (claim.getSpec() != null) ? claim.getSpec().getStorageClassName() : null;
		if (PENDING.equals(state) && storageClass != null && !storageClass.isBlank()) {
			return state + " · " + storageClass;
		}
		return state;
	}

}

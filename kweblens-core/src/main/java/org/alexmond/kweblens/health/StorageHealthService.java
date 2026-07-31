package org.alexmond.kweblens.health;

import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Quantity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.metric.PrometheusMetricService;
import org.alexmond.kweblens.metric.VolumeUsage;

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
 * <b>Capacity is checked only where the reading is about the claim.</b> The Kubernetes
 * API cannot answer "how full is this volume" — that comes from kubelet, via the metrics
 * backend. It is now read from there, but kubelet reports the FILESYSTEM BACKING the
 * volume, so for a provisioner with no per-volume quota every claim on a class reports
 * the same size and the same percentage. Each reading is therefore checked against the
 * claim's requested size before it is used, and a claim whose number describes a shared
 * disk is reported as bound rather than flagged on a figure that is not about it. See
 * {@link org.alexmond.kweblens.metric.VolumeUsage} and
 * {@code docs/design/metrics-sources.md}.
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

	/**
	 * Fullness at which a bound claim starts needing attention. 90% is the conventional
	 * point at which a volume stops being someone's future problem, and it leaves room to
	 * act before writes start failing.
	 */
	private static final double FULL_THRESHOLD = 0.90;

	private final ClusterRegistry clusters;

	private final PrometheusMetricService metrics;

	public List<KindHealth> summarise(String clusterId, String namespace) {
		try {
			var client = this.clusters.require(clusterId).persistentVolumeClaims();
			List<PersistentVolumeClaim> claims = ((namespace != null) ? client.inNamespace(namespace)
					: client.inAnyNamespace())
				.list()
				.getItems();
			// Empty when there is no metrics backend, which simply means no capacity
			// checks
			// — the binding checks below stand on their own.
			Map<String, VolumeUsage> usage = this.metrics.volumeUsage(clusterId);
			Tally tally = new Tally("persistentvolumeclaims", "Persistent Volume Claims", KIND);
			for (PersistentVolumeClaim claim : claims) {
				String phase = phase(claim);
				if (!"Bound".equals(phase)) {
					tally.attention(claim, reason(claim, phase), (phase != null) ? phase : "Unknown", StateCount.ERR);
					continue;
				}
				String full = fullnessReason(claim, usage);
				if (full != null) {
					tally.attention(claim, full, "Nearly full", StateCount.WARN);
				}
				else {
					tally.ok("Bound", StateCount.OK);
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

	/**
	 * "Nearly full", or null when the claim is fine — or when we cannot honestly say.
	 *
	 * <p>
	 * The plausibility check is the whole point. kubelet reports the backing filesystem
	 * for provisioners with no per-volume quota, so on such a cluster every claim on a
	 * class reports the same size and the same percentage. Comparing against the
	 * requested size tells the two cases apart, and where the reading is not about this
	 * claim the claim is simply reported as OK rather than flagged on a number that
	 * describes a shared disk.
	 */
	private String fullnessReason(PersistentVolumeClaim claim, Map<String, VolumeUsage> usage) {
		VolumeUsage volume = usage.get(key(claim));
		if (volume == null || !volume.plausibleFor(requestedBytes(claim))) {
			return null;
		}
		double fraction = volume.usedFraction();
		if (fraction < FULL_THRESHOLD) {
			return null;
		}
		return Math.round(fraction * 100) + "% full";
	}

	private String key(PersistentVolumeClaim claim) {
		return (claim.getMetadata() != null) ? claim.getMetadata().getNamespace() + "/" + claim.getMetadata().getName()
				: "";
	}

	/** The claim's requested storage in bytes, or 0 when it does not state one. */
	private long requestedBytes(PersistentVolumeClaim claim) {
		if (claim.getSpec() == null || claim.getSpec().getResources() == null) {
			return 0;
		}
		var requests = claim.getSpec().getResources().getRequests();
		Quantity requested = (requests != null) ? requests.get("storage") : null;
		if (requested == null) {
			return 0;
		}
		try {
			return Quantity.getAmountInBytes(requested).longValue();
		}
		catch (RuntimeException ex) {
			log.debug("Unparsable storage request on {}: {}", key(claim), ex.getMessage());
			return 0;
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

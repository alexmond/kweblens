package org.alexmond.kweblens.health;

import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Quantity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.metric.PrometheusMetricService;
import org.alexmond.kweblens.metric.VolumeUsage;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.resource.WellKnownKinds;

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
 *
 * <p>
 * <b>Binding is on the object; fullness is not</b> — which is why a claim's state reaches
 * a list row through a {@link StatusContext} rather than through the pure-function half
 * of {@link StatusVocabulary}. One rule ({@link #verdict}), read by the card and by the
 * row.
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

	private static final String BOUND = "Bound";

	/**
	 * Fullness at which a bound claim starts needing attention. 90% is the conventional
	 * point at which a volume stops being someone's future problem, and it leaves room to
	 * act before writes start failing.
	 */
	private static final double FULL_THRESHOLD = 0.90;

	private final ResourceService resources;

	private final PrometheusMetricService metrics;

	/** The kind this judges — the Storage half of {@link StatusVocabulary#covers}. */
	public static boolean supports(String kind) {
		return KIND.equals(kind);
	}

	public List<KindHealth> summarise(String clusterId, String namespace) {
		try {
			List<GenericKubernetesResource> claims = this.resources.listRaw(clusterId,
					WellKnownKinds.PERSISTENT_VOLUME_CLAIMS, namespace);
			// Empty when there is no metrics backend, which simply means no capacity
			// checks
			// — the binding checks below stand on their own.
			Map<String, VolumeUsage> usage = this.metrics.volumeUsage(clusterId);
			Tally tally = new Tally(WellKnownKinds.PERSISTENT_VOLUME_CLAIMS.id(),
					WellKnownKinds.PERSISTENT_VOLUME_CLAIMS.label(), KIND);
			for (GenericKubernetesResource claim : claims) {
				tally.record(verdict(claim, usage), WorkloadHealth.namespaceOf(claim), WorkloadHealth.nameOf(claim));
			}
			return List.of(tally.toKindHealth());
		}
		catch (RuntimeException ex) {
			log.debug("Storage summary failed: {}", ex.getMessage());
			return List.of(KindHealth.failed(WellKnownKinds.PERSISTENT_VOLUME_CLAIMS.id(),
					WellKnownKinds.PERSISTENT_VOLUME_CLAIMS.label(), KIND, String.valueOf(ex.getMessage())));
		}
	}

	/**
	 * A context that judges PersistentVolumeClaim rows, holding the volume usage the
	 * "Nearly full" verdict needs.
	 *
	 * <p>
	 * <b>Cost: one {@code volumeUsage} call per claims list request</b> — backend
	 * discovery plus two instant queries through the API server's service proxy, cluster
	 * wide, exactly what the card costs. Where there is no metrics backend it is the
	 * discovery alone and there is no "Nearly full" state on either side to disagree
	 * about.
	 */
	public StatusContext contextFor(String clusterId, String namespace) {
		Map<String, VolumeUsage> usage = this.metrics.volumeUsage(clusterId);
		return (kind, object) -> supports(kind) ? StatusVocabulary.toState(verdict(object, usage)) : null;
	}

	/**
	 * What state this claim is in: its binding phase, or — when it is bound — whether the
	 * volume behind it is nearly full.
	 *
	 * <p>
	 * Not bound is red and phase-named ({@code Pending}, {@code Lost}, {@code Released}),
	 * because the pod that wants it is not running. Nearly full is amber: it is still
	 * serving, and it is a deadline rather than an outage.
	 */
	WorkloadHealth.Verdict verdict(GenericKubernetesResource claim, Map<String, VolumeUsage> usage) {
		String phase = str(WorkloadHealth.get(claim, "status", "phase"));
		if (!BOUND.equals(phase)) {
			return WorkloadHealth.Verdict.attention(phase.isEmpty() ? "Unknown" : phase, reason(claim, phase));
		}
		String full = fullnessReason(claim, usage);
		return (full != null) ? WorkloadHealth.Verdict.attentionSoft("Nearly full", full)
				: WorkloadHealth.Verdict.ok(BOUND);
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
	private String fullnessReason(GenericKubernetesResource claim, Map<String, VolumeUsage> usage) {
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

	private String key(GenericKubernetesResource claim) {
		return WorkloadHealth.namespaceOf(claim) + "/" + WorkloadHealth.nameOf(claim);
	}

	/** The claim's requested storage in bytes, or 0 when it does not state one. */
	private long requestedBytes(GenericKubernetesResource claim) {
		String requested = str(WorkloadHealth.get(claim, "spec", "resources", "requests", "storage"));
		if (requested.isEmpty()) {
			return 0;
		}
		try {
			return Quantity.getAmountInBytes(Quantity.parse(requested)).longValue();
		}
		catch (RuntimeException ex) {
			log.debug("Unparsable storage request on {}: {}", key(claim), ex.getMessage());
			return 0;
		}
	}

	/**
	 * The phase, plus the StorageClass when the claim is Pending — because a Pending
	 * claim is nearly always a question about its class, and having the name in the row
	 * saves opening the object to find it.
	 */
	private String reason(GenericKubernetesResource claim, String phase) {
		String state = phase.isEmpty() ? "unknown" : phase;
		String storageClass = str(WorkloadHealth.get(claim, "spec", "storageClassName"));
		if (PENDING.equals(state) && !storageClass.isBlank()) {
			return state + " · " + storageClass;
		}
		return state;
	}

	private String str(Object value) {
		return (value != null) ? String.valueOf(value) : "";
	}

}

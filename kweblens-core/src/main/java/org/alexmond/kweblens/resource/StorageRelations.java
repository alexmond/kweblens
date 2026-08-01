package org.alexmond.kweblens.resource;

import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.PersistentVolume;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * The two halves of a storage binding, each resolvable only from the other side.
 *
 * <p>
 * A claim and a volume each carry the other's <em>name</em>, which the detail view
 * already shows. What neither carries is the other's <em>state</em>, and that is where
 * the answers are: the reclaim policy that decides whether deleting the claim destroys
 * the data, the node affinity that pins a local volume to one machine, and the actual
 * backing source.
 */
@Slf4j
@RequiredArgsConstructor
class StorageRelations {

	private final ClusterRegistry clusters;

	/**
	 * The PersistentVolume a claim is bound to.
	 *
	 * <p>
	 * Cost: one GET, and none at all while the claim is unbound — {@code spec.volumeName}
	 * is empty until binding, and an empty relation then is the correct answer rather
	 * than a guess.
	 */
	Relation boundVolume(String clusterId, GenericKubernetesResource claim) {
		Object volumeName = claim.get("spec", "volumeName");
		if (!(volumeName instanceof String name) || name.isBlank()) {
			return Relation.of(List.of());
		}
		try {
			PersistentVolume volume = this.clusters.require(clusterId).persistentVolumes().withName(name).get();
			return (volume != null) ? RelationSupport.bound(List.of(volume)) : Relation.of(List.of());
		}
		catch (RuntimeException ex) {
			log.debug("PersistentVolume lookup failed for {}: {}", name, ex.getMessage());
			return Relation.from(ex);
		}
	}

	/**
	 * The PersistentVolumeClaim a volume is bound to.
	 *
	 * <p>
	 * The volume's {@code claimRef} survives the claim's deletion — that is what a
	 * {@code Released} volume is — so resolving it says whether the claim on the other
	 * end still exists, which is the difference between "in use" and "holding data
	 * nothing can reach".
	 *
	 * <p>
	 * Cost: one GET, and none when the volume is unclaimed.
	 */
	Relation boundClaim(String clusterId, GenericKubernetesResource volume) {
		Object namespace = volume.get("spec", "claimRef", "namespace");
		Object name = volume.get("spec", "claimRef", "name");
		if (!(namespace instanceof String ns) || !(name instanceof String claimName)) {
			return Relation.of(List.of());
		}
		try {
			PersistentVolumeClaim claim = this.clusters.require(clusterId)
				.persistentVolumeClaims()
				.inNamespace(ns)
				.withName(claimName)
				.get();
			return (claim != null) ? RelationSupport.bound(List.of(claim)) : Relation.of(List.of());
		}
		catch (RuntimeException ex) {
			log.debug("PersistentVolumeClaim lookup failed for {}/{}: {}", ns, claimName, ex.getMessage());
			return Relation.from(ex);
		}
	}

}

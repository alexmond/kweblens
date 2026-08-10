package org.alexmond.kweblens.resource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * Resolves the RELATIONS of an object — the questions a detail view should answer that
 * cannot be answered from the object alone.
 *
 * <p>
 * This exists because the UI's section registry is a pure function of one object
 * ({@code (o) => body}), so "which pods back this Service", "what mounts this Secret" and
 * "what created this pod" are not merely unimplemented there — they are inexpressible.
 * Doing the joins here means one implementation serves the SPA, a future TUI and the
 * agent tool surface, instead of each reimplementing them. See
 * {@code docs/design/detail-sections-audit.md}.
 *
 * <p>
 * <b>Every relation is independently guarded and bounded.</b> A drawer open must not
 * become a cluster-wide scan, and one unreadable kind must not blank the whole detail —
 * so each join returns a {@link Relation} carrying items, an error, or "not permitted",
 * and never throws.
 *
 * <p>
 * <b>What a relation is allowed to cost.</b> Every join is one of: a GET of a name the
 * object already carries; a LIST filtered server-side by a label selector; or a LIST of a
 * <em>single namespace's</em> objects of a kind that is few by construction (Ingresses,
 * HPAs, PodDisruptionBudgets, RoleBindings). Exactly one relation lists cluster-wide —
 * {@code grantedBy}, because ClusterRoleBindings have no other scope — and it hangs off
 * the ServiceAccount drawer, not off every pod, so nothing pays for it by accident. A
 * relation is registered only when the kind can actually have it, so a kind with no
 * relations issues no requests at all.
 *
 * <p>
 * <b>What is deliberately not resolved.</b> A reference to an object that does not exist
 * — a mounted ConfigMap that was deleted, an Ingress TLS secret that was never created —
 * is a real and common failure, and {@link Relation} has no way to say it: an item is an
 * object, so a missing reference can only be dropped (asserting a completeness that is
 * false) or fabricated (asserting an existence that is false). Those joins are left out
 * until the shape can carry a per-item note. Gateway API {@code HTTPRoute} is out for a
 * different reason: probing for it in a cluster without the CRDs would put a 404 on every
 * Service drawer, and the catalog that knows which CRDs exist lives above this layer.
 */
@Service
public class RelationService {

	/** Workload kinds an HPA can address through the scale subresource. */
	private static final Set<String> SCALABLE = Set.of("Deployment", "StatefulSet", "ReplicaSet",
			"ReplicationController");

	/** Kinds whose pods a PodDisruptionBudget can cover. */
	private static final Set<String> DISRUPTABLE = Set.of("Pod", "Deployment", "StatefulSet", "DaemonSet",
			"ReplicaSet");

	/** Kinds a pod consumes, and whose consumers are therefore worth scanning for. */
	private static final Set<String> CONSUMABLE = Set.of("Secret", "ConfigMap", "PersistentVolumeClaim");

	private final NetworkRelations network;

	private final ReferenceRelations references;

	private final WorkloadRelations workloads;

	private final StorageRelations storage;

	private final AccessRelations access;

	public RelationService(ClusterRegistry clusters) {
		this.network = new NetworkRelations(clusters);
		this.references = new ReferenceRelations(clusters);
		this.workloads = new WorkloadRelations(clusters);
		this.storage = new StorageRelations(clusters);
		this.access = new AccessRelations(clusters);
	}

	/**
	 * Every relation kweblens knows how to resolve for this kind. Keyed by a stable name
	 * the UI maps to a section. A kind with no relations returns an empty map and costs
	 * nothing.
	 */
	public Map<String, Relation> relationsFor(String clusterId, ResourceDescriptor descriptor,
			GenericKubernetesResource object) {
		Map<String, Relation> out = new LinkedHashMap<>();
		String kind = descriptor.kind();
		String name = (object.getMetadata() != null) ? object.getMetadata().getName() : null;
		if (name == null) {
			return out;
		}
		String namespace = object.getMetadata().getNamespace();
		if (namespace != null) {
			addNamespaced(out, clusterId, kind, namespace, object);
		}
		if ("PersistentVolume".equals(kind)) {
			// Cluster-scoped, so it is reachable only outside the namespaced branch — and
			// it is why this method no longer requires a namespace at all. The ROUTE
			// still
			// does: `/detail/{resourceId}/{namespace}/{name}` has no cluster-scoped form,
			// so a caller sends the `_` sentinel there (#313).
			out.put("boundClaim", this.storage.boundClaim(clusterId, object));
		}
		if (hasOwner(object)) {
			out.put("ownedBy", this.workloads.ownedBy(clusterId, namespace, object));
		}
		return out;
	}

	private void addNamespaced(Map<String, Relation> out, String clusterId, String kind, String namespace,
			GenericKubernetesResource object) {
		String name = object.getMetadata().getName();
		if ("Service".equals(kind)) {
			out.put("endpoints", this.network.endpoints(clusterId, namespace, name));
			out.put("routedBy", this.network.routedBy(clusterId, namespace, name));
		}
		if (selectsPods(kind, object)) {
			out.put("selectedPods", this.network.selectedPods(clusterId, namespace, object));
		}
		if (CONSUMABLE.contains(kind)) {
			out.put("mountedBy", this.references.mountedBy(clusterId, namespace, kind, name));
		}
		addWorkload(out, clusterId, kind, namespace, object);
		if ("PersistentVolumeClaim".equals(kind)) {
			out.put("boundVolume", this.storage.boundVolume(clusterId, object));
		}
		if ("Pod".equals(kind)) {
			out.put("serviceAccount", this.access.serviceAccount(clusterId, namespace, object));
		}
		if ("ServiceAccount".equals(kind)) {
			out.put("grantedBy", this.access.grantedBy(clusterId, namespace, name));
		}
	}

	private void addWorkload(Map<String, Relation> out, String clusterId, String kind, String namespace,
			GenericKubernetesResource object) {
		String name = object.getMetadata().getName();
		if ("Deployment".equals(kind)) {
			out.put("replicaSets", this.workloads.ownedReplicaSets(clusterId, namespace, name, object));
		}
		if (SCALABLE.contains(kind)) {
			out.put("autoscaledBy", this.workloads.autoscaledBy(clusterId, namespace, kind, name));
		}
		if (DISRUPTABLE.contains(kind)) {
			out.put("disruptionBudgets", this.workloads.disruptionBudgets(clusterId, namespace, kind, object));
		}
	}

	/**
	 * Whether {@code spec.selector} on this object selects <em>pods</em>.
	 *
	 * <p>
	 * Deliberately generic, so a custom resource carrying a pod selector gets the
	 * relation without being enumerated here — but not blindly generic: a
	 * PersistentVolumeClaim's {@code spec.selector} selects PersistentVolumes, and
	 * matching pods against it would produce a confidently wrong table.
	 */
	private boolean selectsPods(String kind, GenericKubernetesResource object) {
		return !"PersistentVolumeClaim".equals(kind) && !RelationSupport.selectorOf(object).isEmpty();
	}

	private boolean hasOwner(GenericKubernetesResource object) {
		return object.getMetadata() != null && object.getMetadata().getOwnerReferences() != null
				&& !object.getMetadata().getOwnerReferences().isEmpty();
	}

}

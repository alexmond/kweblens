package org.alexmond.kweblens.resource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudget;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * The controllers around a workload: what created it, what it created, what scales it and
 * what protects it.
 */
@Slf4j
@RequiredArgsConstructor
class WorkloadRelations {

	/**
	 * How far up the owner chain to walk. Three covers every built-in chain
	 * (Pod→ReplicaSet→Deployment, Pod→Job→CronJob) with a level spare for an operator
	 * that inserts one, and caps the cost of a cycle or a pathological chain at a known
	 * number of GETs.
	 */
	private static final int MAX_OWNER_DEPTH = 3;

	private final ClusterRegistry clusters;

	/**
	 * The chain of objects that created this one, nearest first.
	 *
	 * <p>
	 * This is the single most frequently needed relation and the one a client cannot
	 * fake: {@code metadata.ownerReferences} names the immediate owner, but the object an
	 * operator actually edits is two levels up. Being told a failing pod belongs to
	 * ReplicaSet {@code web-7d9f} is a step; being told it belongs to Deployment
	 * {@code web} is the answer, and it is also how you tell a pod that is <em>not</em>
	 * owned by anything — a bare pod nothing will reschedule — from one that is.
	 *
	 * <p>
	 * Cost: one or two GETs per level, at most {@value #MAX_OWNER_DEPTH} levels, and
	 * <b>zero</b> for an object with no owner references (the caller does not register
	 * the relation at all). The second GET per level happens only when the first misses:
	 * an owner reference does not say whether its target is namespaced, so a namespaced
	 * lookup is tried first and a cluster-scoped one only if it comes back empty.
	 */
	Relation ownedBy(String clusterId, String namespace, GenericKubernetesResource object) {
		try {
			KubernetesClient client = this.clusters.require(clusterId);
			List<Object> chain = new ArrayList<>();
			Set<String> seen = new LinkedHashSet<>();
			GenericKubernetesResource current = object;
			for (int depth = 0; depth < MAX_OWNER_DEPTH; depth++) {
				OwnerReference ref = controllerOf(current);
				if (ref == null || !seen.add(ref.getKind() + "/" + ref.getName())) {
					break;
				}
				GenericKubernetesResource owner = fetchOwner(client, namespace, ref);
				if (owner == null) {
					// A dangling owner reference is a real state (the owner was deleted
					// with --cascade=orphan). Stop rather than claim it does not exist.
					break;
				}
				chain.add(owner);
				current = owner;
			}
			return RelationSupport.bound(chain);
		}
		catch (RuntimeException ex) {
			log.debug("Owner chain walk failed in {}: {}", namespace, ex.getMessage());
			return Relation.from(ex);
		}
	}

	/**
	 * The ReplicaSets a Deployment owns — its rollout history, one object per revision.
	 *
	 * <p>
	 * A stuck rollout looks identical on the Deployment whether the new ReplicaSet cannot
	 * schedule or the old one will not scale down; the two ReplicaSets side by side say
	 * which. Old ones with zero replicas are the revisions a rollback would land on.
	 *
	 * <p>
	 * Cost: one LIST, filtered <em>server-side</em> by the Deployment's own selector, so
	 * the namespace's other ReplicaSets are never transferred.
	 */
	Relation ownedReplicaSets(String clusterId, String namespace, String name, GenericKubernetesResource deployment) {
		Map<String, String> selector = RelationSupport.selectorOf(deployment);
		if (selector.isEmpty()) {
			return Relation.of(List.of());
		}
		try {
			List<ReplicaSet> sets = this.clusters.require(clusterId)
				.apps()
				.replicaSets()
				.inNamespace(namespace)
				.withLabels(selector)
				.list()
				.getItems();
			// The selector alone is not proof of ownership — two Deployments can share
			// labels — so the owner reference decides.
			List<Object> owned = sets.stream()
				.filter((rs) -> hasDeploymentOwner(rs.getMetadata().getOwnerReferences(), name))
				.map((rs) -> (Object) rs)
				.toList();
			return RelationSupport.bound(owned);
		}
		catch (RuntimeException ex) {
			log.debug("ReplicaSet lookup failed for {}/{}: {}", namespace, name, ex.getMessage());
			return Relation.from(ex);
		}
	}

	/**
	 * The HorizontalPodAutoscalers targeting this workload.
	 *
	 * <p>
	 * This is the answer to "I scaled it and it went back", and to "why is it pinned at
	 * maxReplicas". Neither is visible from the workload: an HPA writes {@code replicas}
	 * and leaves no trace on the object it writes to.
	 *
	 * <p>
	 * Cost: one namespace LIST of HPAs, filtered locally — there is no selector for
	 * {@code scaleTargetRef}. HPAs are few (one per workload at most), so the list is
	 * small by construction. Reads {@code autoscaling/v2}; a cluster old enough to serve
	 * only {@code v1} surfaces that as the relation's error rather than as "none".
	 */
	Relation autoscaledBy(String clusterId, String namespace, String kind, String name) {
		try {
			List<HorizontalPodAutoscaler> hpas = this.clusters.require(clusterId)
				.autoscaling()
				.v2()
				.horizontalPodAutoscalers()
				.inNamespace(namespace)
				.list()
				.getItems();
			List<Object> matches = hpas.stream()
				.filter((hpa) -> targets(hpa, kind, name))
				.map((hpa) -> (Object) hpa)
				.toList();
			return RelationSupport.bound(matches);
		}
		catch (RuntimeException ex) {
			log.debug("HPA scan failed in {}: {}", namespace, ex.getMessage());
			return Relation.from(ex);
		}
	}

	/**
	 * The PodDisruptionBudgets whose selector covers this workload's pods.
	 *
	 * <p>
	 * A drain that hangs, or a rollout that will not proceed, is usually a budget nobody
	 * remembered — often one with {@code minAvailable} equal to the replica count, which
	 * permits no disruption at all. The budget names a selector, never a workload, so
	 * this direction has to be computed.
	 *
	 * <p>
	 * Cost: one namespace LIST of PodDisruptionBudgets, matched locally against the
	 * object's <em>pod template</em> labels. Only {@code matchLabels} is evaluated, so a
	 * budget written with {@code matchExpressions} is not reported — it is not claimed to
	 * be absent either, since this relation says which budgets were found, not that no
	 * others exist.
	 */
	Relation disruptionBudgets(String clusterId, String namespace, String kind, GenericKubernetesResource object) {
		Map<String, String> labels = RelationSupport.podLabelsOf(kind, object);
		if (labels.isEmpty()) {
			return Relation.of(List.of());
		}
		try {
			List<PodDisruptionBudget> budgets = this.clusters.require(clusterId)
				.policy()
				.v1()
				.podDisruptionBudget()
				.inNamespace(namespace)
				.list()
				.getItems();
			List<Object> matches = budgets.stream()
				.filter((pdb) -> covers(pdb, labels))
				.map((pdb) -> (Object) pdb)
				.toList();
			return RelationSupport.bound(matches);
		}
		catch (RuntimeException ex) {
			log.debug("PodDisruptionBudget scan failed in {}: {}", namespace, ex.getMessage());
			return Relation.from(ex);
		}
	}

	private boolean covers(PodDisruptionBudget pdb, Map<String, String> labels) {
		if (pdb.getSpec() == null || pdb.getSpec().getSelector() == null) {
			return false;
		}
		Map<String, String> selector = pdb.getSpec().getSelector().getMatchLabels();
		return RelationSupport.matches((selector != null) ? selector : Map.of(), labels);
	}

	private boolean targets(HorizontalPodAutoscaler hpa, String kind, String name) {
		if (hpa.getSpec() == null || hpa.getSpec().getScaleTargetRef() == null) {
			return false;
		}
		var target = hpa.getSpec().getScaleTargetRef();
		return kind.equals(target.getKind()) && name.equals(target.getName());
	}

	private boolean hasDeploymentOwner(List<OwnerReference> refs, String name) {
		return refs != null
				&& refs.stream().anyMatch((r) -> "Deployment".equals(r.getKind()) && name.equals(r.getName()));
	}

	private OwnerReference controllerOf(GenericKubernetesResource object) {
		if (object.getMetadata() == null || object.getMetadata().getOwnerReferences() == null) {
			return null;
		}
		List<OwnerReference> refs = object.getMetadata().getOwnerReferences();
		return refs.stream()
			.filter((r) -> Boolean.TRUE.equals(r.getController()))
			.findFirst()
			.orElseGet(() -> refs.isEmpty() ? null : refs.get(0));
	}

	private GenericKubernetesResource fetchOwner(KubernetesClient client, String namespace, OwnerReference ref) {
		if (ref.getKind() == null || ref.getName() == null) {
			return null;
		}
		String group = RelationSupport.groupOf(ref.getApiVersion());
		String version = RelationSupport.versionOf(ref.getApiVersion());
		String plural = plural(ref.getKind());
		if (namespace != null) {
			var namespaced = context(group, version, ref.getKind(), plural, true);
			GenericKubernetesResource found = client.genericKubernetesResources(namespaced)
				.inNamespace(namespace)
				.withName(ref.getName())
				.get();
			if (found != null) {
				return found;
			}
		}
		var clusterScoped = context(group, version, ref.getKind(), plural, false);
		return client.genericKubernetesResources(clusterScoped).withName(ref.getName()).get();
	}

	private ResourceDefinitionContext context(String group, String version, String kind, String plural,
			boolean namespaced) {
		return new ResourceDefinitionContext.Builder().withGroup(group)
			.withVersion(version)
			.withKind(kind)
			.withPlural(plural)
			.withNamespaced(namespaced)
			.build();
	}

	/**
	 * The resource plural for a kind, by the same rule the API machinery and
	 * controller-runtime use when they generate one. An owner reference carries the kind
	 * and the apiVersion but not the plural, and resolving it through discovery would
	 * mean an extra round trip per level for a rule that is right for every built-in
	 * workload kind and for CRDs generated by the standard tooling. A kind the rule gets
	 * wrong simply does not resolve, and the chain stops there.
	 */
	private String plural(String kind) {
		String lower = kind.toLowerCase(Locale.ROOT);
		if (lower.endsWith("s") || lower.endsWith("x") || lower.endsWith("z") || lower.endsWith("ch")
				|| lower.endsWith("sh")) {
			return lower + "es";
		}
		if (lower.endsWith("y") && lower.length() > 1 && "aeiou".indexOf(lower.charAt(lower.length() - 2)) < 0) {
			return lower.substring(0, lower.length() - 1) + "ies";
		}
		return lower + "s";
	}

}

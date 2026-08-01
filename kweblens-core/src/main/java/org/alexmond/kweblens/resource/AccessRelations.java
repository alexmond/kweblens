package org.alexmond.kweblens.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.rbac.Subject;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * Who a pod runs as, and what that identity has been granted.
 *
 * <p>
 * RBAC is the one part of a cluster that is only readable backwards. A ServiceAccount
 * records nothing about its own permissions; the grants live in bindings elsewhere,
 * including cluster-scoped ones, and "why is this pod getting 403 from the API server" is
 * unanswerable from any single object.
 */
@Slf4j
@RequiredArgsConstructor
class AccessRelations {

	/** The account a pod runs as when it does not name one. */
	private static final String DEFAULT_ACCOUNT = "default";

	private final ClusterRegistry clusters;

	/**
	 * The ServiceAccount a pod runs as.
	 *
	 * <p>
	 * Worth resolving rather than just naming: the account carries the image pull secrets
	 * that explain an {@code ImagePullBackOff} on a private registry, and
	 * {@code automountServiceAccountToken}, which explains a pod that has no token to
	 * call the API with at all. A pod that names no account runs as {@code default},
	 * which is itself frequently the finding.
	 *
	 * <p>
	 * Cost: one GET.
	 */
	Relation serviceAccount(String clusterId, String namespace, GenericKubernetesResource pod) {
		String name = accountName(pod);
		try {
			ServiceAccount account = this.clusters.require(clusterId)
				.serviceAccounts()
				.inNamespace(namespace)
				.withName(name)
				.get();
			return (account != null) ? RelationSupport.bound(List.of(account)) : Relation.of(List.of());
		}
		catch (RuntimeException ex) {
			log.debug("ServiceAccount lookup failed for {}/{}: {}", namespace, name, ex.getMessage());
			return Relation.from(ex);
		}
	}

	/**
	 * The RoleBindings and ClusterRoleBindings that grant this ServiceAccount something.
	 *
	 * <p>
	 * Reports bindings that name the account as a subject directly, plus those bound to
	 * the {@code system:serviceaccounts} groups that contain it. Bindings to broad groups
	 * such as {@code system:authenticated} are deliberately <em>not</em> reported: they
	 * apply to every identity in the cluster, so listing them would bury the grants that
	 * are specific to this account under the ones that say nothing about it.
	 *
	 * <p>
	 * Cost: two LISTs — RoleBindings in the account's namespace, and ClusterRoleBindings,
	 * which is necessarily cluster-wide because that is the only scope they have. It is
	 * the most expensive relation here, which is why it hangs off the ServiceAccount and
	 * not off every pod: the cost is paid by the drawer that asked the question.
	 */
	Relation grantedBy(String clusterId, String namespace, String name) {
		try {
			KubernetesClient client = this.clusters.require(clusterId);
			Set<String> identities = Set.of("system:serviceaccounts", "system:serviceaccounts:" + namespace);
			List<Object> matches = new ArrayList<>();
			List<RoleBinding> roleBindings = client.rbac().roleBindings().inNamespace(namespace).list().getItems();
			for (RoleBinding binding : roleBindings) {
				if (grants(binding.getSubjects(), namespace, name, identities)) {
					matches.add(binding);
				}
			}
			List<ClusterRoleBinding> clusterBindings = client.rbac().clusterRoleBindings().list().getItems();
			for (ClusterRoleBinding binding : clusterBindings) {
				if (grants(binding.getSubjects(), namespace, name, identities)) {
					matches.add(binding);
				}
			}
			return RelationSupport.bound(matches);
		}
		catch (RuntimeException ex) {
			log.debug("RBAC binding scan failed for {}/{}: {}", namespace, name, ex.getMessage());
			return Relation.from(ex);
		}
	}

	private boolean grants(List<Subject> subjects, String namespace, String name, Set<String> identities) {
		if (subjects == null) {
			return false;
		}
		return subjects.stream().anyMatch((s) -> namesAccount(s, namespace, name) || namesGroup(s, identities));
	}

	private boolean namesAccount(Subject subject, String namespace, String name) {
		if (!"ServiceAccount".equals(subject.getKind()) || !name.equals(subject.getName())) {
			return false;
		}
		// A RoleBinding may omit the subject namespace, meaning its own; a
		// ClusterRoleBinding always carries it.
		return subject.getNamespace() == null || subject.getNamespace().isBlank()
				|| namespace.equals(subject.getNamespace());
	}

	private boolean namesGroup(Subject subject, Set<String> identities) {
		return "Group".equals(subject.getKind()) && identities.contains(subject.getName());
	}

	private String accountName(GenericKubernetesResource pod) {
		Object named = pod.get("spec", "serviceAccountName");
		if (named instanceof String name && !name.isBlank()) {
			return name;
		}
		// The pre-1.9 spelling, still populated by the API server on every pod.
		Object legacy = pod.get("spec", "serviceAccount");
		if (legacy instanceof String name && !name.isBlank()) {
			return name;
		}
		return DEFAULT_ACCOUNT;
	}

}

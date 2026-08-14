package org.alexmond.kweblens.resource;

import java.util.List;
import java.util.Set;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.rbac.Subject;

/**
 * The one place that decides whether an RBAC binding's subject list names a given
 * ServiceAccount, and whether a subject is one the cluster ships with.
 *
 * <p>
 * This predicate is the whole of the {@code grantedBy} join. It exists as its own class
 * because two readers need it in opposite directions: the drawer's relation walks from
 * one ServiceAccount out to its bindings ({@code AccessRelations.grantedBy}), while the
 * security audit walks from a scope's bindings back to the accounts they name — and a
 * second copy of "does this subject mean that account" would let the drawer and the
 * finding disagree about the same two objects.
 *
 * <p>
 * The subtlety worth writing down: <b>a RoleBinding's subject may omit its namespace</b>,
 * meaning the binding's own, while a ClusterRoleBinding's subject always carries one.
 * Both spellings have to match, and only against the right namespace — matching on name
 * alone is the classic wrong join, because {@code default} is a ServiceAccount in every
 * namespace there is.
 */
public final class RbacSubjects {

	/** The built-in ClusterRole that grants everything, everywhere. */
	public static final String CLUSTER_ADMIN = "cluster-admin";

	/**
	 * Namespaces whose ServiceAccounts belong to the cluster itself. A grant to one of
	 * these is how a control-plane component is installed, so reporting them would put
	 * the same five rows on every cluster and train the reader to skip the check.
	 */
	private static final Set<String> SYSTEM_NAMESPACES = Set.of("kube-system", "kube-public", "kube-node-lease");

	/** The account a pod runs as when it does not name one. */
	private static final String DEFAULT_ACCOUNT = "default";

	private RbacSubjects() {
	}

	/**
	 * The ServiceAccount a pod runs as. A pod that names none runs as {@code default},
	 * which is itself frequently the finding.
	 */
	public static String accountName(GenericKubernetesResource pod) {
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

	/**
	 * Whether these subjects grant something to the named ServiceAccount — directly, or
	 * through one of the {@code system:serviceaccounts} groups that contains it.
	 * @param subjects a binding's subject list, possibly null
	 * @param namespace the account's namespace
	 * @param name the account's name
	 */
	public static boolean grantsAccount(List<Subject> subjects, String namespace, String name) {
		if (subjects == null) {
			return false;
		}
		Set<String> identities = Set.of("system:serviceaccounts", "system:serviceaccounts:" + namespace);
		return subjects.stream().anyMatch((s) -> namesAccount(s, namespace, name) || namesGroup(s, identities));
	}

	/** Whether this subject is the named ServiceAccount itself. */
	public static boolean namesAccount(Subject subject, String namespace, String name) {
		if (!"ServiceAccount".equals(subject.getKind()) || !name.equals(subject.getName())) {
			return false;
		}
		// A RoleBinding may omit the subject namespace, meaning its own; a
		// ClusterRoleBinding always carries it.
		return subject.getNamespace() == null || subject.getNamespace().isBlank()
				|| namespace.equals(subject.getNamespace());
	}

	/**
	 * The namespace a ServiceAccount subject refers to: its own if it carries one,
	 * otherwise the binding's.
	 */
	public static String accountNamespace(Subject subject, String bindingNamespace) {
		String declared = subject.getNamespace();
		return (declared != null && !declared.isBlank()) ? declared : bindingNamespace;
	}

	/**
	 * Whether this subject is part of the cluster's own furniture rather than something
	 * an operator created.
	 *
	 * <p>
	 * Every cluster ships a {@code cluster-admin} ClusterRoleBinding for the
	 * {@code system:masters} group, so a check that reported it would fire on a correctly
	 * installed cluster. The rule is deliberately about the subject and not the binding's
	 * name: a binding called {@code system:something} may still name an ordinary account,
	 * and that grant is real.
	 *
	 * <p>
	 * <b>Known blind spot:</b> a grant to the group {@code system:serviceaccounts} —
	 * every account in the cluster — is skipped by this rule too, because it is spelled
	 * like the built-in one. That is a real misconfiguration this audit does not report.
	 */
	public static boolean isSystemSubject(Subject subject, String bindingNamespace) {
		String name = String.valueOf(subject.getName());
		if ("ServiceAccount".equals(subject.getKind())) {
			return SYSTEM_NAMESPACES.contains(accountNamespace(subject, bindingNamespace));
		}
		return name.startsWith("system:");
	}

	private static boolean namesGroup(Subject subject, Set<String> identities) {
		return "Group".equals(subject.getKind()) && identities.contains(subject.getName());
	}

}

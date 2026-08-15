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

	/** The reserved prefix the cluster issues its own identities under. */
	private static final String SYSTEM_PREFIX = "system:";

	/** The group that contains every ServiceAccount in the cluster. */
	private static final String ALL_ACCOUNTS_GROUP = "system:serviceaccounts";

	/** Prefix of the group that contains every ServiceAccount in one namespace. */
	private static final String ACCOUNTS_IN_NAMESPACE_GROUP = ALL_ACCOUNTS_GROUP + ":";

	/**
	 * The group of every principal — user or ServiceAccount — whose credential the API
	 * server accepted.
	 */
	private static final String AUTHENTICATED_GROUP = "system:authenticated";

	/**
	 * The group of every caller that presented no credential, or one that was rejected.
	 */
	private static final String UNAUTHENTICATED_GROUP = "system:unauthenticated";

	/**
	 * The username the API server gives a request it could not authenticate. It is a
	 * <i>User</i>, not a group — the matching group is {@link #UNAUTHENTICATED_GROUP}.
	 */
	private static final String ANONYMOUS_USER = "system:anonymous";

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
	 *
	 * <p>
	 * <b>An authentication population is deliberately not a match</b>, and that is a
	 * decision rather than an oversight (#387). Every ServiceAccount is in
	 * {@code system:authenticated}, so a binding to it is true of this account and of
	 * every other one — as an answer to "what has <i>this</i> account been granted" it
	 * says nothing while burying the grants that do. The security audit asks the opposite
	 * question — "what is this cluster configured to permit" — and there the same binding
	 * is the whole finding, which is why {@link #isControlPlaneSubject} treats it as a
	 * population and this method does not treat it as an identity.
	 * @param subjects a binding's subject list, possibly null
	 * @param namespace the account's namespace
	 * @param name the account's name
	 */
	public static boolean grantsAccount(List<Subject> subjects, String namespace, String name) {
		if (subjects == null) {
			return false;
		}
		Set<String> identities = Set.of(ALL_ACCOUNTS_GROUP, ACCOUNTS_IN_NAMESPACE_GROUP + namespace);
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
	 * Whether this subject <b>is the control plane</b> — an identity the cluster issues
	 * to itself, for which holding {@code cluster-admin} is the installed state rather
	 * than a misconfiguration. This is the question, and the {@code system:} prefix is
	 * only evidence for it; where the two disagree, the question wins.
	 *
	 * <p>
	 * Deliberately exempt, and why:
	 * <ul>
	 * <li><b>ServiceAccounts in {@code kube-system}, {@code kube-public} and
	 * {@code kube-node-lease}</b> — a grant to one of these is how a control-plane
	 * component is installed.</li>
	 * <li><b>{@code system:masters}</b> — every cluster ships a {@code cluster-admin}
	 * ClusterRoleBinding for it, so reporting it would fire on a correctly installed
	 * cluster and train the reader to skip the whole check.</li>
	 * <li><b>The rest of the reserved {@code system:} namespace</b> —
	 * {@code system:nodes}, {@code system:monitoring}, {@code system:kube-scheduler},
	 * {@code system:kube-controller-manager} and friends: identities the API server
	 * issues to a component, not ones an operator created.</li>
	 * </ul>
	 *
	 * <p>
	 * <b>The carve-out, and the point of this method:</b> the reserved namespace holds
	 * two different sorts of thing, and only one of them is a component. The other is an
	 * <b>open population</b> — a subject defined by a <i>property of the caller</i>
	 * rather than by which component it is, whose membership is automatic and
	 * unrevokable, so the grant cannot be narrowed by editing any account. A population
	 * is never the control plane, however it is spelled. Two families, so far:
	 * <ul>
	 * <li><b>Workload identities (#382)</b> — {@code system:serviceaccounts} and
	 * {@code system:serviceaccounts:<namespace>}: every pod an operator deploys,
	 * including ones deployed tomorrow. No cluster ships such a {@code cluster-admin}
	 * grant: measured against a live k3s cluster, the only default binding naming
	 * {@code system:serviceaccounts} is {@code system:service-account-issuer-discovery},
	 * whose roleRef is not {@code cluster-admin}.</li>
	 * <li><b>Authentication outcomes (#387)</b> — {@code system:authenticated},
	 * {@code system:unauthenticated} and the {@code system:anonymous} user: everyone who
	 * did, or did not, present a credential the API server accepted. Also not shipped
	 * with admin: on the same live cluster {@code system:authenticated} appears only on
	 * {@code system:basic-user}, {@code system:discovery} and
	 * {@code system:public-info-viewer}, and {@code system:unauthenticated} only on the
	 * last of those.</li>
	 * </ul>
	 *
	 * <p>
	 * The rule is about the subject and never the binding's name: a binding called
	 * {@code system:something} may still name an ordinary account, and that grant is
	 * real.
	 */
	public static boolean isControlPlaneSubject(Subject subject, String bindingNamespace) {
		if ("ServiceAccount".equals(subject.getKind())) {
			return SYSTEM_NAMESPACES.contains(accountNamespace(subject, bindingNamespace));
		}
		if (isOpenPopulation(subject)) {
			return false;
		}
		return String.valueOf(subject.getName()).startsWith(SYSTEM_PREFIX);
	}

	/**
	 * Whether this subject names a <b>population</b> rather than a component: a set every
	 * caller with the right property is in, whether or not anyone intended it.
	 *
	 * <p>
	 * This is the one exception to the reserved-prefix rule, and it is deliberately a
	 * union of named families rather than a widening filter — a new family has to be
	 * argued for in its own predicate, so the exception cannot grow by accident.
	 */
	private static boolean isOpenPopulation(Subject subject) {
		return isWorkloadIdentityGroup(subject) || isAuthenticationPopulation(subject);
	}

	/**
	 * Whether this subject is <b>an authentication outcome</b> — the set of callers the
	 * API server decided did, or did not, present an acceptable credential (#387).
	 *
	 * <p>
	 * Matched on the exact kind the API server actually issues, because the other
	 * spelling grants nothing: {@code system:authenticated} and
	 * {@code system:unauthenticated} are Groups, while {@code system:anonymous} is the
	 * <i>username</i> an unauthenticated request is given. A binding that names
	 * {@code system:anonymous} as a Group confers no access at all, so it falls back to
	 * the reserved-prefix rule and stays unreported — the same treatment
	 * {@code system:serviceaccounts:} with no namespace after it gets.
	 */
	public static boolean isAuthenticationPopulation(Subject subject) {
		String name = String.valueOf(subject.getName());
		if ("Group".equals(subject.getKind())) {
			return AUTHENTICATED_GROUP.equals(name) || UNAUTHENTICATED_GROUP.equals(name);
		}
		return "User".equals(subject.getKind()) && ANONYMOUS_USER.equals(name);
	}

	/**
	 * Whether membership of this authentication population needs <b>no credential at
	 * all</b> — anyone who can open a connection to the API server is in it. Only
	 * meaningful for a subject {@link #isAuthenticationPopulation(Subject)} accepts.
	 */
	public static boolean isUnauthenticatedPopulation(Subject subject) {
		return UNAUTHENTICATED_GROUP.equals(subject.getName()) || ANONYMOUS_USER.equals(subject.getName());
	}

	/**
	 * Whether this subject is a <b>set of workload identities</b>: the group of every
	 * ServiceAccount in the cluster, or of every one in a single namespace.
	 *
	 * <p>
	 * Membership is automatic and unrevokable — an account joins by existing, and a
	 * namespace's group gains every account created in it afterwards — so a grant to one
	 * of these cannot be narrowed by editing accounts, only by removing the binding.
	 */
	public static boolean isWorkloadIdentityGroup(Subject subject) {
		if (!"Group".equals(subject.getKind())) {
			return false;
		}
		String name = String.valueOf(subject.getName());
		// A trailing colon with nothing after it names no namespace and so no account;
		// it falls through to the reserved-prefix rule rather than being reported.
		return ALL_ACCOUNTS_GROUP.equals(name) || (name.startsWith(ACCOUNTS_IN_NAMESPACE_GROUP)
				&& name.length() > ACCOUNTS_IN_NAMESPACE_GROUP.length());
	}

	/**
	 * The namespace a workload-identity group is confined to, or {@code null} for
	 * {@code system:serviceaccounts}, which spans the cluster. Only meaningful for a
	 * subject {@link #isWorkloadIdentityGroup(Subject)} accepts.
	 */
	public static String workloadIdentityGroupNamespace(Subject subject) {
		String name = String.valueOf(subject.getName());
		if (!name.startsWith(ACCOUNTS_IN_NAMESPACE_GROUP)) {
			return null;
		}
		return name.substring(ACCOUNTS_IN_NAMESPACE_GROUP.length());
	}

	private static boolean namesGroup(Subject subject, Set<String> identities) {
		return "Group".equals(subject.getKind()) && identities.contains(subject.getName());
	}

}

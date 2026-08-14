package org.alexmond.kweblens.health;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleRef;
import io.fabric8.kubernetes.api.model.rbac.Subject;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.resource.RbacSubjects;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.resource.WellKnownKinds;

/**
 * What this scope is configured to permit: the privileges a pod's own spec claims, and
 * the RBAC grants that make an identity administrator.
 *
 * <p>
 * <b>The cross-manifest half is the point.</b> "This ServiceAccount is cluster
 * administrator, and these three pods run as it" is not on the ServiceAccount, not on the
 * binding and not on the pod: the binding names an account but not what runs as it, and
 * the pod names an account but not what it may do. Only the join says it, which is why
 * RBAC is the one part of a cluster that is readable backwards only — the same reason
 * {@code AccessRelations} exists. The predicate that decides whether a binding's subjects
 * name an account is shared with that relation ({@link RbacSubjects}), so the drawer and
 * this finding cannot disagree about the same two objects.
 *
 * <p>
 * <b>Cost: two list requests for the whole scope</b> — RoleBindings over the scope, and
 * ClusterRoleBindings, which is necessarily cluster-wide because that is the only scope
 * they have. Nothing here is per pod: the pods are the list the caller already had, and
 * the bindings are indexed once. Walking {@code grantedBy} per pod would have been two
 * lists <i>each</i>, which is why this reads the join in the other direction instead.
 *
 * <p>
 * Per ADR-001 kweblens has one operator identity and no RBAC-awareness, so nothing here
 * is an authorization decision — these are observations about the cluster, of the same
 * kind as "this Service has no endpoints".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityAuditService {

	/**
	 * Cap on container-level findings. A cluster-wide audit of a busy cluster can name
	 * dozens of privileged system agents, and the point of this list is to be read. The
	 * cap bites on the LEAST severe, after sorting, and says so in an extra finding
	 * rather than truncating silently.
	 */
	private static final int MAX_CONTAINER_FINDINGS = 20;

	/** Pods named per over-privileged account before the rest become "(+N more)". */
	private static final int MAX_PODS_NAMED = 5;

	private static final Comparator<SecurityFinding> ORDER = Comparator.comparingInt(SecurityFinding::rank)
		.thenComparing(SecurityFinding::title)
		.thenComparing(SecurityFinding::object);

	private final ClusterRegistry clusters;

	private final ResourceService resources;

	/**
	 * Audit a scope, listing the pods here. Callers that already hold the pod list — the
	 * diagnosis does — should pass it instead, so this dimension adds no pod request.
	 */
	public List<SecurityFinding> audit(String clusterId, String namespace) {
		return audit(clusterId, namespace, this.resources.listRaw(clusterId, WellKnownKinds.PODS, namespace));
	}

	/**
	 * Audit a scope over pods that have already been listed.
	 *
	 * <p>
	 * The result is sorted by severity, title and object, so two audits of an unchanged
	 * cluster produce the same list in the same order — the diagnosis fingerprints its
	 * findings, and an order that depended on the API server's listing order would make
	 * that key change without the cluster changing.
	 */
	public List<SecurityFinding> audit(String clusterId, String namespace, List<GenericKubernetesResource> pods) {
		List<SecurityFinding> findings = new ArrayList<>(containerFindings(pods));
		findings.addAll(grantFindings(clusterId, namespace, pods));
		findings.sort(ORDER);
		return List.copyOf(findings);
	}

	/** The pod-spec half: no cluster request, capped, and honest when the cap bites. */
	private List<SecurityFinding> containerFindings(List<GenericKubernetesResource> pods) {
		List<SecurityFinding> all = new ArrayList<>();
		for (GenericKubernetesResource pod : pods) {
			all.addAll(PodSecurity.forPod(pod));
		}
		if (all.size() <= MAX_CONTAINER_FINDINGS) {
			return all;
		}
		all.sort(ORDER);
		List<SecurityFinding> kept = new ArrayList<>(all.subList(0, MAX_CONTAINER_FINDINGS));
		kept.add(new SecurityFinding("info", "Further container privileges not listed",
				"Pods/" + (all.size() - MAX_CONTAINER_FINDINGS),
				(all.size() - MAX_CONTAINER_FINDINGS) + " more container privilege findings in this scope"
						+ " are not listed, so the most severe stay readable",
				"Narrow the diagnosis to one namespace to see the rest."));
		return kept;
	}

	/**
	 * The RBAC half, in both directions: the grant itself, and what runs with it.
	 *
	 * <p>
	 * A grant produces up to two findings on purpose. The binding finding names the thing
	 * to change; the workload finding names the blast radius, which the binding cannot
	 * know and which is what decides how urgent the first one is.
	 */
	private List<SecurityFinding> grantFindings(String clusterId, String namespace,
			List<GenericKubernetesResource> pods) {
		List<Grant> grants;
		try {
			grants = grants(clusterId, namespace);
		}
		catch (RuntimeException ex) {
			// Saying nothing here would read as "no over-privileged identities", which is
			// the dangerous direction: kweblens may simply not be allowed to list
			// bindings.
			log.debug("RBAC grant scan failed: {}", ex.getMessage());
			return List.of(new SecurityFinding("info", "RBAC grants could not be read", "ClusterRoleBindings",
					String.valueOf(ex.getMessage()),
					"The identity kweblens uses may not be permitted to list RoleBindings and ClusterRoleBindings."
							+ " Nothing about cluster-admin grants was checked."));
		}
		Map<String, List<String>> podsByAccount = podsByAccount(pods);
		List<SecurityFinding> findings = new ArrayList<>();
		for (Grant grant : grants) {
			if (!grant.grantsClusterAdmin()) {
				continue;
			}
			for (Subject subject : grant.subjectList()) {
				addSubjectFindings(findings, grant, subject, namespace, podsByAccount);
			}
		}
		return findings;
	}

	private void addSubjectFindings(List<SecurityFinding> findings, Grant grant, Subject subject, String namespace,
			Map<String, List<String>> podsByAccount) {
		if (RbacSubjects.isControlPlaneSubject(subject, grant.namespace())) {
			return;
		}
		boolean account = "ServiceAccount".equals(subject.getKind());
		String subjectNamespace = subjectNamespace(subject, grant.namespace());
		// A namespace-scoped diagnosis reports a cluster-scoped binding only when it
		// grants something to an identity in that namespace. A User, or a group that
		// spans the cluster, is not namespaced, so it belongs to the cluster-wide view.
		if (namespace != null && !namespace.equals(subjectNamespace)) {
			return;
		}
		findings.add(bindingFinding(grant, subject, subjectNamespace));
		// Only a named account gets the second, blast-radius finding. A
		// workload-identity group has no list of pods worth naming — the answer is
		// every pod in the scope, including ones created after this was read, which the
		// binding finding says in one sentence instead of a hundred names.
		if (!account) {
			return;
		}
		List<String> running = podsByAccount.get(subjectNamespace + "/" + subject.getName());
		if (running != null) {
			findings.add(workloadFinding(grant, subject, subjectNamespace, running));
		}
	}

	/**
	 * The namespace a subject belongs to, or null if it belongs to no single one.
	 *
	 * <p>
	 * A ServiceAccount has one. So does {@code system:serviceaccounts:<ns>}, which is
	 * what lets a namespaced audit of that namespace see a cluster-scoped binding aimed
	 * at it — the accounts it hands cluster-admin to are exactly the ones in scope.
	 * {@code system:serviceaccounts} spans the cluster and so stays in the cluster-wide
	 * view, like a User.
	 */
	private String subjectNamespace(Subject subject, String bindingNamespace) {
		if ("ServiceAccount".equals(subject.getKind())) {
			return RbacSubjects.accountNamespace(subject, bindingNamespace);
		}
		if (RbacSubjects.isWorkloadIdentityGroup(subject)) {
			return RbacSubjects.workloadIdentityGroupNamespace(subject);
		}
		return null;
	}

	/** What the binding itself says — readable from that one object. */
	private SecurityFinding bindingFinding(Grant grant, Subject subject, String subjectNamespace) {
		if (RbacSubjects.isWorkloadIdentityGroup(subject)) {
			return workloadGroupFinding(grant, subject, subjectNamespace);
		}
		String who = describe(subject, subjectNamespace);
		if (grant.clusterScoped()) {
			return new SecurityFinding("critical", "ClusterRoleBinding grants cluster-admin", grant.ref(),
					"roleRef ClusterRole/" + RbacSubjects.CLUSTER_ADMIN + ", subject " + who
							+ " — that identity can read, change and delete anything in the cluster",
					"Bind " + who + " to a Role with only the verbs it needs, or remove the binding.");
		}
		return new SecurityFinding("warning", "RoleBinding grants cluster-admin in one namespace", grant.ref(),
				"roleRef ClusterRole/" + RbacSubjects.CLUSTER_ADMIN + ", subject " + who
						+ " — full control of namespace '" + grant.namespace() + "'",
				"Bind " + who + " to a Role with only the verbs it needs, or remove the binding.");
	}

	/**
	 * A grant made to every workload identity at once — {@code system:serviceaccounts},
	 * or one namespace's {@code system:serviceaccounts:<ns>} (#382).
	 *
	 * <p>
	 * <b>Severity follows what the grant confers</b>, the same axis every other binding
	 * finding uses: a ClusterRoleBinding to {@code cluster-admin} makes each of those
	 * accounts administrator of the whole cluster, so compromising any pod running under
	 * the group — including one deployed tomorrow, and including one that named no
	 * account and got {@code default} — is a cluster takeover. That is {@code critical}
	 * whether the group is the cluster's accounts or one namespace's. A RoleBinding
	 * confines the grant to its own namespace and stays a {@code warning}, exactly as it
	 * does for a single account.
	 *
	 * <p>
	 * The two are still not equally bad, and the difference is in the title and the
	 * detail rather than the severity: the cluster-wide group means <i>every</i> pod
	 * anywhere, the namespaced one means every pod in one namespace. Demoting the
	 * namespaced case to {@code warning} would file "every workload in this namespace can
	 * delete anything in the cluster" alongside "this account owns one namespace", which
	 * is the wrong claim; {@code info} is not a severity at all here — it is the bucket
	 * for what could not be checked.
	 */
	private SecurityFinding workloadGroupFinding(Grant grant, Subject subject, String subjectNamespace) {
		String who = (subjectNamespace != null) ? "every ServiceAccount in namespace '" + subjectNamespace + "'"
				: "every ServiceAccount in the cluster";
		String confers = grant.clusterScoped() ? "administrator of the whole cluster"
				: "full control of namespace '" + grant.namespace() + "'";
		return new SecurityFinding(grant.clusterScoped() ? "critical" : "warning", groupTitle(grant, subjectNamespace),
				grant.ref(),
				"roleRef ClusterRole/" + RbacSubjects.CLUSTER_ADMIN + ", subject Group " + subject.getName() + " — "
						+ who + ", including 'default' and any account created later, is " + confers
						+ ", so a compromise of any pod running as one of them is too",
				"Remove the binding and grant only the individual ServiceAccounts that need it."
						+ " Membership of this group is automatic, so the grant cannot be narrowed by editing accounts.");
	}

	/**
	 * Static per case, because the title is what a reader groups findings by and the
	 * order they sort in.
	 */
	private String groupTitle(Grant grant, String subjectNamespace) {
		if (!grant.clusterScoped()) {
			return "RoleBinding grants cluster-admin to a whole group of ServiceAccounts";
		}
		return (subjectNamespace != null)
				? "ClusterRoleBinding grants cluster-admin to every ServiceAccount in a namespace"
				: "ClusterRoleBinding grants cluster-admin to every ServiceAccount in the cluster";
	}

	/**
	 * The finding that no single object can produce: an identity's grant, joined to the
	 * pods running as it.
	 *
	 * <p>
	 * Both halves are named so the reader can check them: the binding is one
	 * {@code kubectl get}, and each pod's {@code spec.serviceAccountName} is another.
	 */
	private SecurityFinding workloadFinding(Grant grant, Subject subject, String subjectNamespace,
			List<String> running) {
		String scope = grant.clusterScoped() ? "the whole cluster" : "namespace '" + grant.namespace() + "'";
		return new SecurityFinding(grant.clusterScoped() ? "critical" : "warning",
				"Workloads run as an identity with cluster-admin",
				"ServiceAccount/" + subjectNamespace + "/" + subject.getName(),
				grant.ref() + " binds it to ClusterRole/" + RbacSubjects.CLUSTER_ADMIN + "; " + running.size() + " "
						+ ((running.size() == 1) ? "pod runs" : "pods run") + " as it (" + named(running)
						+ "), so a compromise of any of them is administrator of " + scope,
				"Give these pods a ServiceAccount bound only to what they need —"
						+ " a workload that never calls the API server needs no grant at all.");
	}

	private String named(List<String> pods) {
		if (pods.size() <= MAX_PODS_NAMED) {
			return String.join(", ", pods);
		}
		return String.join(", ", pods.subList(0, MAX_PODS_NAMED)) + " (+" + (pods.size() - MAX_PODS_NAMED) + " more)";
	}

	private String describe(Subject subject, String subjectNamespace) {
		if ("ServiceAccount".equals(subject.getKind())) {
			return "ServiceAccount " + subjectNamespace + "/" + subject.getName();
		}
		return subject.getKind() + " " + subject.getName();
	}

	/**
	 * Pod names by {@code namespace/serviceAccountName}, sorted, so the finding's pod
	 * list does not depend on the order the API server listed them in.
	 */
	private Map<String, List<String>> podsByAccount(List<GenericKubernetesResource> pods) {
		Map<String, List<String>> out = new LinkedHashMap<>();
		for (GenericKubernetesResource pod : pods) {
			String key = WorkloadHealth.namespaceOf(pod) + "/" + RbacSubjects.accountName(pod);
			out.computeIfAbsent(key, (k) -> new ArrayList<>()).add(WorkloadHealth.nameOf(pod));
		}
		for (List<String> names : out.values()) {
			names.sort(Comparator.naturalOrder());
		}
		return out;
	}

	/**
	 * Every binding in the scope, in a stable order. Two list requests, whatever the
	 * scope holds.
	 */
	private List<Grant> grants(String clusterId, String namespace) {
		KubernetesClient client = this.clusters.require(clusterId);
		Map<String, Grant> byRef = new TreeMap<>();
		var roleBindings = client.rbac().roleBindings();
		List<RoleBinding> namespaced = ((namespace != null) ? roleBindings.inNamespace(namespace)
				: roleBindings.inAnyNamespace())
			.list()
			.getItems();
		for (RoleBinding binding : namespaced) {
			Grant grant = new Grant("RoleBinding", binding.getMetadata().getNamespace(),
					binding.getMetadata().getName(), binding.getRoleRef(), binding.getSubjects());
			byRef.put(grant.ref(), grant);
		}
		for (ClusterRoleBinding binding : client.rbac().clusterRoleBindings().list().getItems()) {
			Grant grant = new Grant("ClusterRoleBinding", null, binding.getMetadata().getName(), binding.getRoleRef(),
					binding.getSubjects());
			byRef.put(grant.ref(), grant);
		}
		return List.copyOf(byRef.values());
	}

	/** One binding, whichever of the two kinds it is. */
	private record Grant(String kind, String namespace, String name, RoleRef roleRef, List<Subject> subjects) {

		boolean clusterScoped() {
			return this.namespace == null;
		}

		String ref() {
			return this.kind + "/" + ((this.namespace != null) ? this.namespace + "/" : "") + this.name;
		}

		List<Subject> subjectList() {
			return (this.subjects != null) ? this.subjects : List.of();
		}

		/**
		 * Whether this binding points at the built-in {@code cluster-admin} ClusterRole.
		 *
		 * <p>
		 * Matched by name rather than by reading the ClusterRole's rules, deliberately:
		 * the name is on the binding, so the finding is checkable from the object it
		 * names, and finding every wildcard-granting ClusterRole would mean listing all
		 * of them on every diagnosis. The consequence is stated rather than hidden — a
		 * custom role with {@code *} on {@code *} is not reported.
		 */
		boolean grantsClusterAdmin() {
			return this.roleRef != null && "ClusterRole".equals(this.roleRef.getKind())
					&& RbacSubjects.CLUSTER_ADMIN.equals(this.roleRef.getName());
		}

	}

}

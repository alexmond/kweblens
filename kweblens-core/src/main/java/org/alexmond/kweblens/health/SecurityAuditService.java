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

	/**
	 * The two halves of this audit, named so a coverage gap says which one fell short.
	 */
	private static final String CONTAINERS = "container privileges";

	private static final String GRANTS = "RBAC grants";

	private static final Comparator<SecurityFinding> ORDER = Comparator.comparingInt(SecurityFinding::rank)
		.thenComparing(SecurityFinding::title)
		.thenComparing(SecurityFinding::object);

	private final ClusterRegistry clusters;

	private final ResourceService resources;

	/**
	 * Audit a scope, listing the pods here. Callers that already hold the pod list — the
	 * diagnosis does — should pass it instead, so this dimension adds no pod request.
	 */
	public SecurityAudit audit(String clusterId, String namespace) {
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
	 *
	 * <p>
	 * <b>The coverage gaps are collected here, from the two places that give up</b>, and
	 * they are appended in the order those two run rather than gathered afterwards — a
	 * consumer that had to work out "was this list complete" by matching a finding's
	 * title would be keeping a second copy of a rule that lives in this file (#388). The
	 * list is empty on an audit that saw everything, which is what makes it worth reading
	 * when it is not.
	 */
	public SecurityAudit audit(String clusterId, String namespace, List<GenericKubernetesResource> pods) {
		List<CoverageGap> incomplete = new ArrayList<>();
		List<SecurityFinding> findings = new ArrayList<>(containerFindings(pods, incomplete));
		findings.addAll(grantFindings(clusterId, namespace, pods, incomplete));
		findings.sort(ORDER);
		return new SecurityAudit(List.copyOf(findings), List.copyOf(incomplete));
	}

	/** The pod-spec half: no cluster request, capped, and honest when the cap bites. */
	private List<SecurityFinding> containerFindings(List<GenericKubernetesResource> pods,
			List<CoverageGap> incomplete) {
		List<SecurityFinding> all = new ArrayList<>();
		for (GenericKubernetesResource pod : pods) {
			all.addAll(PodSecurity.forPod(pod));
		}
		if (all.size() <= MAX_CONTAINER_FINDINGS) {
			return all;
		}
		all.sort(ORDER);
		int dropped = all.size() - MAX_CONTAINER_FINDINGS;
		List<SecurityFinding> kept = new ArrayList<>(all.subList(0, MAX_CONTAINER_FINDINGS));
		kept.add(new SecurityFinding("info", "Further container privileges not listed", "Pods/" + dropped,
				dropped + " more container privilege findings in this scope"
						+ " are not listed, so the most severe stay readable",
				"Narrow the diagnosis to one namespace to see the rest."));
		// The finding above says it beside the objects; this says it about the list.
		// Both, on purpose: the reader of the header and the reader of the export are
		// not the same person. And this one is shorter, also on purpose — the advice
		// stays on the card, because a header line long enough to carry it stops being
		// read at all (measured at 200 characters per line before it was cut back).
		incomplete.add(new CoverageGap(CONTAINERS,
				dropped + " further container privilege findings are not listed, so the most severe stay readable."));
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
			List<GenericKubernetesResource> pods, List<CoverageGap> incomplete) {
		List<Grant> grants;
		try {
			grants = grants(clusterId, namespace);
		}
		catch (RuntimeException ex) {
			// Saying nothing here would read as "no over-privileged identities", which is
			// the dangerous direction: kweblens may simply not be allowed to list
			// bindings.
			log.debug("RBAC grant scan failed: {}", ex.getMessage());
			// The gap's reason is fixed text while the finding carries the exception's
			// message. Deliberate: the gap rides in a response asserted byte-identical
			// across two reads of an unchanged cluster, and a message that varies between
			// two failures of the same kind would break that. The message is still in the
			// finding, which is where a reader goes for the evidence.
			incomplete.add(new CoverageGap(GRANTS,
					"RoleBindings and ClusterRoleBindings could not be listed, so no cluster-admin grant"
							+ " was checked."));
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
		if (namespace != null && outOfScope(grant, subjectNamespace, namespace)) {
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
	 * Whether a namespaced audit should leave this binding out — <b>decided by the
	 * binding's own namespace whenever it has one</b> (#398).
	 *
	 * <p>
	 * <b>A RoleBinding carries its scope on itself.</b> It was listed
	 * {@code inNamespace(namespace)}, so it is in the audited namespace by construction,
	 * and what it confers is full control of exactly that namespace — the audit of that
	 * namespace is the one place it is entirely relevant. Whether its <i>subject</i>
	 * belongs to a namespace decides nothing here: a {@code User}, or one of the open
	 * populations, belongs to none, and until #398 that dropped a RoleBinding in
	 * {@code app} granting {@code cluster-admin} to {@code system:authenticated} out of
	 * the audit of {@code app} while the cluster-wide view still showed it. The narrower
	 * view losing a finding is the wrong direction.
	 *
	 * <p>
	 * <b>A ClusterRoleBinding has no namespace of its own</b>, so its subject is the only
	 * thing that can put it in a namespaced scope, and the existing rule stands: an
	 * identity in that namespace, or {@code system:serviceaccounts:<ns>} naming it — the
	 * accounts it hands cluster-admin to are exactly the ones in scope. A User, a
	 * cluster-spanning {@code system:serviceaccounts}, or an authentication population
	 * (#387) belongs to no namespace, so a cluster-scoped grant to it belongs to the
	 * cluster-wide view only.
	 *
	 * <p>
	 * <b>This costs no extra request</b>, and must not: the namespaced audit still lists
	 * RoleBindings in one namespace. Listing them cluster-wide and filtering afterwards
	 * would answer a namespaced question with a cluster-wide read.
	 */
	private boolean outOfScope(Grant grant, String subjectNamespace, String namespace) {
		return grant.clusterScoped() && !namespace.equals(subjectNamespace);
	}

	/**
	 * The namespace a subject belongs to, or null if it belongs to no single one.
	 *
	 * <p>
	 * A ServiceAccount has one. So does {@code system:serviceaccounts:<ns>}.
	 * {@code system:serviceaccounts} spans the cluster, like a User — and so do the
	 * authentication populations (#387), which are everyone in the cluster and everyone
	 * outside it.
	 *
	 * <p>
	 * This answers "which namespace is this identity in", which is <b>only half</b> of
	 * whether a binding is in a namespaced scope — see {@link #outOfScope}. It is also
	 * what keys the pods-by-account join and what names a ServiceAccount in a finding, so
	 * null here means "no single namespace", never "not in scope".
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
		if (RbacSubjects.isAuthenticationPopulation(subject)) {
			return authenticationPopulationFinding(grant, subject);
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
	 * A grant made to an authentication outcome — {@code system:authenticated}, or the
	 * {@code system:unauthenticated} group and {@code system:anonymous} user (#387).
	 *
	 * <p>
	 * <b>Severity is the scope of what the binding confers</b>, the same axis every other
	 * finding here uses: a ClusterRoleBinding to {@code cluster-admin} is
	 * {@code critical} and a RoleBinding, which cannot reach past its own namespace, is a
	 * {@code warning}.
	 *
	 * <p>
	 * <b>Who holds it is not in the severity, and could not be.</b> These two cases are
	 * not equally bad — one needs a credential the cluster accepts and the other needs
	 * nothing but a route to the API server — but there is no severity above
	 * {@code critical} to put the second in, and demoting the first would file "every
	 * user and every workload in this cluster is an administrator" below a single
	 * account's namespaced grant. {@code info} is not an option: it is the bucket for
	 * what could not be checked. So the distinction is carried where it can be read — a
	 * separate static title per case, and a detail that names the population in words.
	 */
	private SecurityFinding authenticationPopulationFinding(Grant grant, Subject subject) {
		boolean open = RbacSubjects.isUnauthenticatedPopulation(subject);
		String who = open ? "any caller that can reach the API server, presenting no credential at all"
				: "every user and every ServiceAccount whose credential the cluster accepts";
		String confers = grant.clusterScoped() ? "administrator of the whole cluster"
				: "full control of namespace '" + grant.namespace() + "'";
		return new SecurityFinding(grant.clusterScoped() ? "critical" : "warning", populationTitle(grant, open),
				grant.ref(),
				"roleRef ClusterRole/" + RbacSubjects.CLUSTER_ADMIN + ", subject " + subject.getKind() + " "
						+ subject.getName() + " — " + who + " is " + confers,
				"Remove the binding, and grant only the identities that need it."
						+ " Membership of this subject is decided by the API server for every request,"
						+ " so the grant cannot be narrowed by editing users or accounts.");
	}

	/**
	 * Static per case, because the title is what a reader groups findings by and the
	 * order they sort in.
	 */
	private String populationTitle(Grant grant, boolean open) {
		if (!grant.clusterScoped()) {
			return open ? "RoleBinding grants cluster-admin to unauthenticated callers"
					: "RoleBinding grants cluster-admin to every authenticated identity";
		}
		return open ? "ClusterRoleBinding grants cluster-admin to unauthenticated callers"
				: "ClusterRoleBinding grants cluster-admin to every authenticated identity";
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
		// Scoped by the request, not filtered afterwards: a namespaced audit asks the API
		// server for one namespace's RoleBindings, which is what makes every RoleBinding
		// it
		// sees in scope by construction (#398).
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

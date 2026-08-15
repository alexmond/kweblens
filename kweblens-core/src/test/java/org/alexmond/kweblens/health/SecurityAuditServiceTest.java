package org.alexmond.kweblens.health;

import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.resource.WellKnownKinds;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The RBAC half of the audit, which is the half no single object can answer.
 *
 * <p>
 * Each rule is pinned with a decoy, because a check that has never rejected anything pins
 * nothing. The decoys here are the ones a plausible-but-wrong join would return: the
 * {@code cluster-admin} binding every cluster ships with, the rest of the control-plane
 * identities a correct cluster grants admin to, a binding to a different ClusterRole, an
 * account with the same name in another namespace, and a grant that no pod in the scope
 * uses.
 *
 * <p>
 * <b>A decoy is only worth what the code path it reaches.</b> Two of them here would pass
 * against a check that had been deleted if they were scoped to one namespace, because the
 * scope filter rejects them first — both say so, and both are cluster-wide for that
 * reason.
 *
 * <p>
 * Deliberately NOT static: a static mock client would share one API server across the
 * class, and bindings seeded by one test would be joined by the next.
 */
@EnableKubernetesMockClient(crud = true)
class SecurityAuditServiceTest {

	KubernetesClient client;

	KubernetesMockServer server;

	private SecurityAuditService service() {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register("mock", "mock", this.client);
		return new SecurityAuditService(registry, new ResourceService(registry));
	}

	private List<GenericKubernetesResource> pods(String namespace) {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register("mock", "mock", this.client);
		return new ResourceService(registry).listRaw("mock", WellKnownKinds.PODS, namespace);
	}

	private void pod(String namespace, String name, String account) {
		this.client.pods()
			.inNamespace(namespace)
			.resource(new PodBuilder().withNewMetadata()
				.withName(name)
				.withNamespace(namespace)
				.endMetadata()
				.withNewSpec()
				.withServiceAccountName(account)
				.endSpec()
				.build())
			.create();
	}

	private void clusterBinding(String name, String role, String subjectKind, String subjectNamespace,
			String subjectName) {
		this.client.rbac()
			.clusterRoleBindings()
			.resource(new ClusterRoleBindingBuilder().withNewMetadata()
				.withName(name)
				.endMetadata()
				.withNewRoleRef("rbac.authorization.k8s.io", "ClusterRole", role)
				.addNewSubject(null, subjectKind, subjectName, subjectNamespace)
				.build())
			.create();
	}

	private void roleBinding(String namespace, String name, String role, String account) {
		roleBindingTo(namespace, name, role, "ServiceAccount", namespace, account);
	}

	private void roleBindingTo(String namespace, String name, String role, String subjectKind, String subjectNamespace,
			String subjectName) {
		this.client.rbac()
			.roleBindings()
			.inNamespace(namespace)
			.resource(new RoleBindingBuilder().withNewMetadata()
				.withName(name)
				.withNamespace(namespace)
				.endMetadata()
				.withNewRoleRef("rbac.authorization.k8s.io", "ClusterRole", role)
				.addNewSubject(null, subjectKind, subjectName, subjectNamespace)
				.build())
			.create();
	}

	/** The built-in binding every cluster has, which must never be a finding. */
	private void seedBuiltIn() {
		clusterBinding("cluster-admin", "cluster-admin", "Group", null, "system:masters");
	}

	@Test
	void joinsAGrantToThePodsThatRunAsTheAccount() {
		seedBuiltIn();
		clusterBinding("ci-admin", "cluster-admin", "ServiceAccount", "app", "ci");
		pod("app", "runner-2", "ci");
		pod("app", "runner-1", "ci");
		// The decoy a name-only join would return: same account name, other namespace.
		// It only bites in a CLUSTER-WIDE audit — a namespaced one never sees the pod, so
		// scoping this test to 'app' would have passed against a join with no namespace
		// in it at all (measured: it did).
		pod("other", "impostor", "ci");

		List<SecurityFinding> findings = service().audit("mock", null, pods(null));

		assertThat(findings).filteredOn((f) -> "Workloads run as an identity with cluster-admin".equals(f.title()))
			.singleElement()
			.satisfies((f) -> {
				assertThat(f.object()).isEqualTo("ServiceAccount/app/ci");
				// Both halves of the join are named, so the reader can check the verdict
				// against two kubectl gets rather than trusting it.
				assertThat(f.detail()).contains("ClusterRoleBinding/ci-admin")
					.contains("2 pods run as it")
					.contains("runner-1, runner-2")
					.doesNotContain("impostor");
				assertThat(f.severity()).isEqualTo("critical");
			});
	}

	@Test
	void doesNotReportTheClusterAdminBindingEveryClusterShipsWith() {
		seedBuiltIn();

		assertThat(service().audit("mock", null, List.of())).isEmpty();
	}

	/**
	 * The decoy that keeps this check from becoming "report everything" (#382).
	 *
	 * <p>
	 * Every subject here is bound to {@code cluster-admin}, and every one is a
	 * control-plane identity a real cluster ships or issues — read off a live k3s
	 * cluster's ClusterRoleBindings. The audit must stay silent on all of them, or the
	 * same rows appear on every cluster and the reader learns to skip the check.
	 *
	 * <p>
	 * <b>Cluster-wide on purpose.</b> A Group belongs to no namespace, so a namespaced
	 * audit would drop these at the scope filter before the exemption was consulted, and
	 * this test would still pass with the exemption deleted — the vacuous-decoy trap this
	 * class has already been bitten by once. Measured: with {@code isControlPlaneSubject}
	 * returning false unconditionally, this fails with five findings.
	 *
	 * <p>
	 * The fifth subject used to be {@code system:authenticated}, which #387 established
	 * is <i>not</i> a control-plane identity. That assertion moved rather than went away
	 * — it is now {@code reportsAClusterAdminGrantToEveryAuthenticatedIdentity},
	 * asserting the opposite — and {@code system:monitoring} took its place here so this
	 * decoy still pins five subjects and still holds a Group that carries no namespace.
	 * Both are read off the same live k3s cluster's ClusterRoleBindings.
	 */
	@Test
	void doesNotReportTheControlPlaneIdentitiesACorrectClusterGrantsAdminTo() {
		seedBuiltIn();
		clusterBinding("nodes", "cluster-admin", "Group", null, "system:nodes");
		clusterBinding("monitoring", "cluster-admin", "Group", null, "system:monitoring");
		clusterBinding("scheduler", "cluster-admin", "User", null, "system:kube-scheduler");
		clusterBinding("kube-thing", "cluster-admin", "ServiceAccount", "kube-system", "controller");
		pod("app", "web-1", "default");

		assertThat(service().audit("mock", null, pods(null))).isEmpty();
	}

	/**
	 * {@code system:authenticated} is spelled like the control plane and is the opposite
	 * of a component: it is every user and every ServiceAccount that holds any credential
	 * the cluster accepts, so this grant makes all of them administrators (#387).
	 *
	 * <p>
	 * <b>Cluster-wide on purpose</b>, for the same reason as the decoy above: the subject
	 * carries no namespace, so a namespaced audit drops it at the scope filter and would
	 * pass against a check that never ran.
	 */
	@Test
	void reportsAClusterAdminGrantToEveryAuthenticatedIdentity() {
		seedBuiltIn();
		clusterBinding("everyone", "cluster-admin", "Group", null, "system:authenticated");
		pod("app", "web-1", "default");

		// Exactly one: the subject names its own blast radius, so there is no second
		// pod-listing finding — the answer is every pod, and every person, in the
		// cluster.
		assertThat(service().audit("mock", null, pods(null))).singleElement().satisfies((f) -> {
			assertThat(f.title()).isEqualTo("ClusterRoleBinding grants cluster-admin to every authenticated identity");
			assertThat(f.object()).isEqualTo("ClusterRoleBinding/everyone");
			assertThat(f.severity()).isEqualTo("critical");
			assertThat(f.detail()).contains("Group system:authenticated")
				.contains("every user and every ServiceAccount whose credential the cluster accepts")
				.contains("administrator of the whole cluster");
		});
	}

	/**
	 * The two spellings of "nobody in particular". {@code system:unauthenticated} is the
	 * group a request that failed to authenticate lands in; {@code system:anonymous} is
	 * the username it is given. Either one bound to {@code cluster-admin} hands the
	 * cluster to whoever can open a connection to the API server.
	 */
	@Test
	void reportsAClusterAdminGrantThatNeedsNoCredentialAtAll() {
		seedBuiltIn();
		clusterBinding("open-door", "cluster-admin", "Group", null, "system:unauthenticated");
		clusterBinding("nobody", "cluster-admin", "User", null, "system:anonymous");
		// The decoy: 'system:anonymous' is a User, so a binding that names it as a Group
		// grants nothing at all. Reporting it would be a finding about a grant that does
		// not exist, so it falls back to the reserved-prefix rule and stays silent — the
		// same treatment 'system:serviceaccounts:' with no namespace after it gets.
		clusterBinding("mis-kinded", "cluster-admin", "Group", null, "system:anonymous");
		pod("app", "web-1", "default");

		List<SecurityFinding> findings = service().audit("mock", null, pods(null));

		assertThat(findings).extracting(SecurityFinding::object)
			.containsExactly("ClusterRoleBinding/nobody", "ClusterRoleBinding/open-door");
		assertThat(findings).allSatisfy((f) -> {
			assertThat(f.severity()).isEqualTo("critical");
			assertThat(f.title()).isEqualTo("ClusterRoleBinding grants cluster-admin to unauthenticated callers");
			assertThat(f.detail()).contains("presenting no credential at all")
				.contains("administrator of the whole cluster");
		});
	}

	/**
	 * A RoleBinding confines the grant to its own namespace, so it is the same warning
	 * any other namespaced cluster-admin grant gets — the population makes it wider, not
	 * deeper, and severity here has always meant what the binding confers.
	 *
	 * <p>
	 * <b>The last assertion is the one #387 pinned the wrong way round, flipped
	 * (#398).</b> It used to assert the audit of {@code app} was EMPTY: the subject
	 * carries no namespace, so the scope filter dropped the binding from the audit of the
	 * one namespace it is entirely about, while the cluster-wide view still showed it. A
	 * RoleBinding is judged by its own namespace now, so both scopes report it — and this
	 * test is where that is proved, because a Group carrying no namespace is exactly the
	 * shape the old filter discarded. Restore the old rule and this assertion fails
	 * (measured), which is what says it reaches the filter rather than passing around it.
	 */
	@Test
	void reportsANamespacedGrantToAnAuthenticationPopulationAsTheNarrowerThingItIs() {
		roleBindingTo("app", "ns-everyone", "cluster-admin", "Group", null, "system:authenticated");

		assertThat(service().audit("mock", null, pods(null))).singleElement().satisfies((f) -> {
			assertThat(f.severity()).isEqualTo("warning");
			assertThat(f.title()).isEqualTo("RoleBinding grants cluster-admin to every authenticated identity");
			assertThat(f.object()).isEqualTo("RoleBinding/app/ns-everyone");
			assertThat(f.detail()).contains("full control of namespace 'app'");
		});
		assertThat(service().audit("mock", "app", pods("app"))).singleElement().satisfies((f) -> {
			assertThat(f.severity()).isEqualTo("warning");
			assertThat(f.title()).isEqualTo("RoleBinding grants cluster-admin to every authenticated identity");
			assertThat(f.object()).isEqualTo("RoleBinding/app/ns-everyone");
			assertThat(f.detail()).contains("full control of namespace 'app'");
		});
	}

	/**
	 * The defect #398 names, in its plainest form: an operator auditing {@code app} was
	 * not shown a RoleBinding <b>in {@code app}</b> handing {@code cluster-admin} to a
	 * User. Pre-existing for Users since long before #382 — the recent population work
	 * only made it visible.
	 *
	 * <p>
	 * <b>The boundary the fix must not blow through</b> is the second binding: the same
	 * shape, the same namespace-less subject, in a namespace the audit did not ask about.
	 * It is excluded by the list request itself, which is the point — listing
	 * RoleBindings cluster-wide and filtering afterwards would answer a namespaced
	 * question with a cluster-wide read, and this decoy is what fails when someone does
	 * (measured: making {@code grants()} list {@code inAnyNamespace()} unconditionally
	 * fails here and nowhere else, since the request count stays at two).
	 */
	@Test
	void reportsARoleBindingInTheAuditedNamespaceWhateverKindOfSubjectItNames() {
		roleBindingTo("app", "alice-admin", "cluster-admin", "User", null, "alice");
		roleBindingTo("other", "bob-admin", "cluster-admin", "User", null, "bob");
		pod("app", "web-1", "default");

		assertThat(service().audit("mock", "app", pods("app"))).singleElement().satisfies((f) -> {
			assertThat(f.severity()).isEqualTo("warning");
			assertThat(f.title()).isEqualTo("RoleBinding grants cluster-admin in one namespace");
			assertThat(f.object()).isEqualTo("RoleBinding/app/alice-admin");
			assertThat(f.detail()).contains("subject User alice").contains("full control of namespace 'app'");
		});
		// Neither ever belonged to the cluster-wide view ALONE; both are still in it.
		assertThat(service().audit("mock", null, pods(null))).extracting(SecurityFinding::object)
			.containsExactly("RoleBinding/app/alice-admin", "RoleBinding/other/bob-admin");
	}

	/**
	 * The half the old rule got right, kept: a ClusterRoleBinding has no namespace of its
	 * own, so only its subject can put it in a namespaced scope. A User and an
	 * authentication population belong to none, so both stay in the cluster-wide view —
	 * and the empty namespaced audit is the decoy that proves the filter still bites.
	 */
	@Test
	void leavesAClusterScopedGrantToANamespacelessSubjectOutOfANamespacedAudit() {
		clusterBinding("alice-admin", "cluster-admin", "User", null, "alice");
		clusterBinding("everyone", "cluster-admin", "Group", null, "system:authenticated");
		pod("app", "web-1", "default");

		assertThat(service().audit("mock", "app", pods("app"))).isEmpty();
		assertThat(service().audit("mock", null, pods(null))).extracting(SecurityFinding::object)
			.containsExactly("ClusterRoleBinding/alice-admin", "ClusterRoleBinding/everyone");
	}

	/**
	 * The group of every ServiceAccount in the cluster is spelled like a control-plane
	 * identity and is not one: it is every workload an operator deploys, so this grant
	 * makes each of them administrator of everything.
	 */
	@Test
	void reportsAClusterAdminGrantToEveryServiceAccountInTheCluster() {
		seedBuiltIn();
		clusterBinding("everything", "cluster-admin", "Group", null, "system:serviceaccounts");
		pod("app", "web-1", "default");

		List<SecurityFinding> findings = service().audit("mock", null, pods(null));

		// Exactly one: the binding names the blast radius itself, so there is no second
		// finding listing pods — the answer would be "all of them, plus tomorrow's".
		assertThat(findings).singleElement().satisfies((f) -> {
			assertThat(f.title())
				.isEqualTo("ClusterRoleBinding grants cluster-admin to every ServiceAccount in the cluster");
			assertThat(f.object()).isEqualTo("ClusterRoleBinding/everything");
			assertThat(f.severity()).isEqualTo("critical");
			assertThat(f.detail()).contains("system:serviceaccounts").contains("every ServiceAccount in the cluster");
		});
	}

	/** The same shape, one namespace at a time — and still the whole cluster granted. */
	@Test
	void reportsAClusterAdminGrantToEveryServiceAccountInOneNamespace() {
		clusterBinding("app-everything", "cluster-admin", "Group", null, "system:serviceaccounts:app");
		pod("app", "web-1", "default");

		// The namespaced audit reaches this one: the group names its namespace, so the
		// accounts it hands cluster-admin to are exactly the ones in scope. The 'other'
		// scope below is the control that proves the filter still bites — without it,
		// scoping alone would prove nothing.
		assertThat(service().audit("mock", "app", pods("app"))).singleElement().satisfies((f) -> {
			assertThat(f.title())
				.isEqualTo("ClusterRoleBinding grants cluster-admin to every ServiceAccount in a namespace");
			assertThat(f.object()).isEqualTo("ClusterRoleBinding/app-everything");
			assertThat(f.severity()).isEqualTo("critical");
			assertThat(f.detail()).contains("every ServiceAccount in namespace 'app'")
				.contains("administrator of the whole cluster");
		});
		assertThat(service().audit("mock", "other", pods("other"))).isEmpty();
		assertThat(service().audit("mock", null, pods(null))).extracting(SecurityFinding::object)
			.containsExactly("ClusterRoleBinding/app-everything");
	}

	/**
	 * A RoleBinding confines the grant to its own namespace, so it is the same warning a
	 * single account's namespaced grant gets — the group makes it wider, not deeper.
	 */
	@Test
	void reportsAGroupGrantConfinedToOneNamespaceAsTheNarrowerThingItIs() {
		roleBindingTo("app", "ns-everything", "cluster-admin", "Group", null, "system:serviceaccounts:app");

		assertThat(service().audit("mock", "app", pods("app"))).singleElement().satisfies((f) -> {
			assertThat(f.severity()).isEqualTo("warning");
			assertThat(f.title()).isEqualTo("RoleBinding grants cluster-admin to a whole group of ServiceAccounts");
			assertThat(f.object()).isEqualTo("RoleBinding/app/ns-everything");
			assertThat(f.detail()).contains("full control of namespace 'app'");
		});
	}

	@Test
	void doesNotReportABindingToADifferentClusterRole() {
		clusterBinding("readers", "view", "ServiceAccount", "app", "viewer");
		pod("app", "reader-1", "viewer");

		assertThat(service().audit("mock", "app", pods("app"))).isEmpty();
	}

	@Test
	void reportsTheGrantWithoutTheWorkloadFindingWhenNothingRunsAsIt() {
		clusterBinding("build-admin", "cluster-admin", "ServiceAccount", "app", "builder");
		pod("app", "web-1", "default");

		List<SecurityFinding> findings = service().audit("mock", "app", pods("app"));

		assertThat(findings).extracting(SecurityFinding::title)
			.containsExactly("ClusterRoleBinding grants cluster-admin");
		assertThat(findings.get(0).object()).isEqualTo("ClusterRoleBinding/build-admin");
	}

	@Test
	void leavesGrantsToOtherNamespacesOutOfANamespacedAudit() {
		clusterBinding("robot-admin", "cluster-admin", "ServiceAccount", "other", "robot");

		assertThat(service().audit("mock", "app", pods("app"))).isEmpty();
		assertThat(service().audit("mock", null, pods(null))).extracting(SecurityFinding::object)
			.contains("ClusterRoleBinding/robot-admin");
	}

	@Test
	void doesNotReportAccountsTheClusterOwns() {
		clusterBinding("kube-thing", "cluster-admin", "ServiceAccount", "kube-system", "controller");

		assertThat(service().audit("mock", null, pods(null))).isEmpty();
	}

	@Test
	void reportsANamespacedClusterAdminGrantAsTheNarrowerThingItIs() {
		roleBinding("app", "ns-admin", "cluster-admin", "deployer");
		pod("app", "deploy-1", "deployer");

		List<SecurityFinding> findings = service().audit("mock", "app", pods("app"));

		assertThat(findings).extracting(SecurityFinding::severity).containsOnly("warning");
		assertThat(findings).extracting(SecurityFinding::object)
			.containsExactlyInAnyOrder("RoleBinding/app/ns-admin", "ServiceAccount/app/deployer");
		assertThat(findings).filteredOn((f) -> f.object().startsWith("RoleBinding/"))
			.singleElement()
			.satisfies((f) -> assertThat(f.detail()).contains("full control of namespace 'app'"));
	}

	@Test
	void costsTwoRequestsWhateverTheScopeHolds() {
		clusterBinding("ci-admin", "cluster-admin", "ServiceAccount", "app", "ci");
		for (int i = 0; i < 12; i++) {
			pod("app", "runner-" + i, "ci");
		}
		List<GenericKubernetesResource> pods = pods("app");

		int before = this.server.getRequestCount();
		service().audit("mock", "app", pods);
		int spent = this.server.getRequestCount() - before;

		// One RoleBinding list and one ClusterRoleBinding list, for the whole scope —
		// twelve pods, no per-pod lookup. Walking the grantedBy relation from each pod
		// would have been two lists each.
		assertThat(spent).isEqualTo(2);
	}

	@Test
	void ordersFindingsTheSameWayOnAnUnchangedCluster() {
		clusterBinding("ci-admin", "cluster-admin", "ServiceAccount", "app", "ci");
		clusterBinding("build-admin", "cluster-admin", "ServiceAccount", "app", "builder");
		pod("app", "runner-1", "ci");

		assertThat(service().audit("mock", "app", pods("app"))).isEqualTo(service().audit("mock", "app", pods("app")));
	}

	@Test
	void saysSoWhenTheGrantsCouldNotBeRead() {
		ClusterRegistry registry = new ClusterRegistry();
		SecurityAuditService service = new SecurityAuditService(registry, new ResourceService(registry));

		// No cluster registered: the scan cannot run, and reporting nothing would read as
		// "no over-privileged identities", which is the dangerous direction.
		assertThat(service.audit("missing", "app", List.of())).singleElement()
			.satisfies((f) -> assertThat(f.title()).isEqualTo("RBAC grants could not be read"));
	}

}

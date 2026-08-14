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
 * {@code cluster-admin} binding every cluster ships with, a binding to a different
 * ClusterRole, an account with the same name in another namespace, and a grant that no
 * pod in the scope uses.
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
		this.client.rbac()
			.roleBindings()
			.inNamespace(namespace)
			.resource(new RoleBindingBuilder().withNewMetadata()
				.withName(name)
				.withNamespace(namespace)
				.endMetadata()
				.withNewRoleRef("rbac.authorization.k8s.io", "ClusterRole", role)
				.addNewSubject(null, "ServiceAccount", account, namespace)
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

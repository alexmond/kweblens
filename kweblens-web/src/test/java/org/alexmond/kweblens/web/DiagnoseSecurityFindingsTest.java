package org.alexmond.kweblens.web;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security dimension on the deterministic GET path.
 *
 * <p>
 * The model is switched off explicitly here, because the whole claim is that these
 * findings are computed rather than written: {@code aiEnriched} stays false and the
 * findings are still there.
 *
 * <p>
 * Two things this pins that are easy to lose. <b>The order is stable</b> — the diagnosis
 * keys its summary cache on a SHA-256 of the finding list, so a list that reordered
 * itself between two reads of an unchanged cluster would throw the cached summary away
 * every time. And <b>the cost does not follow the pod count</b>: the security checks read
 * the pod list the diagnosis already had.
 */
@SpringBootTest(properties = { "kweblens.load-kubeconfig=false", "kweblens.ai.enabled=false" })
@EnableKubernetesMockClient(crud = true)
class DiagnoseSecurityFindingsTest {

	KubernetesClient client;

	KubernetesMockServer server;

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private ClusterRegistry registry;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).build();
		this.registry.register("test", "Test cluster", this.client);
		this.client.namespaces()
			.resource(new NamespaceBuilder().withNewMetadata().withName("web").endMetadata().build())
			.create();
		// The grant, and the two pods that make it matter.
		this.client.rbac()
			.clusterRoleBindings()
			.resource(new ClusterRoleBindingBuilder().withNewMetadata()
				.withName("ci-admin")
				.endMetadata()
				.withNewRoleRef("rbac.authorization.k8s.io", "ClusterRole", "cluster-admin")
				.addNewSubject(null, "ServiceAccount", "ci", "web")
				.build())
			.create();
		// The built-in binding every cluster ships with must stay silent.
		this.client.rbac()
			.clusterRoleBindings()
			.resource(new ClusterRoleBindingBuilder().withNewMetadata()
				.withName("cluster-admin")
				.endMetadata()
				.withNewRoleRef("rbac.authorization.k8s.io", "ClusterRole", "cluster-admin")
				.addNewSubject(null, "Group", "system:masters", null)
				.build())
			.create();
		pod("runner", "ci", true);
		// The decoy: an ordinary pod on an ungranted account, claiming no privilege.
		pod("web-1", "default", false);
	}

	/**
	 * The coverage signal is a claim about the LIST, so it rides beside the findings
	 * rather than inside them (#388).
	 *
	 * <p>
	 * <b>The control comes first.</b> This scope has three findings and was fully
	 * checked, so {@code incomplete} must be empty — a notice that appears on every
	 * diagnosis stops meaning anything, and the panel builds its "not fully checked" line
	 * from exactly this field.
	 */
	@Test
	void saysNothingAboutCoverageWhenTheAuditSawEverything() throws Exception {
		this.mvc.perform(get("/api/v1/clusters/test/diagnose").param("namespace", "web"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.findings.length()").value(3))
			.andExpect(jsonPath("$.incomplete").isEmpty());
	}

	/**
	 * And the container cap, on the wire, from the code that capped.
	 *
	 * <p>
	 * The 21 pods are seeded past {@code MAX_CONTAINER_FINDINGS} on purpose; that
	 * threshold is a readability judgement nobody has measured against a real cluster,
	 * and this asserts only what happens once it bites.
	 */
	@Test
	void reportsTheContainerCapAsCoverageAndStillCostsEightRequests() throws Exception {
		for (int i = 0; i < 21; i++) {
			pod("agent-" + i, "default", true);
		}

		int before = this.server.getRequestCount();
		this.mvc.perform(get("/api/v1/clusters/test/diagnose").param("namespace", "web"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.incomplete.length()").value(1))
			.andExpect(jsonPath("$.incomplete[0].dimension").value("container privileges"))
			.andExpect(jsonPath("$.incomplete[0].reason").value(containsString("not listed")))
			// The finding stays where it was: the summary is what was missing, not the
			// detail.
			.andExpect(jsonPath("$.findings[?(@.title=='Further container privileges not listed')]").exists());

		// The signal is computed inside checks that were already running, so it buys no
		// round trip — same eight as the namespaced diagnosis has always cost.
		assertThat(this.server.getRequestCount() - before).isEqualTo(8);
	}

	private void pod(String name, String account, boolean privileged) {
		this.client.pods()
			.inNamespace("web")
			.resource(new PodBuilder().withNewMetadata()
				.withName(name)
				.withNamespace("web")
				.endMetadata()
				.withNewSpec()
				.withServiceAccountName(account)
				.addNewContainer()
				.withName("app")
				.withNewSecurityContext()
				.withPrivileged(privileged)
				.endSecurityContext()
				.endContainer()
				.endSpec()
				.withNewStatus()
				.withPhase("Running")
				.endStatus()
				.build())
			.create();
	}

	@Test
	void reportsTheCrossManifestGrantWithoutAModel() throws Exception {
		this.mvc.perform(get("/api/v1/clusters/test/diagnose").param("namespace", "web"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.aiEnriched").value(false))
			.andExpect(jsonPath("$.findings[?(@.object=='ServiceAccount/web/ci')].title")
				.value("Workloads run as an identity with cluster-admin"))
			// Both joined objects are named in the evidence, so the reader can check it.
			.andExpect(jsonPath("$.findings[?(@.object=='ServiceAccount/web/ci')].detail")
				.value(hasItem(allOf(containsString("ClusterRoleBinding/ci-admin"), containsString("runner")))))
			.andExpect(jsonPath("$.findings[?(@.object=='Pod/runner container app')].title")
				.value("Container runs privileged"))
			// The decoy pod and the built-in binding produce nothing.
			.andExpect(jsonPath("$.findings[?(@.object=='Pod/web-1 container app')]").doesNotExist())
			.andExpect(jsonPath("$.findings[?(@.object=='ClusterRoleBinding/cluster-admin')]").doesNotExist());
	}

	@Test
	void returnsTheSameFindingsInTheSameOrderOnAnUnchangedCluster() throws Exception {
		String first = this.mvc.perform(get("/api/v1/clusters/test/diagnose").param("namespace", "web"))
			.andExpect(status().isOk())
			// Not vacuous: the grant, the workloads running with it, and the privileged
			// container are three findings to keep in order.
			.andExpect(jsonPath("$.findings.length()").value(3))
			.andReturn()
			.getResponse()
			.getContentAsString();

		this.mvc.perform(get("/api/v1/clusters/test/diagnose").param("namespace", "web"))
			.andExpect(status().isOk())
			.andExpect(content().json(first, true));
	}

	@Test
	void costsTheSameWhateverThePodCountIs() throws Exception {
		int before = this.server.getRequestCount();
		this.mvc.perform(get("/api/v1/clusters/test/diagnose").param("namespace", "web")).andExpect(status().isOk());
		int withTwoPods = this.server.getRequestCount() - before;

		for (int i = 0; i < 6; i++) {
			pod("extra-" + i, "ci", true);
		}
		int seeded = this.server.getRequestCount();
		this.mvc.perform(get("/api/v1/clusters/test/diagnose").param("namespace", "web")).andExpect(status().isOk());
		int withEightPods = this.server.getRequestCount() - seeded;

		// The security dimension adds two list requests for the whole scope — the
		// RoleBindings and the ClusterRoleBindings — and nothing per pod. Quadrupling
		// the pods must not move this number at all.
		assertThat(withEightPods).isEqualTo(withTwoPods);
		// Measured from the mock server's request log, and pinned so a new round trip has
		// to be argued for. Eight for the whole namespace-scoped diagnosis: pods,
		// services, endpoints, claims, events, a cluster-wide services list (Prometheus
		// discovery, which predates this), and the two binding lists added here.
		assertThat(withTwoPods).isEqualTo(8);
	}

}

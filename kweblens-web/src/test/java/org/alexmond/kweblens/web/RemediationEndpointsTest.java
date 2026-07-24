package org.alexmond.kweblens.web;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI-assisted remediation. Verifies proposals are derived from findings, and the safety
 * gate: applying without {@code confirm=true} is rejected (never autonomous). Security
 * (auth for the write) is covered by SecurityGateTest; this MockMvc omits it to exercise
 * the confirm gate directly.
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
@EnableKubernetesMockClient(crud = true)
class RemediationEndpointsTest {

	KubernetesClient client;

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private ClusterRegistry registry;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(context).build();
		registry.register("test", "Test cluster", client);
		client.namespaces()
			.resource(new NamespaceBuilder().withNewMetadata().withName("web").endMetadata().build())
			.create();
		client.pods()
			.resource(new PodBuilder().withNewMetadata()
				.withName("bad")
				.withNamespace("web")
				.endMetadata()
				.withNewStatus()
				.withPhase("Pending")
				.addNewContainerStatus()
				.withName("c1")
				.withNewState()
				.withNewWaiting()
				.withReason("CrashLoopBackOff")
				.endWaiting()
				.endState()
				.endContainerStatus()
				.endStatus()
				.build())
			.create();
	}

	@Test
	void proposesRestartForCrashLoopingPod() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/remediations").param("namespace", "web"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.action=='restart-pod')].target").value(org.hamcrest.Matchers.hasItem("bad")));
	}

	@Test
	void applyWithoutConfirmationIsRejected() throws Exception {
		mvc.perform(post("/api/v1/clusters/test/remediations/apply").param("namespace", "web")
			.param("action", "restart-pod")
			.param("target", "bad")).andExpect(status().isBadRequest());
	}

	@Test
	void applyWithConfirmationRestartsThePod() throws Exception {
		mvc.perform(post("/api/v1/clusters/test/remediations/apply").param("namespace", "web")
			.param("action", "restart-pod")
			.param("target", "bad")
			.param("confirm", "true"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.containsString("Deleted")));
	}

}

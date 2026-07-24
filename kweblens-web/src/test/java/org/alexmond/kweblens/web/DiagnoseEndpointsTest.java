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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI diagnosis — the deterministic validator path (AI disabled by default). A
 * CrashLoopBackOff pod must surface as a critical finding without any LLM.
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
@EnableKubernetesMockClient(crud = true)
class DiagnoseEndpointsTest {

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
				.withMessage("back-off restarting failed container")
				.endWaiting()
				.endState()
				.endContainerStatus()
				.endStatus()
				.build())
			.create();
	}

	@Test
	void diagnoseFindsCrashLoopBackOffWithoutAi() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/diagnose").param("namespace", "web"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.aiEnriched").value(false))
			.andExpect(jsonPath("$.findings[?(@.title=='CrashLoopBackOff')]").exists())
			.andExpect(jsonPath("$.findings[?(@.severity=='critical')]").exists());
	}

}

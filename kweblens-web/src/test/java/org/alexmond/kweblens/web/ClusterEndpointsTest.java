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
import org.alexmond.kweblens.web.mcp.ClusterTools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
@EnableKubernetesMockClient(crud = true)
class ClusterEndpointsTest {

	// Non-static: the fabric8 mock extension gives each test a fresh CRUD server +
	// client,
	// so tests don't share seeded state and the shared ClusterRegistry bean sees a live
	// client.
	KubernetesClient client;

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private ClusterRegistry registry;

	@Autowired
	private ClusterTools tools;

	private MockMvc mvc;

	@BeforeEach
	void seed() {
		mvc = MockMvcBuilders.webAppContextSetup(context).build();
		registry.register("test", "Test cluster", client);
		client.namespaces()
			.resource(new NamespaceBuilder().withNewMetadata().withName("web").endMetadata().build())
			.create();
		client.pods()
			.resource(new PodBuilder().withNewMetadata()
				.withName("nginx")
				.withNamespace("web")
				.endMetadata()
				.withNewStatus()
				.withPhase("Running")
				.endStatus()
				.build())
			.create();
	}

	@Test
	void apiListsClusters() throws Exception {
		mvc.perform(get("/api/v1/clusters")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("test"));
	}

	@Test
	void apiListsPods() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/pods").param("namespace", "web"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].name").value("nginx"))
			.andExpect(jsonPath("$[0].kind").value("Pod"));
	}

	@Test
	void apiReturns404ForUnknownCluster() throws Exception {
		mvc.perform(get("/api/v1/clusters/ghost/namespaces"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("unknown-cluster"));
	}

	@Test
	void dashboardHomeRendersClustersView() throws Exception {
		mvc.perform(get("/"))
			.andExpect(status().isOk())
			.andExpect(view().name("clusters"))
			.andExpect(model().attributeExists("clusters"));
	}

	@Test
	void dashboardPodsPageRendersResourcesView() throws Exception {
		mvc.perform(get("/clusters/test/pods"))
			.andExpect(status().isOk())
			.andExpect(view().name("resources"))
			.andExpect(model().attribute("kind", "Pods"));
	}

	@Test
	void mcpToolsProjectTheSameData() {
		assertThat(tools.listClusters()).extracting("id").contains("test");
		assertThat(tools.listNamespaces("test")).extracting("name").contains("web");
	}

}

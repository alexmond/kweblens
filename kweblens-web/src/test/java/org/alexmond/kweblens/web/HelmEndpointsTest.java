package org.alexmond.kweblens.web;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Helm HTTP endpoints against a mock cluster with <b>no seeded resources</b>. (The crud
 * mock leaks other seeded kinds into an all-namespaces Secret list, so this stays
 * isolated; the real jhelm read path is covered by
 * {@link org.alexmond.kweblens.web.helm.HelmServiceTest}.)
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
@EnableKubernetesMockClient(crud = true)
class HelmEndpointsTest {

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
	}

	@Test
	void apiListsHelmReleasesEmpty() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/helm/releases").param("namespace", "default"))
			.andExpect(status().isOk())
			.andExpect(content().json("[]"));
	}

	@Test
	void dashboardHelmPageRenders() throws Exception {
		mvc.perform(get("/clusters/test/helm").param("namespace", "default"))
			.andExpect(status().isOk())
			.andExpect(view().name("helm"))
			.andExpect(model().attribute("selectedId", "helm"))
			.andExpect(model().attributeExists("releases"));
	}

}

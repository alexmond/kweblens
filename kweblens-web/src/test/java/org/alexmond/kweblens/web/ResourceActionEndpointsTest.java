package org.alexmond.kweblens.web;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
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
 * Mutating resource actions. Security (auth for the write) is covered by
 * SecurityGateTest; this MockMvc omits it to exercise the action logic. Delete goes
 * through the generic path.
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
@EnableKubernetesMockClient(crud = true)
class ResourceActionEndpointsTest {

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
		client.configMaps()
			.resource(new ConfigMapBuilder().withNewMetadata()
				.withName("doomed")
				.withNamespace("default")
				.endMetadata()
				.build())
			.create();
	}

	@Test
	void deleteRemovesTheResource() throws Exception {
		mvc.perform(post("/api/v1/clusters/test/resources/configmaps/default/doomed/delete"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.containsString("deleted")));

		mvc.perform(get("/api/v1/clusters/test/yaml").param("resource", "configmaps")
			.param("namespace", "default")
			.param("name", "doomed")).andExpect(status().isNotFound());
	}

}

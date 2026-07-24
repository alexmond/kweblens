package org.alexmond.kweblens.web;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.hamcrest.Matchers;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The raw-objects API that drives the SPA's kind-specific columns: it returns full
 * Kubernetes objects (metadata/spec/status), not the summary projection.
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
@EnableKubernetesMockClient(crud = true)
class ObjectEndpointsTest {

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
				.withName("cm1")
				.withNamespace("default")
				.endMetadata()
				.addToData("k", "v")
				.build())
			.create();
	}

	@Test
	void objectsReturnsFullKubernetesObjects() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/resources/configmaps/objects").param("namespace", "default"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].kind").value("ConfigMap"))
			.andExpect(jsonPath("$[0].metadata.name").value("cm1"))
			.andExpect(content().string(Matchers.containsString("\"data\"")));
	}

	@Test
	void objectsIs404ForUnknownKind() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/resources/not-a-kind/objects")).andExpect(status().isNotFound());
	}

}

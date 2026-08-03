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
				.addToData("k", "only-in-the-single-object-payload")
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

	/**
	 * The list is projected (GH#276) — the keys are there so the Keys column still counts
	 * them, the values are not. Asserted through the controller and not only on
	 * {@code ListProjection}, because the wiring is the half that can be forgotten.
	 */
	@Test
	void objectsShipsConfigMapKeysWithoutTheirValues() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/resources/configmaps/objects").param("namespace", "default"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].data.k").value(Matchers.nullValue()))
			.andExpect(content().string(Matchers.containsString("\"k\"")))
			.andExpect(content().string(Matchers.not(Matchers.containsString("only-in-the-single-object-payload"))));
	}

	/** ...and the single-object endpoint the drawer calls still carries them. */
	@Test
	void objectReturnsTheValuesTheListDropped() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/resources/configmaps/object").param("namespace", "default")
			.param("name", "cm1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.k").value("only-in-the-single-object-payload"));
	}

	@Test
	void objectsIs404ForUnknownKind() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/resources/not-a-kind/objects")).andExpect(status().isNotFound());
	}

}

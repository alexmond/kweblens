package org.alexmond.kweblens.web;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeBuilder;
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
	void deleteRemovesAClusterScopedResourceAddressedWithTheNoNamespaceSegment() throws Exception {
		// A Node / PersistentVolume / ClusterRole has no namespace to put in the path, so
		// the UI sends `_` (#297). An EMPTY segment cannot be used — see the test below.
		client.persistentVolumes()
			.resource(new PersistentVolumeBuilder().withNewMetadata().withName("doomed-pv").endMetadata().build())
			.create();

		mvc.perform(post("/api/v1/clusters/test/resources/persistentvolumes/_/doomed-pv/delete"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.containsString("deleted")));

		mvc.perform(get("/api/v1/clusters/test/yaml").param("resource", "persistentvolumes").param("name", "doomed-pv"))
			.andExpect(status().isNotFound());
	}

	@Test
	void anEmptyNamespaceSegmentDoesNotMatchTheMapping() throws Exception {
		// Why the placeholder exists at all: `…/persistentvolumes//pv/delete` is not a
		// missing namespace to Spring, it is a URL with no handler. Sending one is a
		// silent
		// no-op dressed as a request, which is what #297 was.
		client.persistentVolumes()
			.resource(new PersistentVolumeBuilder().withNewMetadata().withName("survivor-pv").endMetadata().build())
			.create();

		mvc.perform(post("/api/v1/clusters/test/resources/persistentvolumes//survivor-pv/delete"))
			.andExpect(status().isNotFound());

		mvc.perform(
				get("/api/v1/clusters/test/yaml").param("resource", "persistentvolumes").param("name", "survivor-pv"))
			.andExpect(status().isOk());
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

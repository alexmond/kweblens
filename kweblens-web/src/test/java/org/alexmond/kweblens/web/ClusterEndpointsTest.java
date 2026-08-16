package org.alexmond.kweblens.web;

import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
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
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
		client.apps()
			.deployments()
			.resource(new DeploymentBuilder().withNewMetadata()
				.withName("api")
				.withNamespace("web")
				.endMetadata()
				.build())
			.create();
		client
			.genericKubernetesResources(new ResourceDefinitionContext.Builder().withGroup("")
				.withVersion("v1")
				.withKind("Event")
				.withPlural("events")
				.withNamespaced(true)
				.build())
			.inNamespace("web")
			.resource(new GenericKubernetesResourceBuilder().withApiVersion("v1")
				.withKind("Event")
				.withNewMetadata()
				.withName("evt1")
				.withNamespace("web")
				.endMetadata()
				.addToAdditionalProperties("type", "Warning")
				.addToAdditionalProperties("reason", "BackOff")
				.addToAdditionalProperties("message", "Back-off restarting")
				.addToAdditionalProperties("involvedObject", Map.of("kind", "Pod", "name", "nginx"))
				.addToAdditionalProperties("lastTimestamp", "2024-06-01T12:00:00Z")
				.build())
			.create();
	}

	@Test
	void apiListsClusters() throws Exception {
		// Contains, not "is first". The ClusterRegistry is one bean shared by every test
		// class in this Spring context and `list()` sorts by id, so which cluster is at
		// index 0 depends on what some other class registered and on the order surefire
		// happened to run them in. This asserted `$[0].id` and passed only because no
		// test
		// class sorting before this one had registered an id sorting before "test".
		mvc.perform(get("/api/v1/clusters"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$..id").value(hasItem("test")));
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
	void apiListsResourcesGenerically() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/resources/deployments").param("namespace", "web"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].name").value("api"))
			.andExpect(jsonPath("$[0].kind").value("Deployment"));
	}

	@Test
	void apiReturns404ForUnknownResourceKind() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/resources/widgets")).andExpect(status().isNotFound());
	}

	@Test
	void apiListsEvents() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/events").param("namespace", "web"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].reason").value("BackOff"))
			.andExpect(jsonPath("$[0].object").value("Pod/nginx"));
	}

	@Test
	void apiMetricsDegradesToEmptyWhenAbsent() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/metrics/nodes"))
			.andExpect(status().isOk())
			.andExpect(content().json("[]"));
	}

	@Test
	void mcpToolsProjectTheSameData() {
		assertThat(tools.listClusters()).extracting("id").contains("test");
		assertThat(tools.listNamespaces("test")).extracting("name").contains("web");
	}

}

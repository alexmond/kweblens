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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
	void dashboardResourcePageRendersShell() throws Exception {
		mvc.perform(get("/clusters/test/r/pods"))
			.andExpect(status().isOk())
			.andExpect(view().name("resource"))
			.andExpect(model().attribute("selectedId", "pods"))
			.andExpect(model().attributeExists("categories", "descriptor", "rows"));
	}

	@Test
	void dashboardClusterRootRedirects() throws Exception {
		mvc.perform(get("/clusters/test")).andExpect(status().is3xxRedirection());
	}

	@Test
	void apiListsEvents() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/events").param("namespace", "web"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].reason").value("BackOff"))
			.andExpect(jsonPath("$[0].object").value("Pod/nginx"));
	}

	@Test
	void dashboardEventsPageRenders() throws Exception {
		mvc.perform(get("/clusters/test/events"))
			.andExpect(status().isOk())
			.andExpect(view().name("events"))
			.andExpect(model().attribute("selectedId", "events"))
			.andExpect(model().attributeExists("events", "categories"));
	}

	@Test
	void dashboardLogsPageRenders() throws Exception {
		mvc.perform(get("/clusters/test/pods/web/nginx/logs"))
			.andExpect(status().isOk())
			.andExpect(view().name("logs"))
			.andExpect(model().attribute("pod", "nginx"))
			.andExpect(model().attribute("namespace", "web"));
	}

	@Test
	void dashboardMetricsPageRenders() throws Exception {
		// No metrics-server in the mock -> the page still renders with empty usage
		// tables.
		mvc.perform(get("/clusters/test/metrics"))
			.andExpect(status().isOk())
			.andExpect(view().name("metrics"))
			.andExpect(model().attributeExists("nodeUsage", "podUsage"));
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

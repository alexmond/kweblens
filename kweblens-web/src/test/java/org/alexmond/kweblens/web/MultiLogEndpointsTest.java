package org.alexmond.kweblens.web;

import java.util.Map;

import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The multi-source log endpoints: source expansion for a pod and for a workload, the
 * multiplexed stream itself, and the previous-run log.
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
@EnableKubernetesMockClient(crud = true)
class MultiLogEndpointsTest {

	KubernetesClient client;

	KubernetesMockServer server;

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private ClusterRegistry registry;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).build();
		this.registry.register("test", "Test cluster", this.client);
		this.client.pods()
			.inNamespace("app")
			.resource(new PodBuilder().withNewMetadata()
				.withName("web-a")
				.withNamespace("app")
				.withLabels(Map.of("app", "web"))
				.endMetadata()
				.withNewSpec()
				.addNewContainer()
				.withName("nginx")
				.withImage("nginx")
				.endContainer()
				.addNewContainer()
				.withName("sidecar")
				.withImage("busybox")
				.endContainer()
				.endSpec()
				.build())
			.create();
	}

	@Test
	void listsEveryContainerOfAPodAsASource() throws Exception {
		this.mvc.perform(get("/api/v1/clusters/test/logs/sources").param("namespace", "app").param("pod", "web-a"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalFound").value(2))
			.andExpect(jsonPath("$.sources[0]").value("app/web-a/nginx"))
			.andExpect(jsonPath("$.sources[1]").value("app/web-a/sidecar"));
	}

	@Test
	void listsEveryPodOfAWorkloadAsASource() throws Exception {
		this.client.apps()
			.deployments()
			.inNamespace("app")
			.resource(new DeploymentBuilder().withNewMetadata()
				.withName("web")
				.withNamespace("app")
				.endMetadata()
				.withNewSpec()
				.withNewSelector()
				.withMatchLabels(Map.of("app", "web"))
				.endSelector()
				.endSpec()
				.build())
			.create();

		this.mvc
			.perform(get("/api/v1/clusters/test/logs/sources").param("namespace", "app")
				.param("resourceId", "deployments")
				.param("name", "web"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalFound").value(2))
			.andExpect(jsonPath("$.sources[0]").value("app/web-a/nginx"));
	}

	@Test
	void rejectsARequestThatNamesNeitherAPodNorAWorkload() throws Exception {
		this.mvc.perform(get("/api/v1/clusters/test/logs/sources").param("namespace", "app"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void streamsTaggedLinesWithTheTimestampSplitOff() throws Exception {
		// One container's snapshot. The timestamp prefix must come back as its own field
		// with clean text — that is what lets the client sort across sources.
		this.server.expect()
			.get()
			.withPath("/api/v1/namespaces/app/pods/web-a/log?pretty=false&container=nginx&tailLines=1&timestamps=true")
			.andReturn(200, "2026-07-29T12:00:00Z listening on :80")
			.always();

		MvcResult result = this.mvc
			.perform(get("/api/v1/clusters/test/logs/stream").param("namespace", "app")
				.param("pod", "web-a")
				.param("container", "nginx")
				.param("tailLines", "1"))
			.andExpect(status().isOk())
			.andReturn();

		String body = result.getResponse().getContentAsString();
		// The source announcement arrives first, so the client can colour the gutter
		// before any line lands.
		assertThat(body).contains("event:sources").contains("app/web-a/nginx");
		assertThat(body).contains("listening on :80").contains("2026-07-29T12:00:00Z");
	}

	@Test
	void servesThePreviousRunsLog() throws Exception {
		this.server.expect()
			.get()
			.withPath("/api/v1/namespaces/app/pods/web-a/log?pretty=false&container=nginx&previous=true&tailLines=5")
			.andReturn(200, "panic: out of memory")
			.always();

		this.mvc
			.perform(get("/api/v1/clusters/test/pods/app/web-a/log/previous").param("container", "nginx")
				.param("tailLines", "5"))
			.andExpect(status().isOk());
	}

}

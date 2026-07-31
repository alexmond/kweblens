package org.alexmond.kweblens.web;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.cluster.ClusterStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runtime cluster configuration over HTTP.
 *
 * <p>
 * {@code cluster-store.mode=memory} so the test never writes a kubeconfig to the machine
 * running it. No mock API server is needed: nothing here connects — building a fabric8
 * client only resolves config.
 */
@SpringBootTest(properties = { "kweblens.load-kubeconfig=false", "kweblens.security.admin-password=secret",
		"kweblens.cluster-store.mode=memory" })
class ClusterConfigEndpointsTest {

	// RFC 5737 documentation addresses; the token is the canary for credential leaks.
	private static final String KUBECONFIG = """
			apiVersion: v1
			kind: Config
			current-context: dev
			clusters:
			- name: dev-cluster
			  cluster:
			    server: https://198.51.100.10:6443
			- name: qa-cluster
			  cluster:
			    server: https://198.51.100.11:6443
			contexts:
			- name: dev
			  context:
			    cluster: dev-cluster
			    user: dev-user
			- name: qa
			  context:
			    cluster: qa-cluster
			    user: qa-user
			users:
			- name: dev-user
			  user:
			    token: s3cr3t-canary-token
			- name: qa-user
			  user:
			    token: s3cr3t-canary-token
			""";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private ClusterRegistry registry;

	@Autowired
	private ClusterStore store;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
	}

	@AfterEach
	void cleanUp() {
		this.registry.list().forEach((c) -> this.registry.unregister(c.id()));
		this.store.load().forEach((d) -> this.store.delete(d.id()));
	}

	private String body(String id, String context, String kubeconfig) {
		return """
				{"id": %s, "name": "Development", "context": %s, "kubeconfig": %s}
				""".formatted(quote(id), quote(context), quote(kubeconfig));
	}

	private String quote(String value) {
		if (value == null) {
			return "null";
		}
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
	}

	private void addDev() throws Exception {
		this.mvc
			.perform(post("/api/v1/clusters").with(httpBasic("admin", "secret"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("dev", "dev", KUBECONFIG)))
			.andExpect(status().isCreated());
	}

	@Test
	void addsAClusterAndItBecomesBrowsable() throws Exception {
		this.mvc
			.perform(post("/api/v1/clusters").with(httpBasic("admin", "secret"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("dev", "dev", KUBECONFIG)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value("dev"))
			.andExpect(jsonPath("$.origin").value("RUNTIME"))
			.andExpect(jsonPath("$.masterUrl").value(Matchers.containsString("198.51.100.10")));

		this.mvc.perform(get("/api/v1/clusters")).andExpect(jsonPath("$[0].id").value("dev"));
	}

	@Test
	void theCredentialIsNeverEchoedBack() throws Exception {
		addDev();

		// Not in the create response, not in the list, not in the config view — the
		// kubeconfig travels one way only.
		String config = this.mvc.perform(get("/api/v1/clusters/dev/config"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kubeconfigStored").value(true))
			.andExpect(jsonPath("$.contexts[0]").value("dev"))
			.andExpect(jsonPath("$.kubeconfig").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(config).doesNotContain("s3cr3t");

		String list = this.mvc.perform(get("/api/v1/clusters")).andReturn().getResponse().getContentAsString();
		assertThat(list).doesNotContain("s3cr3t");
	}

	@Test
	void aBadKubeconfigIs400AndLeavesTheClusterListUnchanged() throws Exception {
		addDev();

		this.mvc
			.perform(post("/api/v1/clusters").with(httpBasic("admin", "secret"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("broken", null, "}{ not yaml")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid-cluster"));

		this.mvc.perform(get("/api/v1/clusters"))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].id").value("dev"));
	}

	@Test
	void aDuplicateIdIs409() throws Exception {
		addDev();

		this.mvc
			.perform(post("/api/v1/clusters").with(httpBasic("admin", "secret"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("dev", "qa", KUBECONFIG)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("cluster-conflict"));
	}

	@Test
	void editingSwitchesContextWithoutResupplyingTheCredential() throws Exception {
		addDev();

		this.mvc
			.perform(put("/api/v1/clusters/dev").with(httpBasic("admin", "secret"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"QA\", \"context\": \"qa\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("QA"))
			.andExpect(jsonPath("$.masterUrl").value(Matchers.containsString("198.51.100.11")));
	}

	@Test
	void removingUnregistersTheCluster() throws Exception {
		addDev();

		this.mvc.perform(delete("/api/v1/clusters/dev").with(httpBasic("admin", "secret")))
			.andExpect(status().isNoContent());

		this.mvc.perform(get("/api/v1/clusters")).andExpect(content().json("[]"));
		this.mvc.perform(get("/api/v1/clusters/dev/config")).andExpect(status().isNotFound());
		assertThat(this.store.load()).isEmpty();
	}

	@Test
	void contextsCanBeInspectedBeforeCommitting() throws Exception {
		this.mvc
			.perform(post("/api/v1/clusters/contexts").with(httpBasic("admin", "secret"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(null, null, KUBECONFIG)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0]").value("dev"))
			.andExpect(jsonPath("$[1]").value("qa"));

		// Nothing was registered by inspecting.
		this.mvc.perform(get("/api/v1/clusters")).andExpect(content().json("[]"));
	}

	@Test
	void everyMutatingRouteRequiresTheAdminLogin() throws Exception {
		this.mvc
			.perform(post("/api/v1/clusters").contentType(MediaType.APPLICATION_JSON)
				.content(body("dev", "dev", KUBECONFIG)))
			.andExpect(status().isUnauthorized());
		this.mvc.perform(put("/api/v1/clusters/dev").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());
		this.mvc.perform(delete("/api/v1/clusters/dev")).andExpect(status().isUnauthorized());
		this.mvc
			.perform(post("/api/v1/clusters/contexts").contentType(MediaType.APPLICATION_JSON)
				.content(body(null, null, KUBECONFIG)))
			.andExpect(status().isUnauthorized());
	}

}

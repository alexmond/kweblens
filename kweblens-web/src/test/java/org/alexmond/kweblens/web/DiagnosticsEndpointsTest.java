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

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The read-only diagnostics endpoints (#27). The security assertions are the important
 * ones: this payload reports the security posture, so it must report the posture honestly
 * and never leak the credential itself.
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
@EnableKubernetesMockClient(crud = true)
class DiagnosticsEndpointsTest {

	KubernetesClient client;

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private ClusterRegistry registry;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).build();
		this.registry.register("test", "Test cluster", this.client);
	}

	@Test
	void reportsClusterCapabilitiesWithAnExplanationPerRow() throws Exception {
		this.mvc.perform(get("/api/v1/clusters/test/diagnostics"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.clusterId").value("test"))
			.andExpect(jsonPath("$.capabilities").isArray())
			// Every capability must carry a `detail`: a bare true/false would not answer
			// the
			// "why is this screen empty?" question the panel exists for.
			.andExpect(jsonPath("$.capabilities[0].name").exists())
			.andExpect(jsonPath("$.capabilities[0].detail").isNotEmpty());
	}

	@Test
	void neverReportsTheAdminPasswordItself() throws Exception {
		this.mvc.perform(get("/api/v1/about"))
			.andExpect(status().isOk())
			// Only WHETHER it is configured — the value must not appear under any key.
			.andExpect(jsonPath("$.security.adminPasswordConfigured").exists())
			.andExpect(jsonPath("$.security.adminPassword").doesNotExist())
			.andExpect(jsonPath("$.security.password").doesNotExist());
	}

	@Test
	void statesTheKnownIdentityGapsRatherThanOmittingThem() throws Exception {
		// These flags drive the panel's warning banner. If they ever flip to true without
		// the work being done, the UI would start reassuring people wrongly.
		this.mvc.perform(get("/api/v1/about"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.security.perUserIdentity").value(false))
			.andExpect(jsonPath("$.security.rbacAware").value(false))
			.andExpect(jsonPath("$.security.mode").isNotEmpty())
			// Deliberately not an exact count: Spring caches the context across test
			// classes, so the ClusterRegistry accumulates earlier classes' registrations.
			// Asserting `1` passes alone and fails in the full suite.
			.andExpect(jsonPath("$.clusterCount").value(greaterThanOrEqualTo(1)));
	}

	@Test
	void aboutIsReadableWithoutSigningInSoTheEmptyScreenQuestionCanBeAnswered() throws Exception {
		// A GET in the default open-mode: a read-only user hits "why is this empty?" too.
		this.mvc.perform(get("/api/v1/about")).andExpect(status().isOk());
	}

}

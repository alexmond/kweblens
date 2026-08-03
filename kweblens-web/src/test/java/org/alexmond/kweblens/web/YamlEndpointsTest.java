package org.alexmond.kweblens.web;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * YAML view + apply endpoints. Security is not applied to this MockMvc (the auth gate is
 * covered by SecurityGateTest); here we exercise the read/apply logic. The crud mock has
 * no server-side apply, so the apply PATCH is stubbed.
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
@EnableKubernetesMockClient(crud = true)
class YamlEndpointsTest {

	KubernetesClient client;

	KubernetesMockServer server;

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
				.addToData("key", "value")
				.build())
			.create();
	}

	@Test
	void apiReturnsResourceYaml() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/yaml").param("resource", "configmaps")
			.param("namespace", "default")
			.param("name", "cm1"))
			.andExpect(status().isOk())
			.andExpect(content().string(org.hamcrest.Matchers.containsString("cm1")));
	}

	@Test
	void apiReturns404ForMissingResource() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/yaml").param("resource", "configmaps")
			.param("namespace", "default")
			.param("name", "absent")).andExpect(status().isNotFound());
	}

	@Test
	void applyServerSideAppliesAndAudits() throws Exception {
		server.expect()
			.patch()
			.withPath("/api/v1/namespaces/default/configmaps/my-config?fieldManager=fabric8&force=true")
			.andReturn(200,
					"{\"apiVersion\":\"v1\",\"kind\":\"ConfigMap\","
							+ "\"metadata\":{\"name\":\"my-config\",\"namespace\":\"default\"}}")
			.always();
		String yaml = "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: my-config\n  namespace: default\n";

		mvc.perform(post("/api/v1/clusters/test/apply").contentType("application/yaml").content(yaml))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("my-config"));
	}

	@Test
	void applyDryRunSendsDryRunAllAndReturnsWhatTheServerWouldStore() throws Exception {
		// The assertion IS the stubbed path. The fabric8 CRUD mock ignores `dryRun`, so a
		// test that dry-ran and then checked the object was not created would pass
		// whether
		// or not the flag was sent — the same trap that made the search cap untestable
		// that
		// way. Matching the exact query string is what proves the request carries it.
		server.expect()
			.patch()
			.withPath("/api/v1/namespaces/default/configmaps/my-config?fieldManager=fabric8&dryRun=All&force=true")
			.andReturn(200,
					"{\"apiVersion\":\"v1\",\"kind\":\"ConfigMap\","
							+ "\"metadata\":{\"name\":\"my-config\",\"namespace\":\"default\"},"
							+ "\"data\":{\"defaultedByServer\":\"yes\"}}")
			.always();
		String yaml = "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: my-config\n  namespace: default\n";

		mvc.perform(post("/api/v1/clusters/test/apply/dry-run").contentType("application/yaml").content(yaml))
			.andExpect(status().isOk())
			// Returned as YAML, and carrying a field the caller never sent — which is the
			// point: defaulting is invisible to a diff against what was typed.
			.andExpect(content().string(org.hamcrest.Matchers.containsString("defaultedByServer")));
	}

	@Test
	void applyDryRunSurfacesAnAdmissionRejectionInsteadOf500() throws Exception {
		// The reason this feature exists. Before the KubernetesClientException mapping
		// the
		// webhook's message — the only useful part — was flattened into a generic 500.
		server.expect()
			.patch()
			.withPath("/api/v1/namespaces/default/configmaps/my-config?fieldManager=fabric8&dryRun=All&force=true")
			.andReturn(422, "{\"kind\":\"Status\",\"status\":\"Failure\",\"code\":422,"
					+ "\"message\":\"admission webhook \\\"policy.example.com\\\" denied the request: no owner label\"}")
			.always();
		String yaml = "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: my-config\n  namespace: default\n";

		mvc.perform(post("/api/v1/clusters/test/apply/dry-run").contentType("application/yaml").content(yaml))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("denied the request")))
			.andExpect(jsonPath("$.code").value("cluster-refused"));
	}

}

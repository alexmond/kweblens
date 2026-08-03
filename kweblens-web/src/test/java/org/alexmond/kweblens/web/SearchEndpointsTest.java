package org.alexmond.kweblens.web;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Global search (GH#259): finding an object without knowing its kind, and being honest
 * about what was truncated and what was never looked at.
 *
 * <p>
 * <b>The cap is asserted over the returned list, never over a server-side
 * {@code limit}.</b> The fabric8 CRUD mock ignores {@code limit} entirely, so a test
 * written against one would pass here and mean nothing about a real API server. It also
 * happens to be why the engine does not use one: it caps <em>matches</em> after scoring,
 * which is a property of the response and therefore testable.
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
@EnableKubernetesMockClient(crud = true)
class SearchEndpointsTest {

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
		deployment("sonarqube", "ci");
		pod("sonarqube-7d9f8-x2k4l", "ci");
		pod("sonarqube-7d9f8-q7m1n", "staging");
		configMap("sonarqube-config", "ci");
		configMap("unrelated", "ci");
	}

	@Test
	void findsObjectsAcrossKindsAndNamespaces() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/search").param("q", "sonarqube"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(4))
			.andExpect(jsonPath("$.hits.length()").value(4))
			.andExpect(jsonPath("$.hits[*].kind", Matchers.hasItems("Deployment", "Pod", "ConfigMap")))
			.andExpect(jsonPath("$.hits[*].namespace", Matchers.hasItems("ci", "staging")));
	}

	/** Every row addresses itself: kind, namespace and the route id of its own kind. */
	@Test
	void everyRowCarriesKindNamespaceAndItsOwnRouteId() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/search").param("q", "sonarqube-7d9f8-x2k4l"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.hits[0].kind").value("Pod"))
			.andExpect(jsonPath("$.hits[0].resourceId").value("pods"))
			.andExpect(jsonPath("$.hits[0].namespace").value("ci"))
			.andExpect(jsonPath("$.hits[0].name").value("sonarqube-7d9f8-x2k4l"));
	}

	/** A primary kind outranks the pod it generated when both match equally well. */
	@Test
	void primaryKindsRankAboveGeneratedOnes() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/search").param("q", "sonarqube"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.hits[0].kind").value("Deployment"));
	}

	@Test
	void namespaceFilterScopesTheSearchAndIsEchoed() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/search").param("q", "sonarqube").param("namespace", "staging"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.namespace").value("staging"))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.hits[0].namespace").value("staging"));
	}

	/**
	 * Truncation is reported against the REAL match count, not the cap (the correction
	 * GH#157 made to the overviews). The cap is checked on the returned array.
	 */
	@Test
	void truncationReportsTheRealTotal() throws Exception {
		for (int i = 0; i < 30; i++) {
			configMap("bulk-" + i, "ci");
		}
		mvc.perform(get("/api/v1/clusters/test/search").param("q", "bulk-").param("limit", "5"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.hits.length()").value(5))
			.andExpect(jsonPath("$.total").value(30))
			.andExpect(jsonPath("$.truncated").value(true));
	}

	/** Not truncated when everything fits — the flag has to mean something. */
	@Test
	void notTruncatedWhenEverythingFits() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/search").param("q", "sonarqube"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.truncated").value(false));
	}

	/**
	 * The load-bearing honesty: the kinds NOT searched are named. Without this a
	 * CRD-backed object is missing from a result set that looks complete.
	 */
	@Test
	void namesTheKindsItDidNotSearch() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/search").param("q", "sonarqube"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.searchedKinds", Matchers.hasItems("Pods", "Deployments", "Config Maps")))
			.andExpect(jsonPath("$.unsearchedKinds", Matchers.hasItems("Replica Sets", "Roles", "Events")))
			.andExpect(jsonPath("$.unsearchedKinds", Matchers.not(Matchers.hasItem("Pods"))));
	}

	@Test
	void blankQueryReturnsNothingRatherThanEverything() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/search").param("q", "   "))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.hits.length()").value(0))
			.andExpect(jsonPath("$.total").value(0));
	}

	/** A hit is opened by fetching its own object — the drawer's addressing path. */
	@Test
	void singleObjectFetchAddressesAHitByItsOwnKind() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/resources/pods/object").param("namespace", "ci")
			.param("name", "sonarqube-7d9f8-x2k4l"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kind").value("Pod"))
			.andExpect(jsonPath("$.metadata.name").value("sonarqube-7d9f8-x2k4l"));
	}

	@Test
	void singleObjectFetchOfAMissingObjectIsABadRequestNotAnEmptyBody() throws Exception {
		mvc.perform(get("/api/v1/clusters/test/resources/pods/object").param("namespace", "ci").param("name", "nope"))
			.andExpect(status().isBadRequest());
	}

	private void deployment(String name, String namespace) {
		client.apps()
			.deployments()
			.resource(new DeploymentBuilder().withNewMetadata()
				.withName(name)
				.withNamespace(namespace)
				.endMetadata()
				.build())
			.create();
	}

	private void pod(String name, String namespace) {
		client.pods()
			.resource(new PodBuilder().withNewMetadata().withName(name).withNamespace(namespace).endMetadata().build())
			.create();
	}

	private void configMap(String name, String namespace) {
		client.configMaps()
			.resource(new ConfigMapBuilder().withNewMetadata()
				.withName(name)
				.withNamespace(namespace)
				.endMetadata()
				.build())
			.create();
	}

}

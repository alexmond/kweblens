package org.alexmond.kweblens.web.access;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/clusters/{id}/access} — the one request a surface makes before it
 * decides which of its controls to grey out.
 *
 * <p>
 * The fail-open case is asserted here as well as in {@code AccessReviewServiceTest},
 * because it has to survive the whole path: a review that never answers must reach the
 * browser as {@code unknown}, which the client renders as <b>enabled</b>. A verdict that
 * failed open in core and arrived as a refusal on the wire would be the same defect one
 * layer further out.
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
@EnableKubernetesMockClient(crud = true)
class AccessEndpointsTest {

	private static final String REVIEW_PATH = "/apis/authorization.k8s.io/v1/selfsubjectaccessreviews";

	KubernetesClient client;

	KubernetesMockServer server;

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private ClusterRegistry registry;

	private MockMvc mvc;

	/**
	 * The endpoint, on one shared cluster id — and each test below asks about a NAMESPACE
	 * of its own, which is isolation rather than decoration.
	 *
	 * <p>
	 * The review cache lives in a singleton bean and is keyed by (cluster, group,
	 * resource, namespace, verb), and the mock client is the same instance for every
	 * method in this class — so re-registering one shared cluster id does <b>not</b> drop
	 * it: {@code ClusterRegistry} notifies its listeners only when the client actually
	 * changes, which is correct and which left one test reading the verdict another had
	 * stubbed. A namespace per test moves the key instead of the cluster, which keeps the
	 * registry to a single id — registering one per test polluted the shared registry and
	 * broke {@code ClusterEndpointsTest}, which lists it. (That the cache IS dropped when
	 * an id is genuinely re-pointed is asserted in {@code AccessReviewServiceTest}.)
	 */
	private static final String URL = "/api/v1/clusters/test/access";

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).build();
		this.registry.register("test", "Test cluster", this.client);
	}

	private void reviewsAnswer(String status) {
		this.server.expect()
			.post()
			.withPath(REVIEW_PATH)
			.andReturn(201,
					"{\"apiVersion\":\"authorization.k8s.io/v1\",\"kind\":\"SelfSubjectAccessReview\"," + "\"status\":"
							+ status + "}")
			.always();
	}

	@Test
	void aRefusalArrivesWithTheClusterSOwnReason() throws Exception {
		reviewsAnswer("{\"allowed\":false,\"reason\":\"RBAC: no rules authorize this\"}");

		this.mvc.perform(get(URL).param("resource", "configmaps").param("namespace", "ns-refused"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kind").value("ConfigMap"))
			.andExpect(jsonPath("$.namespace").value("ns-refused"))
			.andExpect(jsonPath("$.verbs.delete.verdict").value("denied"))
			.andExpect(jsonPath("$.verbs.delete.reason").value("RBAC: no rules authorize this"));
	}

	@Test
	void aReviewThatFailsArrivesAsUnknownSoTheControlStaysEnabled() throws Exception {
		this.server.expect().post().withPath(REVIEW_PATH).andReturn(500, "boom").always();

		this.mvc.perform(get(URL).param("resource", "configmaps").param("namespace", "ns-unanswered"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.verbs.create.verdict").value("unknown"))
			.andExpect(jsonPath("$.verbs.patch.verdict").value("unknown"))
			.andExpect(jsonPath("$.verbs.delete.verdict").value("unknown"));
	}

	@Test
	void anAllowArrives() throws Exception {
		reviewsAnswer("{\"allowed\":true}");

		this.mvc.perform(get(URL).param("resource", "configmaps").param("namespace", "ns-allowed"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.verbs.delete.verdict").value("allowed"))
			.andExpect(jsonPath("$.verbs.delete.reason").doesNotExist());
	}

	@Test
	void aClusterWideRefusalAboutANamespacedKindIsNotReportedAsARefusal() throws Exception {
		// No namespace on screen, so the review has to be cluster-wide — and a
		// cluster-wide "no" only says "not in every namespace". A service account with a
		// RoleBinding in one namespace answers exactly this while being able to delete
		// the
		// row the operator is pointing at.
		reviewsAnswer("{\"allowed\":false,\"reason\":\"RBAC: no rules authorize this\"}");

		this.mvc.perform(get(URL).param("resource", "configmaps"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.namespace").doesNotExist())
			.andExpect(jsonPath("$.verbs.delete.verdict").value("unknown"));
	}

	@Test
	void aClusterWideRefusalAboutAClusterScopedKindIsARefusal() throws Exception {
		reviewsAnswer("{\"allowed\":false,\"reason\":\"RBAC: no rules authorize this\"}");

		this.mvc.perform(get(URL).param("resource", "nodes"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kind").value("Node"))
			.andExpect(jsonPath("$.verbs.delete.verdict").value("denied"));
	}

	@Test
	void aListOfManyRowsStillCostsThreeReviews() throws Exception {
		reviewsAnswer("{\"allowed\":true}");
		for (int i = 0; i < 25; i++) {
			this.client.configMaps()
				.resource(new ConfigMapBuilder().withNewMetadata()
					.withName("cm" + i)
					.withNamespace("default")
					.endMetadata()
					.build())
				.create();
		}
		int before = this.server.getRequestCount();

		this.mvc.perform(get(URL).param("resource", "configmaps").param("namespace", "ns-many-rows"))
			.andExpect(status().isOk());

		assertThat(this.server.getRequestCount() - before)
			.as("one review per verb for the whole surface — never one per row")
			.isEqualTo(3);
	}

	@Test
	void anUnknownKindIs404() throws Exception {
		this.mvc.perform(get(URL).param("resource", "not-a-kind")).andExpect(status().isNotFound());
	}

}

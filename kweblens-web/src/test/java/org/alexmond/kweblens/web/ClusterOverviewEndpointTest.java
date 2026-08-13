package org.alexmond.kweblens.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.NodeConditionBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Cluster overview (GH#339): <b>the number on a Nodes or Namespaces card and the rows
 * a {@code status:} query selects are the same objects</b>.
 *
 * <p>
 * The equality is asserted end to end, over HTTP, on both halves as the browser receives
 * them — {@code /overview/cluster} for the card and {@code /resources/…/objects} for the
 * rows — because those are two separate code paths and the defect this epic exists to
 * prevent (#157, #316) is precisely two predicates producing similar-looking words. A
 * test that called one Java method twice would prove nothing about that.
 *
 * <p>
 * <b>Decoys and a mutation.</b> The seeded cluster has more than one state per kind, so
 * an empty-versus-empty comparison cannot pass as agreement; it carries pods, which this
 * category must not report; and {@link #theComparisonIsCapableOfFailing()} perturbs a
 * count and shows the assertion going red.
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
@EnableKubernetesMockClient(crud = true)
class ClusterOverviewEndpointTest {

	private static final String OVERVIEW = "/api/v1/clusters/test/overview/cluster";

	private static final ObjectMapper JSON = new ObjectMapper();

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
		node("worker-1", "True", false);
		node("worker-2", "True", false);
		node("worker-3", "True", true);
		node("worker-4", "False", false);
		namespace("default", "Active");
		namespace("app", "Active");
		namespace("doomed", "Terminating");
		// A decoy: pods are judged by a different check and belong to a different
		// category. If they leaked into this overview the counts below would move.
		this.client.pods()
			.inNamespace("app")
			.resource(new PodBuilder().withNewMetadata().withName("web").withNamespace("app").endMetadata().build())
			.create();
	}

	private void node(String name, String ready, boolean cordoned) {
		this.client.nodes()
			.resource(new NodeBuilder().withNewMetadata()
				.withName(name)
				.endMetadata()
				.withNewSpec()
				.withUnschedulable(cordoned)
				.endSpec()
				.withNewStatus()
				.withConditions(new NodeConditionBuilder().withType("Ready").withStatus(ready).build())
				.endStatus()
				.build())
			.create();
	}

	private void namespace(String name, String phase) {
		this.client.namespaces()
			.resource(new NamespaceBuilder().withNewMetadata()
				.withName(name)
				.endMetadata()
				.withNewStatus()
				.withPhase(phase)
				.endStatus()
				.build())
			.create();
	}

	// --- the two halves, as the browser gets them ---

	private JsonNode json(String url) throws Exception {
		return JSON.readTree(
				this.mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private JsonNode card(String resourceId) throws Exception {
		for (JsonNode kind : json(OVERVIEW)) {
			if (resourceId.equals(kind.path("id").asText())) {
				return kind;
			}
		}
		throw new AssertionError("No card for " + resourceId + " on the Cluster overview");
	}

	/** What the card shows: state label to count. */
	private Map<String, Integer> cardCounts(String resourceId) throws Exception {
		JsonNode kind = card(resourceId);
		assertThat(kind.path("error").isNull()).as("the check ran").isTrue();
		Map<String, Integer> out = new TreeMap<>();
		for (JsonNode state : kind.path("states")) {
			out.put(state.path("label").asText(), state.path("count").asInt());
		}
		return out;
	}

	/** What a {@code status:} term would select: state label to row count. */
	private Map<String, Integer> rowCounts(String resourceId) throws Exception {
		Map<String, Integer> out = new TreeMap<>();
		for (JsonNode row : rows(resourceId)) {
			JsonNode state = row.path("kweblensState");
			if (!state.isMissingNode()) {
				out.merge(state.path("label").asText(), 1, Integer::sum);
			}
		}
		return out;
	}

	/** The list endpoint's reply: a JSON array of rows, each as the table receives it. */
	private JsonNode rows(String resourceId) throws Exception {
		return json("/api/v1/clusters/test/resources/" + resourceId + "/objects");
	}

	// --- the assertions ---

	@Test
	void everyStateTheNodesCardCountsSelectsExactlyThoseRows() throws Exception {
		Map<String, Integer> card = cardCounts("nodes");

		// Pinned independently as well as compared: two empty maps are equal, and so are
		// two wrong ones that are wrong the same way.
		assertThat(card)
			.containsExactlyInAnyOrderEntriesOf(Map.of("Ready", 2, "Ready,SchedulingDisabled", 1, "NotReady", 1));
		assertThat(rowCounts("nodes")).isEqualTo(card);
		assertThat(card("nodes").path("total").asInt()).isEqualTo(4);
	}

	@Test
	void everyStateTheNamespacesCardCountsSelectsExactlyThoseRows() throws Exception {
		Map<String, Integer> card = cardCounts("namespaces");

		assertThat(card).containsExactlyInAnyOrderEntriesOf(Map.of("Active", 2, "Terminating", 1));
		assertThat(rowCounts("namespaces")).isEqualTo(card);
	}

	@Test
	void theToneOnTheCardIsTheToneOnTheRow() throws Exception {
		// A colour is a claim of its own, and the click-through inherits it: a state
		// shown
		// amber that arrives as red text is a second discrepancy of the same kind.
		Map<String, String> rowTones = new LinkedHashMap<>();
		for (String resourceId : new String[] { "nodes", "namespaces" }) {
			for (JsonNode row : rows(resourceId)) {
				rowTones.put(row.path("kweblensState").path("label").asText(),
						row.path("kweblensState").path("tone").asText());
			}
			for (JsonNode state : card(resourceId).path("states")) {
				assertThat(rowTones).containsEntry(state.path("label").asText(), state.path("tone").asText());
			}
		}
		assertThat(rowTones).containsEntry("Ready", "ok")
			.containsEntry("Ready,SchedulingDisabled", "warn")
			.containsEntry("NotReady", "err")
			.containsEntry("Terminating", "warn");
	}

	@Test
	void theNamespaceFilterDoesNotNarrowACategoryOfClusterScopedKinds() throws Exception {
		// #313's precedent: a namespace assumption applied to a cluster-scoped kind does
		// not return fewer rows, it asks a question the API cannot answer. The page says
		// the cards are cluster-wide; this is that claim, tested.
		assertThat(json(OVERVIEW + "?namespace=doomed")).isEqualTo(json(OVERVIEW));
	}

	@Test
	void theCategoryIsExactlyTheKindsThereIsAVerdictFor() throws Exception {
		// Events are the third kind in the Cluster nav category and are deliberately not
		// here: an event's Warning/Normal is a field on a report about another object, so
		// there is no state to count and nothing to click. Pods are a decoy from another
		// category entirely.
		JsonNode overview = json(OVERVIEW);

		assertThat(overview).hasSize(2);
		assertThat(overview.path(0).path("id").asText()).isEqualTo("nodes");
		assertThat(overview.path(1).path("id").asText()).isEqualTo("namespaces");
	}

	@Test
	void theComparisonIsCapableOfFailing() throws Exception {
		// Without this, "the two maps are equal" could be true of a comparison that never
		// looked at anything.
		Map<String, Integer> card = cardCounts("nodes");

		Map<String, Integer> perturbed = new TreeMap<>(rowCounts("nodes"));
		perturbed.merge("Ready", -1, Integer::sum);
		perturbed.merge("NotReady", 1, Integer::sum);

		assertThat(perturbed).isNotEqualTo(card);
	}

	@Test
	void anUnknownCategoryIsStillNotFound() throws Exception {
		// The new case must not have turned the default branch into a fall-through: an
		// empty list would render as a healthy, empty dashboard.
		this.mvc.perform(get("/api/v1/clusters/test/overview/nodes")).andExpect(status().isNotFound());
	}

}

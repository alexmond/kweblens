package org.alexmond.kweblens.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.EndpointAddress;
import io.fabric8.kubernetes.api.model.EndpointsBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.metric.MetricsProperties;
import org.alexmond.kweblens.metric.PrometheusMetricService;
import org.alexmond.kweblens.metric.VolumeUsage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Network, Storage and Config overviews (GH#340): <b>the number on a card and the
 * rows a {@code status:} query selects are the same objects</b>.
 *
 * <p>
 * Asserted end to end, over HTTP, on both halves as the browser receives them —
 * {@code /overview/<category>} for the card and {@code /resources/…/objects} for the rows
 * — because those are two separate code paths and this is where the epic could most
 * easily have shipped a lie. These three categories are the ones whose verdicts are
 * <b>not</b> in the object: a Service's is in the Endpoints collection, a claim's
 * fullness is in the metrics backend, a ConfigMap's is in a scan of the namespace.
 * Nothing in the browser can reproduce any of them, so a filter over something merely
 * similar is the failure mode #336 exists to prevent, and only a comparison of the two
 * payloads catches it.
 *
 * <p>
 * Each category is asserted on <b>one field-backed state and one derived-verdict
 * state</b>: {@code Bound} versus {@code Nearly full}; a Secret's
 * {@code Cluster-managed}, which is its own {@code type} field, versus
 * {@code Not referenced}, which is a property of everything else in the namespace.
 *
 * <p>
 * <b>Decoys and a mutation.</b> The seeded namespace carries an ExternalName Service that
 * must not be flagged, a claim whose usage reading describes a shared disk rather than
 * the claim, cluster-owned Secrets that are neither referenced nor a finding, and objects
 * in a second namespace that the filtered halves must both exclude;
 * {@link #theComparisonIsCapableOfFailing()} perturbs a count and shows the assertion
 * going red.
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
@EnableKubernetesMockClient(crud = true)
@Import(CategoryOverviewEndpointTest.StubMetricsConfig.class)
class CategoryOverviewEndpointTest {

	private static final String NS = "app";

	private static final long GIB = 1024L * 1024 * 1024;

	private static final ObjectMapper JSON = new ObjectMapper();

	/**
	 * The readings the stub backend reports. {@code data} is a genuine per-volume quota
	 * and 95% full; {@code logs} is kubelet reporting the whole backing disk, which is
	 * 90% full and about a 10Gi claim — the reading that must NOT produce a finding.
	 */
	private static final Map<String, VolumeUsage> READINGS = Map.of("app/data",
			new VolumeUsage(NS, "data", 95 * GIB / 10, 10 * GIB), "app/logs",
			new VolumeUsage(NS, "logs", 900 * GIB, 1_000 * GIB));

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
		seedNetwork();
		seedStorage();
		seedConfig();
	}

	// --- seeding ---

	private void seedNetwork() {
		service("web", "ClusterIP");
		endpoints("web", 2, 0);
		service("api", "ClusterIP");
		endpoints("api", 1, 0);
		// Nothing behind it at all — a wrong selector or a workload that is gone.
		service("orphan", "ClusterIP");
		// Matched but not ready — a different fix, so it must stay a different state.
		service("starting", "ClusterIP");
		endpoints("starting", 0, 3);
		// A decoy: an ExternalName Service has no endpoints by design and is healthy.
		service("external", "ExternalName");
		// A decoy in another namespace: both halves are asked for `app` only.
		this.client.services()
			.inNamespace("other")
			.resource(new ServiceBuilder().withNewMetadata()
				.withName("elsewhere")
				.withNamespace("other")
				.endMetadata()
				.withNewSpec()
				.withType("ClusterIP")
				.endSpec()
				.build())
			.create();
	}

	private void seedStorage() {
		claim("data", "Bound", "10Gi");
		claim("logs", "Bound", "10Gi");
		claim("scratch", "Bound", "10Gi");
		claim("waiting", "Pending", "5Gi");
		claim("gone", "Lost", "5Gi");
	}

	private void seedConfig() {
		configMap("app-config");
		configMap("orphaned");
		configMap("also-orphaned");
		secret("app-secret", "Opaque");
		secret("stale", "Opaque");
		secret("sa-token", "kubernetes.io/service-account-token");
		secret("sh.helm.release.v1.web.v1", "helm.sh/release.v1");
		podMounting("web-1", "app-config", "app-secret");
	}

	private void service(String name, String type) {
		this.client.services()
			.inNamespace(NS)
			.resource(new ServiceBuilder().withNewMetadata()
				.withName(name)
				.withNamespace(NS)
				.endMetadata()
				.withNewSpec()
				.withType(type)
				.endSpec()
				.build())
			.create();
	}

	private void endpoints(String name, int ready, int notReady) {
		this.client.endpoints()
			.inNamespace(NS)
			.resource(new EndpointsBuilder().withNewMetadata()
				.withName(name)
				.withNamespace(NS)
				.endMetadata()
				.addNewSubset()
				.withAddresses(addresses("10.0.0.", ready))
				.withNotReadyAddresses(addresses("10.1.0.", notReady))
				.endSubset()
				.build())
			.create();
	}

	private List<EndpointAddress> addresses(String prefix, int count) {
		return java.util.stream.IntStream.range(0, count).mapToObj((i) -> {
			EndpointAddress out = new EndpointAddress();
			out.setIp(prefix + (i + 1));
			return out;
		}).toList();
	}

	private void claim(String name, String phase, String requested) {
		this.client.persistentVolumeClaims()
			.inNamespace(NS)
			.resource(new PersistentVolumeClaimBuilder().withNewMetadata()
				.withName(name)
				.withNamespace(NS)
				.endMetadata()
				.withNewSpec()
				.withNewResources()
				.addToRequests("storage", new Quantity(requested))
				.endResources()
				.endSpec()
				.withNewStatus()
				.withPhase(phase)
				.endStatus()
				.build())
			.create();
	}

	private void configMap(String name) {
		this.client.configMaps()
			.inNamespace(NS)
			.resource(new ConfigMapBuilder().withNewMetadata()
				.withName(name)
				.withNamespace(NS)
				.endMetadata()
				.withData(Map.of("log-level", "debug"))
				.build())
			.create();
	}

	private void secret(String name, String type) {
		this.client.secrets()
			.inNamespace(NS)
			.resource(new SecretBuilder().withNewMetadata()
				.withName(name)
				.withNamespace(NS)
				.endMetadata()
				.withType(type)
				.withData(Map.of("k", "dmFsdWU="))
				.build())
			.create();
	}

	private void podMounting(String name, String configMap, String secret) {
		this.client.pods()
			.inNamespace(NS)
			.resource(new PodBuilder().withNewMetadata()
				.withName(name)
				.withNamespace(NS)
				.endMetadata()
				.withNewSpec()
				.addNewVolume()
				.withName("cfg")
				.withNewConfigMap()
				.withName(configMap)
				.endConfigMap()
				.endVolume()
				.addNewVolume()
				.withName("sec")
				.withNewSecret()
				.withSecretName(secret)
				.endSecret()
				.endVolume()
				.endSpec()
				.build())
			.create();
	}

	// --- the two halves, as the browser gets them ---

	private JsonNode json(String url) throws Exception {
		return JSON.readTree(
				this.mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private JsonNode card(String category, String resourceId) throws Exception {
		for (JsonNode kind : json("/api/v1/clusters/test/overview/" + category + "?namespace=" + NS)) {
			if (resourceId.equals(kind.path("id").asText())) {
				return kind;
			}
		}
		throw new AssertionError("No card for " + resourceId + " on the " + category + " overview");
	}

	/** What the card shows: state label to count. */
	private Map<String, Integer> cardCounts(String category, String resourceId) throws Exception {
		JsonNode kind = card(category, resourceId);
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

	private JsonNode rows(String resourceId) throws Exception {
		return json("/api/v1/clusters/test/resources/" + resourceId + "/objects?namespace=" + NS);
	}

	// --- the assertions ---

	@Test
	void everyStateTheServicesCardCountsSelectsExactlyThoseRows() throws Exception {
		// "No endpoints" is the derived verdict — it is nowhere on the Service object and
		// the browser cannot compute it. The ExternalName decoy is counted as Serving on
		// both halves or neither.
		Map<String, Integer> card = cardCounts("network", "services");

		assertThat(card).containsExactlyInAnyOrderEntriesOf(Map.of("Serving", 3, "No endpoints", 1, "Not ready", 1));
		assertThat(rowCounts("services")).isEqualTo(card);
		assertThat(card("network", "services").path("total").asInt()).isEqualTo(5);
	}

	@Test
	void aBindingPhaseAndAFullnessReadingBothSelectTheirOwnRows() throws Exception {
		// Bound / Pending / Lost are on the object; "Nearly full" comes from the metrics
		// backend and applies to exactly one claim, because the other reading describes a
		// shared disk rather than the claim (#175's narrowing, still in force).
		Map<String, Integer> card = cardCounts("storage", "persistentvolumeclaims");

		assertThat(card)
			.containsExactlyInAnyOrderEntriesOf(Map.of("Bound", 2, "Nearly full", 1, "Pending", 1, "Lost", 1));
		assertThat(rowCounts("persistentvolumeclaims")).isEqualTo(card);
	}

	@Test
	void aReferenceScanOfTheNamespaceReachesTheRowsToo() throws Exception {
		// The most expensive verdict to put on a row: it is a property of the pods,
		// service accounts and ingresses around the object rather than of the object.
		Map<String, Integer> configMaps = cardCounts("config", "configmaps");
		Map<String, Integer> secrets = cardCounts("config", "secrets");

		assertThat(configMaps).containsExactlyInAnyOrderEntriesOf(Map.of("Referenced", 1, "Not referenced", 2));
		assertThat(rowCounts("configmaps")).isEqualTo(configMaps);
		// "Cluster-managed" is the field-backed half — it is the Secret's own type.
		assertThat(secrets)
			.containsExactlyInAnyOrderEntriesOf(Map.of("Referenced", 1, "Not referenced", 1, "Cluster-managed", 2));
		assertThat(rowCounts("secrets")).isEqualTo(secrets);
	}

	@Test
	void theToneOnTheCardIsTheToneOnTheRow() throws Exception {
		// A colour is a claim of its own and the click-through inherits it: a state shown
		// amber that arrives as red text is a second discrepancy of the same kind.
		Map<String, String> rowTones = new LinkedHashMap<>();
		Map<String, String> categories = new LinkedHashMap<>();
		categories.put("services", "network");
		categories.put("persistentvolumeclaims", "storage");
		categories.put("configmaps", "config");
		categories.put("secrets", "config");
		for (Map.Entry<String, String> entry : categories.entrySet()) {
			for (JsonNode row : rows(entry.getKey())) {
				rowTones.put(row.path("kweblensState").path("label").asText(),
						row.path("kweblensState").path("tone").asText());
			}
			for (JsonNode state : card(entry.getValue(), entry.getKey()).path("states")) {
				assertThat(rowTones).containsEntry(state.path("label").asText(), state.path("tone").asText());
			}
		}
		assertThat(rowTones).containsEntry("Serving", "ok")
			.containsEntry("No endpoints", "err")
			.containsEntry("Nearly full", "warn")
			.containsEntry("Not referenced", "warn")
			.containsEntry("Cluster-managed", "idle");
	}

	@Test
	void aSecretRowKeepsItsStateWhileLosingItsValues() throws Exception {
		// The two things the list projection does to a Secret must not fight: the values
		// are stripped to keys, and the state is still there for a status: term.
		String body = this.mvc.perform(get("/api/v1/clusters/test/resources/secrets/objects?namespace=" + NS))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(body).doesNotContain("dmFsdWU=").contains("\"kweblensState\"").contains("\"Cluster-managed\"");
	}

	@Test
	void theComparisonIsCapableOfFailing() throws Exception {
		// Move one object between states and the two sides must stop agreeing. Without
		// it, "the maps are equal" could be a claim about a comparison that never looked
		// at anything.
		Map<String, Integer> card = cardCounts("network", "services");

		Map<String, Integer> perturbed = new TreeMap<>(rowCounts("services"));
		perturbed.merge("Serving", -1, Integer::sum);
		perturbed.merge("No endpoints", 1, Integer::sum);

		assertThat(perturbed).isNotEqualTo(card);
	}

	// Nested types last (Checkstyle InnerTypeLast).

	/**
	 * A metrics backend that reports {@link #READINGS}. There is no Prometheus behind the
	 * mock API server, and "Nearly full" is precisely the state that cannot be reached
	 * without one — leaving it unstubbed would test the two halves agreeing about a state
	 * neither of them has.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class StubMetricsConfig {

		@Bean
		@Primary
		PrometheusMetricService stubMetrics(ClusterRegistry clusters, MetricsProperties properties) {
			return new PrometheusMetricService(clusters, properties) {
				@Override
				public Map<String, VolumeUsage> volumeUsage(String clusterId) {
					return READINGS;
				}
			};
		}

	}

}

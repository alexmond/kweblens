package org.alexmond.kweblens.web.api;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.EndpointAddress;
import io.fabric8.kubernetes.api.model.EndpointsBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.health.ConfigUsageService;
import org.alexmond.kweblens.health.KindHealth;
import org.alexmond.kweblens.health.NetworkHealthService;
import org.alexmond.kweblens.health.ObjectState;
import org.alexmond.kweblens.health.StateCount;
import org.alexmond.kweblens.health.StatusContext;
import org.alexmond.kweblens.health.StatusContexts;
import org.alexmond.kweblens.health.StatusVocabulary;
import org.alexmond.kweblens.health.StorageHealthService;
import org.alexmond.kweblens.metric.MetricsProperties;
import org.alexmond.kweblens.metric.PrometheusMetricService;
import org.alexmond.kweblens.metric.VolumeUsage;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.resource.WellKnownKinds;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The same equality as {@link StatusVocabularyEqualityTest}, for the kinds whose
 * verdict is not in the object</b> (GH#340).
 *
 * <p>
 * Network, Storage and Config are where this epic could most easily have shipped a lie. A
 * Service's "No endpoints" lives in the Endpoints collection, a claim's "Nearly full" in
 * the metrics backend, a ConfigMap's "Not referenced" in a scan of the namespace — so the
 * tempting implementation is a client-side predicate over something <i>similar</i>, and
 * the symptom is a card saying 3 over a list showing 2. This runs both halves over one
 * seeded cluster and compares them as maps: the check that draws the card, and the
 * {@link ListProjection} every row and every watch event goes through.
 *
 * <p>
 * Each category is asserted on <b>one field-backed state and one derived-verdict
 * state</b>, which is what the ticket asks for: {@code Bound} is on the object,
 * {@code Nearly full} is not; {@code Cluster-managed} is a Secret's own {@code type},
 * {@code Not referenced} is a property of everything else in the namespace.
 *
 * <p>
 * <b>Decoys, and proof the comparison can fail.</b> Each fleet carries objects in states
 * the assertion does not name and objects deliberately in the healthy state, so an
 * implementation that labelled everything the same way could not pass; the expected shape
 * is pinned independently of the comparison, so both sides going empty is a failure
 * rather than an agreement; and {@link #theComparisonIsCapableOfFailing()} perturbs one
 * row and shows the equality going red.
 */
@EnableKubernetesMockClient(crud = true)
class ContextualStatusEqualityTest {

	private static final String NS = "app";

	private static final long GIB = 1024L * 1024 * 1024;

	KubernetesClient client;

	private ClusterRegistry registry() {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register("mock", "mock", this.client);
		return registry;
	}

	/**
	 * The whole wiring, as the app assembles it — the contexts come from the same beans
	 * the overview endpoint summarises with, which is the point being tested.
	 */
	private StatusContexts contexts(PrometheusMetricService metrics) {
		ClusterRegistry registry = registry();
		ResourceService resources = new ResourceService(registry);
		return new StatusContexts(new NetworkHealthService(registry, resources),
				new StorageHealthService(resources, metrics), new ConfigUsageService(registry, resources));
	}

	private PrometheusMetricService noMetrics() {
		return new PrometheusMetricService(registry(), new MetricsProperties());
	}

	/** A metrics backend that reports exactly the readings a test seeds. */
	private PrometheusMetricService metricsReporting(Map<String, VolumeUsage> readings) {
		return new PrometheusMetricService(registry(), new MetricsProperties()) {
			@Override
			public Map<String, VolumeUsage> volumeUsage(String clusterId) {
				return readings;
			}
		};
	}

	// --- seeding ---

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
				.addToRequests("storage", new io.fabric8.kubernetes.api.model.Quantity(requested))
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

	/** A pod that mounts a ConfigMap and a Secret — the reference the scan looks for. */
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

	// --- the two halves ---

	private Map<String, Integer> counts(KindHealth kind) {
		assertThat(kind.error()).isNull();
		Map<String, Integer> out = new TreeMap<>();
		for (StateCount state : kind.states()) {
			out.put(state.label(), state.count());
		}
		return out;
	}

	private List<GenericKubernetesResource> rows(ResourceDescriptor descriptor, StatusContexts contexts) {
		StatusContext context = contexts.open("mock", descriptor.kind(), NS);
		return ListProjection.forList(new ResourceService(registry()).listRaw("mock", descriptor, NS), context);
	}

	private Map<String, Integer> rowCounts(ResourceDescriptor descriptor, StatusContexts contexts) {
		Map<String, Integer> out = new TreeMap<>();
		for (GenericKubernetesResource row : rows(descriptor, contexts)) {
			ObjectState state = (ObjectState) row.getAdditionalProperties().get(StatusVocabulary.FIELD);
			if (state != null) {
				out.merge(state.label(), 1, Integer::sum);
			}
		}
		return out;
	}

	// --- Network ---

	private void seedServices() {
		// Serving (field-backed only in the sense that something IS behind it), plus both
		// broken causes, plus an ExternalName that must NOT be flagged — the decoy that
		// catches "no endpoints means broken".
		service("web", "ClusterIP");
		endpoints("web", 2, 0);
		service("api", "ClusterIP");
		endpoints("api", 1, 0);
		service("orphan", "ClusterIP");
		service("starting", "ClusterIP");
		endpoints("starting", 0, 3);
		service("external", "ExternalName");
	}

	@Test
	void everyStateTheServicesCardCountsSelectsExactlyThoseRows() {
		seedServices();
		StatusContexts contexts = contexts(noMetrics());
		ClusterRegistry registry = registry();
		NetworkHealthService network = new NetworkHealthService(registry, new ResourceService(registry));

		Map<String, Integer> card = counts(network.summarise("mock", NS).get(0));

		assertThat(card).containsExactlyInAnyOrderEntriesOf(Map.of("Serving", 3, "No endpoints", 1, "Not ready", 1));
		assertThat(rowCounts(WellKnownKinds.SERVICES, contexts)).isEqualTo(card);
	}

	// --- Storage ---

	private void seedClaims() {
		claim("data", "Bound", "10Gi");
		claim("logs", "Bound", "10Gi");
		claim("cache", "Bound", "10Gi");
		claim("waiting", "Pending", "5Gi");
		claim("gone", "Lost", "5Gi");
	}

	@Test
	void aBindingPhaseAndAReadingOnlyTheMetricsBackendHasBothAgree() {
		// One field-backed state (Bound / Pending / Lost, straight off status.phase) and
		// one that is nowhere on the object at all (Nearly full). The reading is
		// plausible for `data` and implausible for `logs` — kubelet reporting the whole
		// backing disk — so "Nearly full" must take one row and not two.
		seedClaims();
		PrometheusMetricService metrics = metricsReporting(
				Map.of("app/data", new VolumeUsage(NS, "data", (long) (9.5 * GIB), 10 * GIB), "app/logs",
						new VolumeUsage(NS, "logs", 900 * GIB, 1000 * GIB)));
		StatusContexts contexts = contexts(metrics);
		StorageHealthService storage = new StorageHealthService(new ResourceService(registry()), metrics);

		Map<String, Integer> card = counts(storage.summarise("mock", NS).get(0));

		assertThat(card)
			.containsExactlyInAnyOrderEntriesOf(Map.of("Bound", 2, "Nearly full", 1, "Pending", 1, "Lost", 1));
		assertThat(rowCounts(WellKnownKinds.PERSISTENT_VOLUME_CLAIMS, contexts)).isEqualTo(card);
	}

	// --- Config ---

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

	@Test
	void aReferenceScanOfTheNamespaceReachesTheRowsToo() {
		seedConfig();
		StatusContexts contexts = contexts(noMetrics());
		ClusterRegistry registry = registry();
		ConfigUsageService config = new ConfigUsageService(registry, new ResourceService(registry));

		List<KindHealth> summary = config.summarise("mock", NS);

		assertThat(counts(summary.get(0)))
			.containsExactlyInAnyOrderEntriesOf(Map.of("Referenced", 1, "Not referenced", 2));
		assertThat(rowCounts(WellKnownKinds.CONFIG_MAPS, contexts)).isEqualTo(counts(summary.get(0)));
		// Secrets add a state the object itself carries — `type` — beside the derived
		// one.
		assertThat(counts(summary.get(1)))
			.containsExactlyInAnyOrderEntriesOf(Map.of("Referenced", 1, "Not referenced", 1, "Cluster-managed", 2));
		assertThat(rowCounts(WellKnownKinds.SECRETS, contexts)).isEqualTo(counts(summary.get(1)));
	}

	@Test
	void aSecretRowKeepsItsStateWhileLosingItsValues() {
		// The two things ListProjection does to a Secret must not fight: the values are
		// stripped to keys, and the state is still there for a status: term to select.
		seedConfig();

		String json = Serialization.asJson(rows(WellKnownKinds.SECRETS, contexts(noMetrics())));

		assertThat(json).doesNotContain("dmFsdWU=").contains("\"kweblensState\"").contains("\"label\":\"Referenced\"");
	}

	// --- the negative cases ---

	@Test
	void withoutItsContextAContextCarryingKindShipsNoStateAtAll() {
		// Not "OK": an object nobody scanned must not be selectable as healthy. This is
		// what a caller that forgot to open a context gets, and it is the safe answer.
		seedConfig();
		seedServices();

		List<GenericKubernetesResource> configMaps = ListProjection.forList(
				new ResourceService(registry()).listRaw("mock", WellKnownKinds.CONFIG_MAPS, NS), StatusContext.none());
		List<GenericKubernetesResource> services = ListProjection.forList(
				new ResourceService(registry()).listRaw("mock", WellKnownKinds.SERVICES, NS), StatusContext.none());

		assertThat(configMaps).isNotEmpty()
			.allSatisfy((row) -> assertThat(row.getAdditionalProperties()).doesNotContainKey(StatusVocabulary.FIELD));
		assertThat(services).isNotEmpty()
			.allSatisfy((row) -> assertThat(row.getAdditionalProperties()).doesNotContainKey(StatusVocabulary.FIELD));
	}

	@Test
	void aContextIsOpenedOnlyForTheKindItJudges() {
		// The context opened for a Services list must not answer about a ConfigMap: the
		// verdicts are different questions, and a context that answered anything asked of
		// it would put a Service's vocabulary on a Config row.
		seedServices();
		seedConfig();

		StatusContext forServices = contexts(noMetrics()).open("mock", "Service", NS);
		GenericKubernetesResource configMap = new ResourceService(registry())
			.listRaw("mock", WellKnownKinds.CONFIG_MAPS, NS)
			.get(0);

		assertThat(forServices.state("ConfigMap", configMap)).isNull();
	}

	@Test
	void theComparisonIsCapableOfFailing() {
		// The mutation the epic asks for: move one object between states and the two
		// sides must stop agreeing. Without it, "the maps are equal" could be a claim
		// about a comparison that never looked at anything.
		seedServices();
		StatusContexts contexts = contexts(noMetrics());
		ClusterRegistry registry = registry();
		Map<String, Integer> card = counts(
				new NetworkHealthService(registry, new ResourceService(registry)).summarise("mock", NS).get(0));

		Map<String, Integer> perturbed = new TreeMap<>(rowCounts(WellKnownKinds.SERVICES, contexts));
		perturbed.merge("Serving", -1, Integer::sum);
		perturbed.merge("No endpoints", 1, Integer::sum);

		assertThat(perturbed).isNotEqualTo(card);
	}

}

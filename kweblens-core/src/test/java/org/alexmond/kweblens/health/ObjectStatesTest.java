package org.alexmond.kweblens.health;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.metric.MetricsProperties;
import org.alexmond.kweblens.metric.PrometheusMetricService;
import org.alexmond.kweblens.metric.VolumeUsage;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.resource.WellKnownKinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>Every verdict kweblens has is reachable from {@code kweblens-core} alone, and a list
 * costs one context rather than one per row.</b>
 *
 * <p>
 * The pieces were already here — {@link StatusVocabulary} has been public and in core
 * since #337, {@link StatusContexts} since #340, and the health checks call both. What
 * was missing is the composition over a <i>list</i>: every caller wired {@code open()} to
 * a per-row {@code state()} by hand. {@link ObjectStates} is that wiring, and this file
 * is what makes it a guarantee rather than a habit.
 *
 * <h2>The two claims</h2>
 *
 * <ol>
 * <li><b>All 13 kinds, no web module.</b> One object of each covered kind, in a state a
 * blanket "OK" could not have produced, asserted on the exact {@code label} and
 * {@code tone} the SPA renders — and an Event, which nothing judges, asserted
 * <b>absent</b>. {@link #theWebModuleIsNotOnThisTestsClasspath()} proves the "no web
 * type" half by measurement rather than by comment.
 * <li><b>Once per list.</b> The expensive mistake this design exists to prevent is
 * opening the context per row: on ConfigMaps and Secrets that is a three-list namespace
 * scan multiplied by the row count. It produces <i>identical output</i>, so no comparison
 * of labels can catch it — only a count of opens can, which is what
 * {@link #aNamespaceWideListOpensTheContextOnce()} does.
 * {@link #theOpenedOnceAssertionIsCapableOfFailing()} then aims that same assertion at a
 * deliberately per-row composition and shows it going red, because a green assertion that
 * has never failed pins nothing.
 * </ol>
 */
@EnableKubernetesMockClient(crud = true)
class ObjectStatesTest {

	private static final String CLUSTER = "mock";

	private static final String NS = "app";

	private static final ResourceDescriptor DEPLOYMENTS = ResourceDescriptor.namespaced("deployments", "Deployments",
			"Deployment", "apps", "v1", "deployments");

	private static final ResourceDescriptor STATEFUL_SETS = ResourceDescriptor.namespaced("statefulsets",
			"Stateful Sets", "StatefulSet", "apps", "v1", "statefulsets");

	private static final ResourceDescriptor REPLICA_SETS = ResourceDescriptor.namespaced("replicasets", "Replica Sets",
			"ReplicaSet", "apps", "v1", "replicasets");

	private static final ResourceDescriptor DAEMON_SETS = ResourceDescriptor.namespaced("daemonsets", "Daemon Sets",
			"DaemonSet", "apps", "v1", "daemonsets");

	private static final ResourceDescriptor JOBS = ResourceDescriptor.namespaced("jobs", "Jobs", "Job", "batch", "v1",
			"jobs");

	private static final ResourceDescriptor CRON_JOBS = ResourceDescriptor.namespaced("cronjobs", "Cron Jobs",
			"CronJob", "batch", "v1", "cronjobs");

	KubernetesClient client;

	// --- the wiring, exactly as core assembles it ---

	private ClusterRegistry registry() {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register(CLUSTER, CLUSTER, this.client);
		return registry;
	}

	private ResourceService resources() {
		return new ResourceService(registry());
	}

	/**
	 * A metrics backend that reports exactly what a test hands it. Explicit rather than a
	 * real {@link PrometheusMetricService} against the mock API server, because a failure
	 * to reach one is caught by {@link StatusContexts} and would silently turn a claim's
	 * verdict into "absent" — which is the safe behaviour in production and a useless one
	 * to assert against here.
	 */
	private PrometheusMetricService metricsReporting(Map<String, VolumeUsage> readings) {
		return new PrometheusMetricService(registry(), new MetricsProperties()) {
			@Override
			public Map<String, VolumeUsage> volumeUsage(String clusterId) {
				return readings;
			}
		};
	}

	private StatusContexts statusContexts() {
		ClusterRegistry registry = registry();
		ResourceService resources = new ResourceService(registry);
		return new StatusContexts(new NetworkHealthService(registry, resources),
				new StorageHealthService(resources, metricsReporting(Map.of())),
				new ConfigUsageService(registry, resources));
	}

	private CountingContexts countingContexts() {
		ClusterRegistry registry = registry();
		ResourceService resources = new ResourceService(registry);
		return new CountingContexts(new NetworkHealthService(registry, resources),
				new StorageHealthService(resources, metricsReporting(Map.of())),
				new ConfigUsageService(registry, resources));
	}

	// --- seeding ---

	/**
	 * Create one object from its manifest, through the same generic path that lists it.
	 */
	private void seed(ResourceDescriptor descriptor, String manifest) {
		GenericKubernetesResource object = Serialization.unmarshal(manifest, GenericKubernetesResource.class);
		var op = this.client
			.genericKubernetesResources(new ResourceDefinitionContext.Builder().withGroup(descriptor.group())
				.withVersion(descriptor.version())
				.withKind(descriptor.kind())
				.withPlural(descriptor.plural())
				.withNamespaced(descriptor.namespaced())
				.build());
		if (descriptor.namespaced()) {
			op.inNamespace(NS).resource(object).create();
		}
		else {
			op.resource(object).create();
		}
	}

	/**
	 * The seven kinds whose verdict is a pure function of the object — each in a
	 * <i>different</i> state, so an implementation that answered the same thing about
	 * everything could not pass.
	 */
	private void seedWorkloads() {
		seed(WellKnownKinds.PODS, """
				apiVersion: v1
				kind: Pod
				metadata: {name: web, namespace: app}
				status: {phase: Running}
				""");
		seed(DEPLOYMENTS, """
				apiVersion: apps/v1
				kind: Deployment
				metadata: {name: api, namespace: app}
				spec: {replicas: 2}
				status: {readyReplicas: 1}
				""");
		seed(STATEFUL_SETS, """
				apiVersion: apps/v1
				kind: StatefulSet
				metadata: {name: db, namespace: app}
				spec: {replicas: 1}
				status: {readyReplicas: 1}
				""");
		seed(REPLICA_SETS, """
				apiVersion: apps/v1
				kind: ReplicaSet
				metadata: {name: web-old, namespace: app}
				spec: {replicas: 0}
				""");
		seed(DAEMON_SETS, """
				apiVersion: apps/v1
				kind: DaemonSet
				metadata: {name: agent, namespace: app}
				status: {desiredNumberScheduled: 2, numberReady: 2}
				""");
		seed(JOBS, """
				apiVersion: batch/v1
				kind: Job
				metadata: {name: import, namespace: app}
				status: {}
				""");
		seed(CRON_JOBS, """
				apiVersion: batch/v1
				kind: CronJob
				metadata: {name: nightly, namespace: app}
				spec: {schedule: "0 3 * * *", suspend: true}
				""");
	}

	/** Node and Namespace — cluster-scoped, and added to the vocabulary by #339. */
	private void seedClusterObjects() {
		seed(WellKnownKinds.NODES, """
				apiVersion: v1
				kind: Node
				metadata: {name: node-1}
				spec: {unschedulable: true}
				status: {conditions: [{type: Ready, status: "True"}]}
				""");
		seed(WellKnownKinds.NAMESPACES, """
				apiVersion: v1
				kind: Namespace
				metadata: {name: app}
				status: {phase: Active}
				""");
	}

	/**
	 * The four kinds whose verdict is not in the object (#340). No Endpoints, no volume
	 * reading and no pod mounting anything, so each one lands on the state its <i>second
	 * collection</i> decides — which is the half a row-only implementation cannot reach.
	 */
	private void seedContextCarrying() {
		seed(WellKnownKinds.SERVICES, """
				apiVersion: v1
				kind: Service
				metadata: {name: orphan, namespace: app}
				spec: {type: ClusterIP}
				""");
		seed(WellKnownKinds.PERSISTENT_VOLUME_CLAIMS, """
				apiVersion: v1
				kind: PersistentVolumeClaim
				metadata: {name: data, namespace: app}
				spec: {resources: {requests: {storage: 10Gi}}}
				status: {phase: Bound}
				""");
		configMap("orphaned");
		seed(WellKnownKinds.SECRETS, """
				apiVersion: v1
				kind: Secret
				metadata: {name: sa-token, namespace: app}
				type: kubernetes.io/service-account-token
				data: {token: dmFsdWU=}
				""");
	}

	private void configMap(String name) {
		seed(WellKnownKinds.CONFIG_MAPS, """
				apiVersion: v1
				kind: ConfigMap
				metadata: {name: %s, namespace: app}
				data: {log-level: debug}
				""".formatted(name));
	}

	// --- the question this class exists to answer ---

	/**
	 * Every object's state, by name — through the composition, listed and judged with the
	 * same scope. Asserts the positional contract on the way past, because a caller pairs
	 * the two lists by index and a size mismatch would pair the wrong verdict with the
	 * wrong object rather than fail.
	 */
	private Map<String, Optional<ObjectState>> statesByName(ResourceDescriptor descriptor) {
		String namespace = descriptor.namespaced() ? NS : null;
		List<GenericKubernetesResource> objects = resources().listRaw(CLUSTER, descriptor, namespace);
		List<Optional<ObjectState>> states = new ObjectStates(statusContexts()).forList(CLUSTER, descriptor.kind(),
				namespace, objects);

		assertThat(objects).as("nothing seeded for " + descriptor.kind()).isNotEmpty();
		assertThat(states).as("one state per object, in order").hasSameSizeAs(objects);
		Map<String, Optional<ObjectState>> byName = new LinkedHashMap<>();
		for (int i = 0; i < objects.size(); i++) {
			byName.put(objects.get(i).getMetadata().getName(), states.get(i));
		}
		return byName;
	}

	private Optional<ObjectState> stateOf(ResourceDescriptor descriptor, String name) {
		Map<String, Optional<ObjectState>> byName = statesByName(descriptor);
		assertThat(byName).containsKey(name);
		return byName.get(name);
	}

	private Optional<ObjectState> state(String label, String tone) {
		return Optional.of(new ObjectState(label, tone));
	}

	// --- claim 1: all 13 kinds, from core ---

	@Test
	void theSevenPureFunctionKindsAreJudgedFromTheObjectAlone() {
		// The labels are the ones the SPA prints in its Status column, character for
		// character: it renders `kweblensState.label` and nothing else.
		seedWorkloads();

		assertThat(stateOf(WellKnownKinds.PODS, "web")).isEqualTo(state("Running", StateCount.OK));
		assertThat(stateOf(DEPLOYMENTS, "api")).isEqualTo(state("Unavailable", StateCount.ERR));
		assertThat(stateOf(STATEFUL_SETS, "db")).isEqualTo(state("Healthy", StateCount.OK));
		assertThat(stateOf(REPLICA_SETS, "web-old")).isEqualTo(state("Idle", StateCount.IDLE));
		assertThat(stateOf(DAEMON_SETS, "agent")).isEqualTo(state("Ready", StateCount.OK));
		assertThat(stateOf(JOBS, "import")).isEqualTo(state("Active", StateCount.OK));
		assertThat(stateOf(CRON_JOBS, "nightly")).isEqualTo(state("Suspended", StateCount.WARN));
	}

	@Test
	void theTwoClusterScopedKindsAreJudgedToo() {
		// Cordoned keeps kubectl's spelling, which is the whole of #339's Node rule, and
		// it travels to a core consumer exactly as it travels to the browser.
		seedClusterObjects();

		assertThat(stateOf(WellKnownKinds.NODES, "node-1"))
			.isEqualTo(state("Ready,SchedulingDisabled", StateCount.WARN));
		assertThat(stateOf(WellKnownKinds.NAMESPACES, "app")).isEqualTo(state("Active", StateCount.OK));
	}

	@Test
	void theFourContextCarryingKindsAreJudgedWithTheirSecondCollection() {
		// None of these four verdicts is anywhere on the row: "No endpoints" is the
		// Endpoints collection, "Bound" is read off the claim but "Nearly full" would not
		// be, and both config states are a scan of the namespace. This is the half that
		// used to need the caller to know to open a context first.
		seedWorkloads();
		seedContextCarrying();

		assertThat(stateOf(WellKnownKinds.SERVICES, "orphan")).isEqualTo(state("No endpoints", StateCount.ERR));
		assertThat(stateOf(WellKnownKinds.PERSISTENT_VOLUME_CLAIMS, "data")).isEqualTo(state("Bound", StateCount.OK));
		assertThat(stateOf(WellKnownKinds.CONFIG_MAPS, "orphaned")).isEqualTo(state("Not referenced", StateCount.WARN));
		assertThat(stateOf(WellKnownKinds.SECRETS, "sa-token")).isEqualTo(state("Cluster-managed", StateCount.IDLE));
	}

	@Test
	void thirteenIsTheNumberTheTwoPredicatesName() {
		// The three tests above assert on 13 kinds; this is what says 13 is the whole set
		// rather than the set someone happened to write down. covers() and needsContext()
		// stay two questions on purpose — broadening the first would hand a Service to
		// WorkloadHealth's default and get a bare "OK" out of it.
		List<String> pureFunction = List.of("Pod", "Deployment", "StatefulSet", "ReplicaSet", "DaemonSet", "Job",
				"CronJob", "Node", "Namespace");
		List<String> contextCarrying = List.of("Service", "PersistentVolumeClaim", "ConfigMap", "Secret");

		assertThat(pureFunction).hasSize(9)
			.allSatisfy((kind) -> assertThat(StatusVocabulary.covers(kind)).as(kind).isTrue())
			.allSatisfy((kind) -> assertThat(StatusVocabulary.needsContext(kind)).as(kind).isFalse());
		assertThat(contextCarrying).hasSize(4)
			.allSatisfy((kind) -> assertThat(StatusVocabulary.needsContext(kind)).as(kind).isTrue())
			.allSatisfy((kind) -> assertThat(StatusVocabulary.covers(kind)).as(kind).isFalse());
	}

	@Test
	void anUncoveredKindIsAbsentRatherThanNullValuedOrEmpty() {
		// "We do not judge this kind" and "this kind is fine" are different claims. An
		// Event's Warning/Normal is its `type` — a property of a report ABOUT another
		// object — so #339 excluded it deliberately, and an empty Optional is how that
		// refusal survives the trip to a consumer.
		seed(WellKnownKinds.EVENTS, """
				apiVersion: v1
				kind: Event
				metadata: {name: boot, namespace: app}
				type: Warning
				reason: Started
				""");

		Optional<ObjectState> state = stateOf(WellKnownKinds.EVENTS, "boot");

		assertThat(state).isEmpty();
		assertThat(state).isNotEqualTo(Optional.of(new ObjectState("", "")));
		assertThat(state).isNotEqualTo(Optional.of(new ObjectState(null, null)));
	}

	@Test
	void theWebModuleIsNotOnThisTestsClasspath() {
		// The "using only core services" half of the ticket, measured rather than
		// asserted in a comment: if kweblens-web ever became a test dependency of core
		// this file would keep passing while proving nothing.
		assertThatThrownBy(() -> Class.forName("org.alexmond.kweblens.web.api.ListProjection"))
			.isInstanceOf(ClassNotFoundException.class);
	}

	@Test
	void anEmptyListOpensNothingAtAll() {
		// No row for a context to be consulted about, so paying for one would be this
		// class's own cost spent on nothing.
		CountingContexts contexts = countingContexts();

		assertThat(new ObjectStates(contexts).forList(CLUSTER, "ConfigMap", NS, List.of())).isEmpty();
		assertThat(new ObjectStates(contexts).forList(CLUSTER, "ConfigMap", NS, null)).isEmpty();
		assertThat(contexts.opens()).isZero();
	}

	// --- claim 2: once per list, and an assertion that can say so ---

	/**
	 * The assertion itself, aimed at a composition rather than at {@link ObjectStates},
	 * so the same words can be pointed at the wrong implementation and shown to fail.
	 */
	private void assertOpensTheContextOncePerList(Composition composition, CountingContexts contexts) {
		List<GenericKubernetesResource> configMaps = resources().listRaw(CLUSTER, WellKnownKinds.CONFIG_MAPS, NS);
		assertThat(configMaps).as("with one row, 'once' and 'per row' are the same number").hasSizeGreaterThan(1);

		List<Optional<ObjectState>> states = composition.forList(CLUSTER, "ConfigMap", NS, configMaps);

		assertThat(states).hasSameSizeAs(configMaps).allSatisfy((state) -> assertThat(state).isPresent());
		assertThat(contexts.opens()).as("StatusContexts.open calls for one namespace-wide list").isEqualTo(1);
	}

	@Test
	void aNamespaceWideListOpensTheContextOnce() {
		// ConfigMaps because their context is the expensive one — pods, service accounts
		// and ingresses over the namespace, +80-120 ms — so per row it is that, times the
		// row count, on every list of a kind an operator opens constantly.
		configMap("a");
		configMap("b");
		configMap("c");
		CountingContexts contexts = countingContexts();

		assertOpensTheContextOncePerList(new ObjectStates(contexts)::forList, contexts);
	}

	@Test
	void theOpenedOnceAssertionIsCapableOfFailing() {
		// The mutation the design turns on. Note what the per-row composition gets RIGHT:
		// the identical states, in the identical order. Nothing about the output is
		// wrong, which is exactly why counting the opens is the only thing that can catch
		// it — and why this assertion has to be shown failing to be worth having.
		configMap("a");
		configMap("b");
		configMap("c");
		CountingContexts contexts = countingContexts();
		List<GenericKubernetesResource> configMaps = resources().listRaw(CLUSTER, WellKnownKinds.CONFIG_MAPS, NS);

		assertThat(perRow(contexts).forList(CLUSTER, "ConfigMap", NS, configMaps))
			.isEqualTo(new ObjectStates(statusContexts()).forList(CLUSTER, "ConfigMap", NS, configMaps));

		CountingContexts perRowContexts = countingContexts();
		assertThatThrownBy(() -> assertOpensTheContextOncePerList(perRow(perRowContexts), perRowContexts))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("StatusContexts.open calls for one namespace-wide list");
		assertThat(perRowContexts.opens()).isEqualTo(configMaps.size());
	}

	/** The mistake, written out: a context opened inside the walk over the rows. */
	private Composition perRow(StatusContexts contexts) {
		return (clusterId, kind, namespace, objects) -> objects.stream()
			.map((object) -> Optional
				.ofNullable(StatusVocabulary.state(kind, object, contexts.open(clusterId, kind, namespace))))
			.toList();
	}

	// Nested types last (Checkstyle InnerTypeLast).

	/** Either {@link ObjectStates#forList} or the per-row mistake it forbids. */
	@FunctionalInterface
	private interface Composition {

		List<Optional<ObjectState>> forList(String clusterId, String kind, String namespace,
				List<GenericKubernetesResource> objects);

	}

	/**
	 * The seam the "once" claim is measured through: the real {@link StatusContexts},
	 * counting how many times it was asked to open one.
	 */
	private static final class CountingContexts extends StatusContexts {

		private final AtomicInteger opens = new AtomicInteger();

		CountingContexts(NetworkHealthService network, StorageHealthService storage, ConfigUsageService config) {
			super(network, storage, config);
		}

		@Override
		public StatusContext open(String clusterId, String kind, String namespace) {
			this.opens.incrementAndGet();
			return super.open(clusterId, kind, namespace);
		}

		int opens() {
			return this.opens.get();
		}

	}

}

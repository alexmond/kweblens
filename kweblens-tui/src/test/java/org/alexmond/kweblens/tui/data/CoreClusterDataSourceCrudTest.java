package org.alexmond.kweblens.tui.data;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.health.ObjectState;
import org.alexmond.kweblens.resource.WellKnownKinds;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rest of the port against the in-JVM API server: which clusters it can address,
 * whether verdicts come back positionally, and whether a watch delivers and stops.
 */
@EnableKubernetesMockClient(crud = true)
class CoreClusterDataSourceCrudTest {

	KubernetesClient client;

	private ResourceQuery pods() {
		return new ResourceQuery(CoreStack.CLUSTER, WellKnownKinds.PODS, "default");
	}

	private void seedPod(String name, String phase) {
		this.client.pods()
			.inNamespace("default")
			.resource(new PodBuilder().withNewMetadata()
				.withName(name)
				.withNamespace("default")
				.endMetadata()
				.withNewStatus()
				.withPhase(phase)
				.endStatus()
				.build())
			.create();
	}

	@Test
	void clustersAreTheRegistrysIdsAndNothingIsAssumedToBeCalledDefault() {
		ClusterRegistry registry = CoreStack.registry(this.client);

		assertThat(CoreStack.dataSource(registry).clusters()).containsExactly(CoreStack.CLUSTER);
	}

	@Test
	void statesLineUpWithTheRowsTheyDescribe() {
		seedPod("running-one", "Running");
		seedPod("failed-one", "Failed");
		CoreClusterDataSource source = CoreStack.dataSource(this.client);

		List<GenericKubernetesResource> page = this.client.genericKubernetesResources("v1", "Pod")
			.inNamespace("default")
			.list()
			.getItems();
		List<Optional<ObjectState>> states = source.states(pods(), page);

		assertThat(states).as("positional: same size and order as the objects").hasSameSizeAs(page);
		assertThat(states).allSatisfy((state) -> assertThat(state).isPresent());
		// Not asserting the exact words: the status vocabulary is OPEN — three producers
		// pass cluster values straight through — so an exhaustive expectation here would
		// be a second, stale copy of a rule that lives in core. What must be true is that
		// two differently-phased pods do not collapse to one label, which is what a
		// constant or a mis-wired context would look like.
		assertThat(states.stream().map((state) -> state.map(ObjectState::label).orElse("")).distinct())
			.as("a Running pod and a Failed pod must not read the same")
			.hasSize(2);
	}

	@Test
	void statesOfAnEmptyPageOpenNothingAndAnswerNothing() {
		assertThat(CoreStack.dataSource(this.client).states(pods(), List.of())).isEmpty();
	}

	@Test
	void watchDeliversEventsAndStopsWhenTheSubscriptionIsClosed() {
		CoreClusterDataSource source = CoreStack.dataSource(this.client);
		List<String> actions = new CopyOnWriteArrayList<>();
		List<WatchEnd> ends = new CopyOnWriteArrayList<>();

		try (Subscription subscription = source.watch(pods(), (action, object) -> actions.add(action), ends::add)) {
			seedPod("watched", "Pending");
			Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> !actions.isEmpty());
		}

		assertThat(actions).contains("ADDED");
	}

	/**
	 * The half of GH#413 that has to be true against the real client, not a double.
	 *
	 * <p>
	 * fabric8 routes a locally requested close through the watcher's no-argument
	 * {@code onClose()} — {@code Watch.close()} calls {@code closeEvent()} — so the
	 * ending the TUI causes and the ending it needs to hear about arrive on the same code
	 * path. If this ever reported, quitting would announce a lost watch on the way out
	 * and, far worse, every reconnect would read its own close of the previous handle as
	 * a fresh failure and reconnect forever.
	 */
	@Test
	void closingASubscriptionIsNotReportedAsAWatchThatDied() {
		CoreClusterDataSource source = CoreStack.dataSource(this.client);
		List<String> actions = new CopyOnWriteArrayList<>();
		List<WatchEnd> ends = new CopyOnWriteArrayList<>();

		Subscription subscription = source.watch(pods(), (action, object) -> actions.add(action), ends::add);
		seedPod("quietly-closed", "Pending");
		Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> !actions.isEmpty());
		subscription.close();

		// The positive control is the assertion above it: the watch was demonstrably open
		// and delivering, so "no ending reported" is a decision and not a watch that
		// never
		// started. Give any asynchronous callback room to arrive before believing it.
		Awaitility.await()
			.during(500, TimeUnit.MILLISECONDS)
			.atMost(5, TimeUnit.SECONDS)
			.untilAsserted(() -> assertThat(ends).isEmpty());
	}

}

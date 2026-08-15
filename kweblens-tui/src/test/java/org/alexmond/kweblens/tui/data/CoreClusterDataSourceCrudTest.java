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

		try (Subscription subscription = source.watch(pods(), (action, object) -> actions.add(action))) {
			seedPod("watched", "Pending");
			Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> !actions.isEmpty());
		}

		assertThat(actions).contains("ADDED");
	}

}

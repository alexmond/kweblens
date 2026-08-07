package org.alexmond.kweblens.cluster;

import java.util.ArrayList;
import java.util.List;

import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ClusterRegistryTest {

	private KubernetesClient clientPointedAt(String masterUrl) {
		return new KubernetesClientBuilder().withConfig(new ConfigBuilder().withMasterUrl(masterUrl).build()).build();
	}

	@Test
	void registersAndListsClustersSortedById() {
		try (ClusterRegistry registry = new ClusterRegistry()) {
			registry.register("prod", "Production", clientPointedAt("https://prod:6443/"));
			registry.register("dev", "Development", clientPointedAt("https://dev:6443/"));

			assertThat(registry.list()).extracting(ClusterInfo::id).containsExactly("dev", "prod");
			assertThat(registry.info("prod")).get().extracting(ClusterInfo::name).isEqualTo("Production");
		}
	}

	@Test
	void requireThrowsForUnknownCluster() {
		try (ClusterRegistry registry = new ClusterRegistry()) {
			assertThat(registry.client("nope")).isEmpty();
			assertThatThrownBy(() -> registry.require("nope")).isInstanceOf(UnknownClusterException.class)
				.hasMessageContaining("nope");
		}
	}

	@Test
	void unregisterClosesTheClientAndDropsTheEntry() {
		try (ClusterRegistry registry = new ClusterRegistry()) {
			KubernetesClient client = mock(KubernetesClient.class);
			registry.register("gone", "Gone", client, ClusterOrigin.RUNTIME);

			assertThat(registry.unregister("gone")).isTrue();

			// Closing is the point: a removed cluster must stop holding its connection
			// pool, not merely vanish from the list.
			verify(client).close();
			assertThat(registry.list()).isEmpty();
			assertThat(registry.unregister("gone")).isFalse();
		}
	}

	@Test
	void originIsReportedSoTheUiKnowsWhatIsEditable() {
		try (ClusterRegistry registry = new ClusterRegistry()) {
			registry.register("declared", "Declared", clientPointedAt("https://198.51.100.10:6443/"));
			registry.register("added", "Added", clientPointedAt("https://198.51.100.11:6443/"), ClusterOrigin.RUNTIME);

			assertThat(registry.info("declared")).get().extracting(ClusterInfo::origin).isEqualTo(ClusterOrigin.STATIC);
			assertThat(registry.info("added")).get().extracting(ClusterInfo::origin).isEqualTo(ClusterOrigin.RUNTIME);
		}
	}

	@Test
	void reRegisteringSameIdReplacesTheEntry() {
		try (ClusterRegistry registry = new ClusterRegistry()) {
			registry.register("c1", "First", clientPointedAt("https://one:6443/"));
			registry.register("c1", "Second", clientPointedAt("https://two:6443/"));

			assertThat(registry.list()).hasSize(1);
			assertThat(registry.info("c1")).get().extracting(ClusterInfo::name).isEqualTo("Second");
		}
	}

	@Test
	void closingAClusterTellsWhoeverElseWasHoldingSomethingForIt() {
		List<String> closed = new ArrayList<>();
		try (ClusterRegistry registry = new ClusterRegistry()) {
			registry.addClientListener(closed::add);

			// Removal.
			registry.register("gone", "Gone", clientPointedAt("https://one:6443/"), ClusterOrigin.RUNTIME);
			registry.unregister("gone");
			// Re-pointing: the old client is closed just the same, so a port-forward or a
			// cached schema derived from it is just as dead.
			registry.register("moved", "Moved", clientPointedAt("https://two:6443/"), ClusterOrigin.RUNTIME);
			registry.register("moved", "Moved", clientPointedAt("https://three:6443/"), ClusterOrigin.RUNTIME);

			assertThat(closed).containsExactly("gone", "moved");
		}
	}

	@Test
	void reRegisteringTheSameClientIsNotAClosure() {
		List<String> closed = new ArrayList<>();
		try (ClusterRegistry registry = new ClusterRegistry()) {
			registry.addClientListener(closed::add);
			KubernetesClient client = clientPointedAt("https://one:6443/");

			registry.register("same", "Same", client);
			// A rename re-registers the identical instance; nothing derived from it died,
			// so nothing may be torn down.
			registry.register("same", "Renamed", client);

			assertThat(closed).isEmpty();
			assertThat(registry.client("same")).contains(client);
		}
	}

	@Test
	void aListenerThatThrowsDoesNotStopTheRemoval() {
		try (ClusterRegistry registry = new ClusterRegistry()) {
			registry.addClientListener((id) -> {
				throw new IllegalStateException("could not release " + id);
			});
			KubernetesClient client = mock(KubernetesClient.class);
			registry.register("gone", "Gone", client, ClusterOrigin.RUNTIME);

			assertThat(registry.unregister("gone")).isTrue();

			verify(client).close();
			assertThat(registry.list()).isEmpty();
		}
	}

	@Test
	void shutdownDoesNotFirePerClusterCallbacks() {
		List<String> closed = new ArrayList<>();
		ClusterRegistry registry = new ClusterRegistry();
		registry.addClientListener(closed::add);
		registry.register("c1", "One", clientPointedAt("https://one:6443/"));

		registry.close();

		// Every holder closes its own on shutdown; firing removal callbacks here would
		// have them racing the container over the same objects.
		assertThat(closed).isEmpty();
	}

}

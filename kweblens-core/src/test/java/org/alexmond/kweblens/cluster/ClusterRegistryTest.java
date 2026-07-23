package org.alexmond.kweblens.cluster;

import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
	void reRegisteringSameIdReplacesTheEntry() {
		try (ClusterRegistry registry = new ClusterRegistry()) {
			registry.register("c1", "First", clientPointedAt("https://one:6443/"));
			registry.register("c1", "Second", clientPointedAt("https://two:6443/"));

			assertThat(registry.list()).hasSize(1);
			assertThat(registry.info("c1")).get().extracting(ClusterInfo::name).isEqualTo("Second");
		}
	}

}

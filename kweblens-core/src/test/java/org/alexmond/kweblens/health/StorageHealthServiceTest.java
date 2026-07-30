package org.alexmond.kweblens.health;

import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An unbound claim is a pod that will never start; the reason should point at the class.
 */
@EnableKubernetesMockClient(crud = true)
class StorageHealthServiceTest {

	KubernetesClient client;

	private StorageHealthService service() {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register("mock", "mock", this.client);
		return new StorageHealthService(registry);
	}

	private void claim(String name, String phase, String storageClass) {
		this.client.persistentVolumeClaims()
			.inNamespace("app")
			.resource(new PersistentVolumeClaimBuilder().withNewMetadata()
				.withName(name)
				.withNamespace("app")
				.endMetadata()
				.withNewSpec()
				.withStorageClassName(storageClass)
				.endSpec()
				.withNewStatus()
				.withPhase(phase)
				.endStatus()
				.build())
			.create();
	}

	private KindHealth summary() {
		return service().summarise("mock", "app").get(0);
	}

	@Test
	void countsABoundClaimAsHealthy() {
		claim("data", "Bound", "nfs");
		KindHealth health = summary();
		assertThat(health.ok()).isEqualTo(1);
		assertThat(health.attention()).isZero();
	}

	@Test
	void namesAPendingClaimWithItsStorageClass() {
		// A Pending claim is nearly always a question about its class, so putting the
		// name
		// in the row saves opening the object to find it.
		claim("cache", "Pending", "fast-ssd");
		assertThat(summary().needsAttention()).singleElement().satisfies((item) -> {
			assertThat(item.name()).isEqualTo("cache");
			assertThat(item.reason()).isEqualTo("Pending · fast-ssd");
		});
	}

	@Test
	void fallsBackToThePhaseWhenThereIsNoStorageClass() {
		claim("legacy", "Pending", null);
		assertThat(summary().needsAttention()).singleElement()
			.satisfies((item) -> assertThat(item.reason()).isEqualTo("Pending"));
	}

	@Test
	void reportsAClaimWithNoStatusRatherThanSkippingIt() {
		// A claim the API has not filled in yet is unknown, not fine — silence here is
		// the
		// dangerous direction on a health screen.
		this.client.persistentVolumeClaims()
			.inNamespace("app")
			.resource(new PersistentVolumeClaimBuilder().withNewMetadata()
				.withName("fresh")
				.withNamespace("app")
				.endMetadata()
				.build())
			.create();
		assertThat(summary().needsAttention()).singleElement()
			.satisfies((item) -> assertThat(item.reason()).isEqualTo("unknown"));
	}

}

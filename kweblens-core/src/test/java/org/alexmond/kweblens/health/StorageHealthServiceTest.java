package org.alexmond.kweblens.health;

import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.metric.PrometheusMetricService;
import org.alexmond.kweblens.metric.VolumeUsage;

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
		// A real PrometheusMetricService against the mock API server: discovery finds no
		// Prometheus-like Service, so volumeUsage() is empty and only the binding checks
		// run.
		// That is exactly the no-metrics-backend deployment, and it must still work.
		return new StorageHealthService(registry, new PrometheusMetricService(registry));
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

	private static final long GIB = 1024L * 1024 * 1024;

	/** A metrics backend that returns exactly the reading the test wants to exercise. */
	private StorageHealthService serviceWith(VolumeUsage reading) {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register("mock", "mock", this.client);
		PrometheusMetricService stub = new PrometheusMetricService(registry) {
			@Override
			public java.util.Map<String, VolumeUsage> volumeUsage(String clusterId) {
				return java.util.Map.of("app/" + reading.claim(), reading);
			}
		};
		return new StorageHealthService(registry, stub);
	}

	private void boundClaim(String name, String requested) {
		this.client.persistentVolumeClaims()
			.inNamespace("app")
			.resource(new PersistentVolumeClaimBuilder().withNewMetadata()
				.withName(name)
				.withNamespace("app")
				.endMetadata()
				.withNewSpec()
				.withNewResources()
				.addToRequests("storage", new io.fabric8.kubernetes.api.model.Quantity(requested))
				.endResources()
				.endSpec()
				.withNewStatus()
				.withPhase("Bound")
				.endStatus()
				.build())
			.create();
	}

	@Test
	void flagsABoundClaimThatIsNearlyFull() {
		// The positive path: a real per-volume quota, 95% consumed.
		boundClaim("data", "10Gi");
		KindHealth health = serviceWith(new VolumeUsage("app", "data", (long) (9.5 * GIB), 10 * GIB))
			.summarise("mock", "app")
			.get(0);
		assertThat(health.needsAttention()).singleElement()
			.satisfies((item) -> assertThat(item.reason()).isEqualTo("95% full"));
	}

	@Test
	void doesNotFlagAClaimOnTheStrengthOfTheBackingDisk() {
		// The negative path, and the reason this check exists. On a local-path or NFS
		// volume
		// kubelet reports the whole filesystem: a 1Gi claim comes back as a 3.2 TB volume
		// that is 95% full. Flagging it would blame the claim for someone else's disk.
		boundClaim("small", "1Gi");
		KindHealth health = serviceWith(new VolumeUsage("app", "small", (long) (3080.0 * GIB), 3245L * GIB))
			.summarise("mock", "app")
			.get(0);
		assertThat(health.attention()).isZero();
		assertThat(health.ok()).isEqualTo(1);
	}

	@Test
	void leavesAComfortableVolumeAlone() {
		boundClaim("roomy", "10Gi");
		KindHealth health = serviceWith(new VolumeUsage("app", "roomy", 4 * GIB, 10 * GIB)).summarise("mock", "app")
			.get(0);
		assertThat(health.attention()).isZero();
	}

}

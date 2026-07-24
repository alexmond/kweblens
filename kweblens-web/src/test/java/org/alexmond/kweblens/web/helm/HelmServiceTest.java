package org.alexmond.kweblens.web.helm;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.cluster.UnknownClusterException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the jhelm integration end-to-end against the fabric8 mock server: an official
 * io.kubernetes {@link io.kubernetes.client.openapi.ApiClient} built from the cluster's
 * fabric8 config queries Helm release Secrets. With none present, listing returns empty
 * (not an error), proving the per-cluster wiring works without a live cluster.
 */
@EnableKubernetesMockClient(crud = true)
class HelmServiceTest {

	KubernetesClient client;

	private HelmService serviceFor(String clusterId) {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register(clusterId, clusterId, client);
		return new HelmService(registry);
	}

	@Test
	void listReleasesIsEmptyWithoutHelmSecrets() {
		assertThat(serviceFor("c1").listReleases("c1", "default")).isEmpty();
	}

	@Test
	void statusIsEmptyForUnknownRelease() {
		assertThat(serviceFor("c1").status("c1", "default", "nope")).isEmpty();
	}

	@Test
	void requiresAKnownCluster() {
		assertThatThrownBy(() -> serviceFor("c1").listReleases("ghost", "default"))
			.isInstanceOf(UnknownClusterException.class);
	}

}

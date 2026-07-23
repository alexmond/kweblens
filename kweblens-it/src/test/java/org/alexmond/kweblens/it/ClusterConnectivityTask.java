package org.alexmond.kweblens.it;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * On-demand operational task: prove kweblens can reach the cluster the ambient kubeconfig
 * points at. Tagged {@code it} so it is excluded from the default {@code verify} (no live
 * cluster in CI); run explicitly against a real target:
 *
 * <pre>
 *   mvn -pl kweblens-it test -Dkweblens.it.excluded-groups= -Dtest=ClusterConnectivityTask
 * </pre>
 */
@Tag("it")
class ClusterConnectivityTask {

	@Test
	void reachesTheCurrentCluster() {
		try (KubernetesClient client = new KubernetesClientBuilder().build()) {
			assertThat(client.getKubernetesVersion()).isNotNull();
			assertThat(client.namespaces().list().getItems()).isNotNull();
		}
	}

}

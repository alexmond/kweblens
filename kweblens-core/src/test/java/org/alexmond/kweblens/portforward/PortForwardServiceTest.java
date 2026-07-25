package org.alexmond.kweblens.portforward;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.cluster.UnknownClusterException;
import org.alexmond.kweblens.config.KweblensProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnableKubernetesMockClient(crud = true)
class PortForwardServiceTest {

	static KubernetesClient client;

	private PortForwardService service(ClusterRegistry registry) {
		return new PortForwardService(registry, new KweblensProperties());
	}

	@Test
	void startRequiresAKnownCluster() {
		PortForwardService service = service(new ClusterRegistry());
		assertThatThrownBy(() -> service.start("ghost", "Pod", "web", "nginx", 80, null))
			.isInstanceOf(UnknownClusterException.class);
	}

	@Test
	void rejectsUnsupportedKind() {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register("mock", "mock", client);
		PortForwardService service = service(registry);
		assertThatThrownBy(() -> service.start("mock", "Deployment", "web", "app", 8080, null))
			.isInstanceOf(PortForwardException.class)
			.hasMessageContaining("Deployment");
	}

	@Test
	void listsNothingForAnUnknownCluster() {
		PortForwardService service = service(new ClusterRegistry());
		assertThat(service.list("mock")).isEmpty();
	}

	@Test
	void stopIsANoOpForUnknownId() {
		PortForwardService service = service(new ClusterRegistry());
		service.stop("nope");
		assertThat(service.list("mock")).isEmpty();
	}

	@Test
	void withStatusReplacesOnlyTheStatus() {
		PortForwardInfo info = new PortForwardInfo("id", "mock", "web", "Pod", "nginx", 80, 12345, "127.0.0.1", "TCP",
				"Active");
		PortForwardInfo closed = info.withStatus("Closed");
		assertThat(closed.status()).isEqualTo("Closed");
		assertThat(closed.localPort()).isEqualTo(12345);
		assertThat(closed.name()).isEqualTo("nginx");
	}

}

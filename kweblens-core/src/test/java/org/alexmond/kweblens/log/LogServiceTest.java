package org.alexmond.kweblens.log;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.cluster.UnknownClusterException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnableKubernetesMockClient(crud = true)
class LogServiceTest {

	KubernetesClient client;

	KubernetesMockServer server;

	private LogService serviceFor(String clusterId) {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register(clusterId, clusterId, client);
		return new LogService(registry);
	}

	@Test
	void tailReturnsThePodLog() {
		server.expect()
			.get()
			.withPath("/api/v1/namespaces/web/pods/nginx/log?pretty=false&tailLines=100")
			.andReturn(200, "hello\nworld")
			.always();

		assertThat(serviceFor("c1").tail("c1", "web", "nginx", null, 100)).isEqualTo("hello\nworld");
	}

	@Test
	void tailThrowsForUnknownCluster() {
		assertThatThrownBy(() -> serviceFor("c1").tail("missing", "web", "nginx", null, 100))
			.isInstanceOf(UnknownClusterException.class);
	}

}

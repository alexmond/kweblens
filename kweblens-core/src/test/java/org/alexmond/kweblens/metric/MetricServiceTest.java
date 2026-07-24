package org.alexmond.kweblens.metric;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class MetricServiceTest {

	// Per-test client/server: each test stubs its own metrics endpoints.
	KubernetesClient client;

	KubernetesMockServer server;

	private MetricService serviceFor(String clusterId) {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register(clusterId, clusterId, client);
		return new MetricService(registry);
	}

	@Test
	void nodeUsageIsFormattedInMillicoresAndMebibytes() {
		server.expect()
			.get()
			.withPath("/apis/metrics.k8s.io/v1beta1/nodes")
			.andReturn(200,
					"{\"kind\":\"NodeMetricsList\",\"apiVersion\":\"metrics.k8s.io/v1beta1\","
							+ "\"items\":[{\"metadata\":{\"name\":\"node1\"},"
							+ "\"usage\":{\"cpu\":\"120m\",\"memory\":\"512Mi\"}}]}")
			.always();

		assertThat(serviceFor("c1").nodeUsage("c1")).singleElement().satisfies((u) -> {
			assertThat(u.name()).isEqualTo("node1");
			assertThat(u.cpu()).isEqualTo("120m");
			assertThat(u.memory()).isEqualTo("512Mi");
		});
	}

	@Test
	void podUsageSumsContainers() {
		server.expect()
			.get()
			.withPath("/apis/metrics.k8s.io/v1beta1/namespaces/web/pods")
			.andReturn(200,
					"{\"kind\":\"PodMetricsList\",\"apiVersion\":\"metrics.k8s.io/v1beta1\","
							+ "\"items\":[{\"metadata\":{\"name\":\"nginx\",\"namespace\":\"web\"},\"containers\":["
							+ "{\"name\":\"c1\",\"usage\":{\"cpu\":\"50m\",\"memory\":\"128Mi\"}},"
							+ "{\"name\":\"c2\",\"usage\":{\"cpu\":\"30m\",\"memory\":\"64Mi\"}}]}]}")
			.always();

		assertThat(serviceFor("c1").podUsage("c1", "web")).singleElement().satisfies((u) -> {
			assertThat(u.name()).isEqualTo("nginx");
			assertThat(u.cpu()).isEqualTo("80m");
			assertThat(u.memory()).isEqualTo("192Mi");
		});
	}

	@Test
	void degradesToEmptyWhenMetricsServerAbsent() {
		// No stub for the node metrics endpoint -> the mock returns 404 -> empty, not an
		// error.
		assertThat(serviceFor("c1").nodeUsage("c1")).isEmpty();
	}

}

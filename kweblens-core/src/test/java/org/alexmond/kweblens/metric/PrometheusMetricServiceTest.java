package org.alexmond.kweblens.metric;

import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class PrometheusMetricServiceTest {

	KubernetesClient client;

	private PrometheusMetricService serviceFor(String clusterId) {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register(clusterId, clusterId, client);
		return new PrometheusMetricService(registry);
	}

	private void createService(String namespace, String name, int port) {
		client.services()
			.resource(new ServiceBuilder().withNewMetadata()
				.withName(name)
				.withNamespace(namespace)
				.endMetadata()
				.withNewSpec()
				.addNewPort()
				.withPort(port)
				.endPort()
				.endSpec()
				.build())
			.create();
	}

	@Test
	void discoversAPrometheusLikeServiceAndIgnoresExporters() {
		createService("monitoring", "vmstack-prometheus-node-exporter", 9100);
		createService("monitoring", "vmsingle-vmstack", 8428);
		PrometheusMetricService service = serviceFor("c1");

		assertThat(service.endpoint("c1")).contains("monitoring/vmsingle-vmstack:8428");
	}

	@Test
	void queryRangeIsUnavailableWithoutABackend() {
		PrometheusMetricService service = serviceFor("c1");
		assertThat(service.queryRange("c1", "up", "cores", 30).available()).isFalse();
	}

	@Test
	void parseExtractsSamplesFromAPrometheusResponse() {
		String json = """
				{"status":"success","data":{"resultType":"matrix","result":[
				  {"metric":{},"values":[[1700000000,"1.5"],[1700000060,"2.25"]]}
				]}}
				""";
		MetricSeries series = serviceFor("c1").parse(json, "cores");

		assertThat(series.available()).isTrue();
		assertThat(series.unit()).isEqualTo("cores");
		assertThat(series.points()).hasSize(2);
		assertThat(series.points().get(1).v()).isEqualTo(2.25);
		assertThat(series.points().get(0).t()).isEqualTo(1_700_000_000L);
	}

}

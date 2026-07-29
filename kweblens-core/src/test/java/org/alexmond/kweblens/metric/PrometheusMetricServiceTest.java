package org.alexmond.kweblens.metric;

import io.fabric8.kubernetes.api.model.NodeBuilder;
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

	@Test
	void parseInstantMapsSeriesByLabel() {
		String json = """
				{"status":"success","data":{"resultType":"vector","result":[
				  {"metric":{"instance":"192.0.2.1:9100"},"value":[1700000000,"42.5"]},
				  {"metric":{"instance":"192.0.2.2:9100"},"value":[1700000000,"7"]}
				]}}
				""";
		var byInstance = serviceFor("c1").parseInstant(json, "instance");

		assertThat(byInstance).containsEntry("192.0.2.1:9100", 42.5).containsEntry("192.0.2.2:9100", 7.0);
		assertThat(serviceFor("c1").parseInstant("", "instance")).isEmpty();
		assertThat(serviceFor("c1").parseInstant("not json", "instance")).isEmpty();
	}

	@Test
	void nodeInternalIpsMapsIpToNodeName() {
		client.nodes()
			.resource(new NodeBuilder().withNewMetadata()
				.withName("worker-1")
				.endMetadata()
				.withNewStatus()
				.addNewAddress()
				.withType("InternalIP")
				.withAddress("192.0.2.50")
				.endAddress()
				.endStatus()
				.build())
			.create();

		assertThat(serviceFor("c1").nodeInternalIps("c1")).containsEntry("192.0.2.50", "worker-1");
	}

	@Test
	void nodeDiskUsageIsEmptyWithoutABackend() {
		assertThat(serviceFor("c1").nodeDiskUsage("c1")).isEmpty();
	}

	@Test
	void nodeInstanceSelectorDoubleEscapesTheIpDots() {
		client.nodes()
			.resource(new NodeBuilder().withNewMetadata()
				.withName("worker-9")
				.endMetadata()
				.withNewStatus()
				.addNewAddress()
				.withType("InternalIP")
				.withAddress("192.0.2.10")
				.endAddress()
				.endStatus()
				.build())
			.create();

		// TWO backslashes per dot: the matcher value is a PromQL string literal, so `\\.`
		// in
		// the query text unescapes to `\.` for the regex engine. A single backslash is
		// not a
		// valid string escape and strict backends (VictoriaMetrics) reject the query with
		// 422.
		assertThat(serviceFor("c1").nodeInstanceSelector("c1", "worker-9"))
			.contains("instance=~\"192\\\\.0\\\\.2\\\\.10:.*\"");
	}

	@Test
	void nodeInstanceSelectorIsEmptyForAnUnknownNode() {
		assertThat(serviceFor("c1").nodeInstanceSelector("c1", "absent")).isEmpty();
	}

}

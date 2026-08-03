package org.alexmond.kweblens.web.sim;

import java.util.List;

import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The simulator seeder produces the configured number of objects per kind across the
 * configured namespaces — the same crud mock the simulator uses at runtime.
 */
@EnableKubernetesMockClient(crud = true)
class SimulatorSeederTest {

	KubernetesClient client;

	@Test
	void seedsConfigurableCountsPerKindAndNamespace() {
		SimulatorProperties props = new SimulatorProperties();
		props.setSize(5);
		props.setNamespaces(2);

		SimulatorSeeder.seed(client, props);

		assertThat(client.namespaces().list().getItems()).hasSize(2);
		assertThat(client.configMaps().inAnyNamespace().list().getItems()).hasSize(5);
		assertThat(client.secrets().inAnyNamespace().list().getItems()).hasSize(5);
		assertThat(client.pods().inAnyNamespace().list().getItems()).hasSize(5);
		assertThat(client.apps().replicaSets().inAnyNamespace().list().getItems()).hasSize(5);
		assertThat(client.apps().deployments().inAnyNamespace().list().getItems()).hasSize(5);
		assertThat(client.network().v1().ingresses().inAnyNamespace().list().getItems()).hasSize(5);
		// Annotations and a TLS host are what make the drawer's two chip styles
		// renderable
		// without a live cluster; a simulator without them cannot be used to check their
		// colours.
		assertThat(client.configMaps().inAnyNamespace().list().getItems().getFirst().getMetadata().getAnnotations())
			.containsKey("meta.helm.sh/release-name");
		assertThat(client.network().v1().ingresses().inAnyNamespace().list().getItems().getFirst().getSpec().getTls())
			.isNotEmpty();
		// Nodes, and pods scheduled onto them mounting the same-index ConfigMap and
		// Secret. Without these three the drawer's relation sections are unrenderable in
		// the simulator: "Mounted By" reads "None." for every ConfigMap and Secret, and
		// its Node column — the one #278 was about — has nothing in it.
		assertThat(client.nodes().list().getItems()).hasSize(3);
		assertThat(client.nodes().list().getItems().getFirst().getMetadata().getName())
			.isEqualTo("node-0.sim.example.test");
		assertThat(client.pods().inAnyNamespace().list().getItems()).allSatisfy(
				(pod) -> assertThat(pod.getSpec().getNodeName()).startsWith("node-").endsWith(".sim.example.test"));
		List<Volume> volumes = client.pods().inNamespace("sim-ns-0").withName("sim-pod-0").get().getSpec().getVolumes();
		assertThat(volumes).map((v) -> (v.getSecret() != null) ? v.getSecret().getSecretName() : null)
			.contains("sim-secret-0");
		assertThat(volumes).map((v) -> (v.getConfigMap() != null) ? v.getConfigMap().getName() : null)
			.contains("sim-config-0");
		// Objects spread across both namespaces (index % 2).
		assertThat(client.configMaps().inNamespace("sim-ns-0").list().getItems()).hasSize(3);
		assertThat(client.configMaps().inNamespace("sim-ns-1").list().getItems()).hasSize(2);
	}

}

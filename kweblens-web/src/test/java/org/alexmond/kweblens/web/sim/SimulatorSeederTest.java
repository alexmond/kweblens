package org.alexmond.kweblens.web.sim;

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
		// Objects spread across both namespaces (index % 2).
		assertThat(client.configMaps().inNamespace("sim-ns-0").list().getItems()).hasSize(3);
		assertThat(client.configMaps().inNamespace("sim-ns-1").list().getItems()).hasSize(2);
	}

}

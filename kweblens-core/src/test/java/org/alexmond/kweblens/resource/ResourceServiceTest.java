package org.alexmond.kweblens.resource;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class ResourceServiceTest {

	static KubernetesClient client;

	private ResourceService serviceFor(String clusterId) {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register(clusterId, clusterId, client);
		return new ResourceService(registry);
	}

	@Test
	void listsNamespacesAsSummaries() {
		client.namespaces()
			.resource(new NamespaceBuilder().withNewMetadata().withName("kube-system").endMetadata().build())
			.create();

		ResourceService service = serviceFor("mock");

		assertThat(service.listNamespaces("mock")).anySatisfy((row) -> {
			assertThat(row.kind()).isEqualTo("Namespace");
			assertThat(row.name()).isEqualTo("kube-system");
		});
	}

	@Test
	void listsPodsInANamespace() {
		client.pods()
			.resource(new PodBuilder().withNewMetadata()
				.withName("nginx")
				.withNamespace("web")
				.endMetadata()
				.withNewStatus()
				.withPhase("Running")
				.endStatus()
				.build())
			.create();

		ResourceService service = serviceFor("mock");

		assertThat(service.listPods("mock", "web")).singleElement().satisfies((row) -> {
			assertThat(row.kind()).isEqualTo("Pod");
			assertThat(row.namespace()).isEqualTo("web");
			assertThat(row.name()).isEqualTo("nginx");
		});
	}

}

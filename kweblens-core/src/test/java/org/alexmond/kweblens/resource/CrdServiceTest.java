package org.alexmond.kweblens.resource;

import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class CrdServiceTest {

	KubernetesClient client;

	@Test
	void discoversCrdsAsDescriptors() {
		CustomResourceDefinition crd = new CustomResourceDefinitionBuilder().withNewMetadata()
			.withName("ingressroutes.traefik.io")
			.endMetadata()
			.withNewSpec()
			.withGroup("traefik.io")
			.withScope("Namespaced")
			.withNewNames()
			.withPlural("ingressroutes")
			.withKind("IngressRoute")
			.endNames()
			.addNewVersion()
			.withName("v1alpha1")
			.withServed(true)
			.withStorage(true)
			.endVersion()
			.endSpec()
			.build();
		client.apiextensions().v1().customResourceDefinitions().resource(crd).create();

		ClusterRegistry registry = new ClusterRegistry();
		registry.register("c1", "c1", client);

		assertThat(new CrdService(registry).customResourceDescriptors("c1")).anySatisfy((d) -> {
			assertThat(d.id()).isEqualTo("traefik.io.ingressroutes");
			assertThat(d.kind()).isEqualTo("IngressRoute");
			assertThat(d.group()).isEqualTo("traefik.io");
			assertThat(d.version()).isEqualTo("v1alpha1");
			assertThat(d.namespaced()).isTrue();
		});
	}

}

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

	@Test
	void printerColumnsFromTheServedVersion() {
		CustomResourceDefinition crd = new CustomResourceDefinitionBuilder().withNewMetadata()
			.withName("certificates.cert-manager.io")
			.endMetadata()
			.withNewSpec()
			.withGroup("cert-manager.io")
			.withScope("Namespaced")
			.withNewNames()
			.withPlural("certificates")
			.withKind("Certificate")
			.endNames()
			.addNewVersion()
			.withName("v1")
			.withServed(true)
			.withStorage(true)
			.addNewAdditionalPrinterColumn()
			.withName("Ready")
			.withJsonPath(".status.conditions[?(@.type==\"Ready\")].status")
			.withType("string")
			.endAdditionalPrinterColumn()
			.addNewAdditionalPrinterColumn()
			.withName("Wide")
			.withJsonPath(".spec.secretName")
			.withType("string")
			.withPriority(1)
			.endAdditionalPrinterColumn()
			.endVersion()
			.endSpec()
			.build();
		client.apiextensions().v1().customResourceDefinitions().resource(crd).create();

		ClusterRegistry registry = new ClusterRegistry();
		registry.register("c1", "c1", client);

		// The served version's columns are returned; wide (priority > 0) columns are
		// dropped.
		assertThat(new CrdService(registry).printerColumns("c1", "cert-manager.io.certificates")).singleElement()
			.satisfies((c) -> {
				assertThat(c.name()).isEqualTo("Ready");
				assertThat(c.type()).isEqualTo("string");
			});
		// Built-in kinds (no matching CRD) get no columns.
		assertThat(new CrdService(registry).printerColumns("c1", "pods")).isEmpty();
	}

}

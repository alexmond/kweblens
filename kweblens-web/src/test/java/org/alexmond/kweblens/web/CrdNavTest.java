package org.alexmond.kweblens.web;

import java.util.List;

import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.web.nav.ClusterNavService;
import org.alexmond.kweblens.web.nav.NavCategory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dynamic Custom Resources nav: a cluster's CRDs appear as a sub-group per API group
 * nested under the Custom Resources category, and their route ids resolve to descriptors.
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
@EnableKubernetesMockClient(crud = true)
class CrdNavTest {

	KubernetesClient client;

	@Autowired
	private ClusterRegistry registry;

	@Autowired
	private ClusterNavService clusterNav;

	@Test
	void crdsAppearAsAGroupCategoryAndResolve() {
		registry.register("crd-cluster", "Test cluster", client);
		client.apiextensions()
			.v1()
			.customResourceDefinitions()
			.resource(new CustomResourceDefinitionBuilder().withNewMetadata()
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
				.build())
			.create();

		List<NavCategory> categories = clusterNav.categories("crd-cluster");
		// The API group is nested under Custom Resources, not a top-level category.
		assertThat(categories).extracting(NavCategory::label).contains("Custom Resources").doesNotContain("traefik.io");
		NavCategory customResources = categories.stream()
			.filter((c) -> c.label().equals("Custom Resources"))
			.findFirst()
			.orElseThrow();
		assertThat(customResources.subgroups()).extracting(NavCategory::label).contains("traefik.io");
		assertThat(clusterNav.find("crd-cluster", "traefik.io.ingressroutes")).get()
			.extracting("kind")
			.isEqualTo("IngressRoute");
	}

}

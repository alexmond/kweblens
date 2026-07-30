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
 * Gateway API promotion: its CRD-delivered kinds get their own top-level category beside
 * Network instead of being buried under an API-group name in Custom Resources — but only
 * when the CRDs are actually installed.
 *
 * <p>
 * Non-static mock client: promotion depends on which CRDs exist, so a client shared
 * across the class would let one test's CRDs decide another test's nav.
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
@EnableKubernetesMockClient(crud = true)
class GatewayNavTest {

	KubernetesClient client;

	@Autowired
	private ClusterRegistry registry;

	@Autowired
	private ClusterNavService clusterNav;

	private void crd(String plural, String kind, String group, String scope) {
		this.client.apiextensions()
			.v1()
			.customResourceDefinitions()
			.resource(new CustomResourceDefinitionBuilder().withNewMetadata()
				.withName(plural + "." + group)
				.endMetadata()
				.withNewSpec()
				.withGroup(group)
				.withScope(scope)
				.withNewNames()
				.withPlural(plural)
				.withKind(kind)
				.endNames()
				.addNewVersion()
				.withName("v1")
				.withServed(true)
				.withStorage(true)
				.endVersion()
				.endSpec()
				.build())
			.create();
	}

	private List<NavCategory> navFor(String clusterId) {
		this.registry.register(clusterId, "Test cluster", this.client);
		return this.clusterNav.categories(clusterId);
	}

	@Test
	void promotesGatewayApiToItsOwnCategoryRightAfterNetwork() {
		crd("gatewayclasses", "GatewayClass", "gateway.networking.k8s.io", "Cluster");
		crd("gateways", "Gateway", "gateway.networking.k8s.io", "Namespaced");
		crd("httproutes", "HTTPRoute", "gateway.networking.k8s.io", "Namespaced");

		List<String> labels = navFor("gw-1").stream().map(NavCategory::label).toList();

		// Gateway API is the successor to Ingress, so it belongs beside Network rather
		// than at
		// the end of the menu.
		assertThat(labels).contains("Gateway");
		assertThat(labels.indexOf("Gateway")).isEqualTo(labels.indexOf("Network") + 1);
	}

	@Test
	void ordersGatewayKindsByTrafficFlowNotAlphabetically() {
		// Alphabetical order would lead with BackendTLSPolicy, which explains nothing.
		// Reading
		// top-to-bottom should follow a request: class → gateway → routes.
		crd("httproutes", "HTTPRoute", "gateway.networking.k8s.io", "Namespaced");
		crd("backendtlspolicies", "BackendTLSPolicy", "gateway.networking.k8s.io", "Namespaced");
		crd("gateways", "Gateway", "gateway.networking.k8s.io", "Namespaced");
		crd("gatewayclasses", "GatewayClass", "gateway.networking.k8s.io", "Cluster");

		NavCategory gateway = navFor("gw-2").stream()
			.filter((c) -> c.label().equals("Gateway"))
			.findFirst()
			.orElseThrow();

		assertThat(gateway.items()).extracting("kind")
			.containsExactly("GatewayClass", "Gateway", "HTTPRoute", "BackendTLSPolicy");
	}

	@Test
	void doesNotDuplicateGatewayKindsUnderCustomResources() {
		crd("gateways", "Gateway", "gateway.networking.k8s.io", "Namespaced");
		crd("ingressroutes", "IngressRoute", "traefik.io", "Namespaced");

		List<NavCategory> categories = navFor("gw-3");
		NavCategory customResources = categories.stream()
			.filter((c) -> c.label().equals("Custom Resources"))
			.findFirst()
			.orElseThrow();

		// Promoted, so removed from Custom Resources — otherwise the same kinds appear
		// twice.
		assertThat(customResources.subgroups()).extracting(NavCategory::label)
			.contains("traefik.io")
			.doesNotContain("gateway.networking.k8s.io");
	}

	@Test
	void addsNoGatewayCategoryWhenTheCrdsAreNotInstalled() {
		// The whole reason promotion is conditional: a static entry for a CRD-delivered
		// kind
		// would give every cluster without Gateway API a dead category that errors when
		// clicked.
		crd("ingressroutes", "IngressRoute", "traefik.io", "Namespaced");

		assertThat(navFor("gw-4")).extracting(NavCategory::label).doesNotContain("Gateway");
	}

	@Test
	void stillResolvesPromotedKindsByTheirRouteId() {
		// Promotion is presentation only — the ids are unchanged, so existing links keep
		// working.
		crd("httproutes", "HTTPRoute", "gateway.networking.k8s.io", "Namespaced");
		navFor("gw-5");

		assertThat(this.clusterNav.find("gw-5", "gateway.networking.k8s.io.httproutes")).get()
			.extracting("kind")
			.isEqualTo("HTTPRoute");
	}

}

package org.alexmond.kweblens.web.mcp;

import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code list_resource_kinds} answers a machine, and what it answers with is the
 * {@code resourceId} every other tool takes plus the Kubernetes {@code kind} — never the
 * rail's label.
 *
 * <p>
 * The distinction became worth pinning when CRD labels stopped being the raw kind (#433):
 * the nav is where a label is written for a reader, and this tool shares the nav's
 * categories, so a label that leaked into it would put a name no {@code kubectl} knows in
 * front of an assistant. The category heading IS a label, deliberately — it is prose, and
 * nothing takes it as an argument.
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
@EnableKubernetesMockClient(crud = true)
class ClusterToolsKindsTest {

	private static final String CLUSTER = "mcp-kinds";

	KubernetesClient client;

	@Autowired
	private ClusterRegistry registry;

	@Autowired
	private ClusterTools tools;

	@AfterEach
	void unregister() {
		this.registry.unregister(CLUSTER);
	}

	private void crd(String plural, String kind, String group) {
		this.client.apiextensions()
			.v1()
			.customResourceDefinitions()
			.resource(new CustomResourceDefinitionBuilder().withNewMetadata()
				.withName(plural + "." + group)
				.endMetadata()
				.withNewSpec()
				.withGroup(group)
				.withScope("Namespaced")
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

	@SuppressWarnings("unchecked")
	private List<Map<String, String>> kindsOf(List<Map<String, Object>> categories, String category) {
		return categories.stream()
			.filter((c) -> category.equals(c.get("category")))
			.map((c) -> (List<Map<String, String>>) c.get("kinds"))
			.findFirst()
			.orElseThrow();
	}

	@Test
	void namesACrdKindByItsKubernetesKindAndRouteId() {
		crd("httproutes", "HTTPRoute", "gateway.networking.k8s.io");
		this.registry.register(CLUSTER, "Test cluster", this.client);

		List<Map<String, Object>> categories = this.tools.listResourceKinds(CLUSTER);

		assertThat(kindsOf(categories, "Gateway")).singleElement()
			.isEqualTo(Map.of("resourceId", "gateway.networking.k8s.io.httproutes", "kind", "HTTPRoute", "namespaced",
					"true"));
	}

	@Test
	void carriesNoDisplayLabelForAnyKindOnAnyCluster() {
		crd("httproutes", "HTTPRoute", "gateway.networking.k8s.io");
		crd("ingressroutes", "IngressRoute", "traefik.io");
		this.registry.register(CLUSTER, "Test cluster", this.client);

		List<Map<String, Object>> categories = this.tools.listResourceKinds(CLUSTER);

		assertThat(categories)
			.allSatisfy((category) -> assertThat(kindsOf(categories, (String) category.get("category")))
				.allSatisfy((kind) -> assertThat(kind).containsOnlyKeys("resourceId", "kind", "namespaced")));
	}

}

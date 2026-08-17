package org.alexmond.kweblens.web.mcp;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.resource.CrdService;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.web.nav.ClusterNavService;
import org.alexmond.kweblens.web.nav.NavCatalog;
import org.alexmond.kweblens.web.nav.NavCategory;

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

	/**
	 * One cluster id per test, because {@code CrdService.customResourceDescriptors} is
	 * {@code @Cacheable} on the cluster id with no eviction — two tests seeding different
	 * CRDs into the same id inside the 10 s TTL would read each other's discovery.
	 */
	private static final String INVARIANT_CLUSTER = "mcp-kinds-invariant";

	private static final String FILING_CLUSTER = "mcp-kinds-filing";

	KubernetesClient client;

	@Autowired
	private ClusterRegistry registry;

	@Autowired
	private ClusterTools tools;

	@Autowired
	private ClusterNavService clusterNav;

	@Autowired
	private NavCatalog navCatalog;

	@Autowired
	private CrdService crds;

	@AfterEach
	void unregister() {
		this.registry.unregister(CLUSTER);
		this.registry.unregister(INVARIANT_CLUSTER);
		this.registry.unregister(FILING_CLUSTER);
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

	/** Every {@code resourceId} the tool listed, in order, duplicates kept. */
	@SuppressWarnings("unchecked")
	private List<String> listedIds(List<Map<String, Object>> categories) {
		return categories.stream()
			.flatMap((c) -> ((List<Map<String, String>>) c.get("kinds")).stream())
			.map((kind) -> kind.get("resourceId"))
			.toList();
	}

	/** The categories the tool filed one id under — normally exactly one. */
	private List<String> filedUnder(List<Map<String, Object>> categories, String resourceId) {
		return categories.stream()
			.filter((c) -> listedIds(List.of(c)).contains(resourceId))
			.map((c) -> (String) c.get("category"))
			.toList();
	}

	/**
	 * The invariant, and it is about the nav rather than a kind count: an id
	 * {@link ClusterNavService#find} resolves is an id every other tool accepts, so an id
	 * the nav resolves and this tool does not list is a route reachable only by guessing
	 * — which is the one thing this tool exists to remove. Asserting a number instead
	 * would pass the day someone adds a kind and forgets the tool.
	 *
	 * <p>
	 * Before #436 this failed on {@code traefik.io.ingressroutes}: the tool mapped
	 * {@code category.items()} only, and a cluster's CRDs hang off
	 * {@link NavCategory#subgroups()}.
	 */
	@Test
	void listsEveryKindTheNavCanResolve() {
		crd("httproutes", "HTTPRoute", "gateway.networking.k8s.io");
		crd("ingressroutes", "IngressRoute", "traefik.io");
		this.registry.register(INVARIANT_CLUSTER, "Test cluster", this.client);

		List<String> listed = listedIds(this.tools.listResourceKinds(INVARIANT_CLUSTER));

		List<String> resolvable = Stream
			.concat(this.navCatalog.categories().stream().flatMap((c) -> c.items().stream()),
					this.crds.customResourceDescriptors(INVARIANT_CLUSTER).stream())
			.map(ResourceDescriptor::id)
			.toList();
		assertThat(listed).containsAll(resolvable);
		assertThat(listed)
			.allSatisfy((id) -> assertThat(this.clusterNav.find(INVARIANT_CLUSTER, id)).as(id).isPresent());
	}

	/**
	 * A kind is filed under one category, and a promoted one under the category it was
	 * promoted into — never there and again under its API group. The nav already removes
	 * a promoted group (Gateway, whole) or kind (the VPA, part of
	 * {@code autoscaling.k8s.io}) from the sub-groups it builds, so walking the tree
	 * inherits that; a flatten that unioned the two levels would not.
	 */
	@Test
	void filesEachKindUnderExactlyOneCategory() {
		crd("httproutes", "HTTPRoute", "gateway.networking.k8s.io");
		crd("verticalpodautoscalers", "VerticalPodAutoscaler", "autoscaling.k8s.io");
		crd("verticalpodautoscalercheckpoints", "VerticalPodAutoscalerCheckpoint", "autoscaling.k8s.io");
		crd("ingressroutes", "IngressRoute", "traefik.io");
		this.registry.register(FILING_CLUSTER, "Test cluster", this.client);

		List<Map<String, Object>> categories = this.tools.listResourceKinds(FILING_CLUSTER);

		assertThat(listedIds(categories)).doesNotHaveDuplicates();
		assertThat(filedUnder(categories, "gateway.networking.k8s.io.httproutes")).containsExactly("Gateway");
		assertThat(filedUnder(categories, "autoscaling.k8s.io.verticalpodautoscalers")).containsExactly("Autoscaling");
		// Demoted machinery, and the category names the API group that defines it.
		assertThat(filedUnder(categories, "autoscaling.k8s.io.verticalpodautoscalercheckpoints"))
			.containsExactly("Custom Resources / autoscaling.k8s.io");
		assertThat(filedUnder(categories, "traefik.io.ingressroutes")).containsExactly("Custom Resources / traefik.io");
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

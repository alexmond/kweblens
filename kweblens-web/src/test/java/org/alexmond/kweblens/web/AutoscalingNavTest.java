package org.alexmond.kweblens.web;

import java.util.ArrayList;
import java.util.List;

import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.web.nav.ClusterNavService;
import org.alexmond.kweblens.web.nav.NavCategory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The Autoscaling category (#428): the built-in HorizontalPodAutoscaler and the
 * CRD-delivered VerticalPodAutoscaler under one heading, next to the workloads they act
 * on rather than in Config and Custom Resources respectively.
 *
 * <p>
 * <b>The case without the CRD is the control.</b> The category itself is offered on every
 * cluster — HPA is a built-in kind that every cluster serves, and an empty list is a
 * correct answer, so hiding it would make HPA the one built-in kind whose menu presence
 * depended on its object count. What is conditional is the CRD-delivered half, and
 * {@link #holdsTheHpaAloneWhenTheVpaCrdIsNotInstalled} is what makes that testable:
 * declare a VPA kind unconditionally (a second entry in {@code NavCatalog}, say) and it
 * is the test that goes red.
 *
 * <p>
 * Non-static mock client: what the nav holds depends on which CRDs exist, so a client
 * shared across the class would let one test's cluster decide another test's nav.
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
@EnableKubernetesMockClient(crud = true)
class AutoscalingNavTest {

	private static final String VPA_GROUP = "autoscaling.k8s.io";

	private final List<String> registered = new ArrayList<>();

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

	private void vpaCrds() {
		crd("verticalpodautoscalers", "VerticalPodAutoscaler", VPA_GROUP, "Namespaced");
		crd("verticalpodautoscalercheckpoints", "VerticalPodAutoscalerCheckpoint", VPA_GROUP, "Namespaced");
	}

	private void horizontalPodAutoscaler() {
		this.client.autoscaling()
			.v2()
			.horizontalPodAutoscalers()
			.inNamespace("default")
			.resource(new HorizontalPodAutoscalerBuilder().withNewMetadata()
				.withName("web")
				.withNamespace("default")
				.endMetadata()
				.build())
			.create();
	}

	private List<NavCategory> navFor(String clusterId) {
		this.registry.register(clusterId, "Test cluster", this.client);
		this.registered.add(clusterId);
		return this.clusterNav.categories(clusterId);
	}

	/**
	 * The ClusterRegistry is one bean shared by every test class in this context, so a
	 * cluster left registered here is a cluster every later test sees — pointing at a
	 * mock server that has already shut down.
	 */
	@AfterEach
	void unregisterTheClustersThisTestRegistered() {
		this.registered.forEach(this.registry::unregister);
		this.registered.clear();
	}

	private NavCategory category(String clusterId, String label) {
		return navFor(clusterId).stream().filter((c) -> c.label().equals(label)).findFirst().orElseThrow();
	}

	@Test
	void holdsTheHpaAndTheVpaUnderOneHeadingWhenTheVpaCrdsAreInstalled() {
		vpaCrds();

		assertThat(category("as-1", "Autoscaling").items()).extracting("kind")
			.containsExactly("HorizontalPodAutoscaler", "VerticalPodAutoscaler");
	}

	/**
	 * The scene from #433: two leaves of one list, one built-in and one CRD-delivered,
	 * and nothing on screen says which is which. The CRD's label is written the way the
	 * catalog's is, derived by {@code KindLabel} from what the CRD declares.
	 *
	 * <p>
	 * The ids are asserted in the same breath because that is what must NOT move: the
	 * label is what a reader sees, the id is what a bookmark resolves.
	 */
	@Test
	void labelsTheCrdKindTheWayTheBuiltInBesideItIsLabelled() {
		vpaCrds();

		assertThat(category("as-9", "Autoscaling").items()).extracting("label", "id")
			.containsExactly(tuple("Horizontal Pod Autoscalers", "horizontalpodautoscalers"),
					tuple("Vertical Pod Autoscalers", "autoscaling.k8s.io.verticalpodautoscalers"));
	}

	@Test
	void sitsBetweenTheWorkloadsItActsOnAndConfig() {
		vpaCrds();

		List<String> labels = navFor("as-2").stream().map(NavCategory::label).toList();

		assertThat(labels.indexOf("Autoscaling")).isEqualTo(labels.indexOf("Workloads") + 1);
		assertThat(labels.indexOf("Config")).isEqualTo(labels.indexOf("Autoscaling") + 1);
	}

	@Test
	void noLongerFilesTheHpaUnderConfig() {
		// This list is also what the Config category's badge sums (NavGroup.categoryBadge
		// adds up the counts of exactly these items), so the badge follows the move with
		// no
		// second edit. The Config OVERVIEW does not read it at all: its checks are
		// ConfigUsageService's ConfigMap-and-Secret reference scan, pinned by
		// CategoryOverviewEndpointTest, so an HPA was never in what that card covers.
		vpaCrds();

		assertThat(category("as-3", "Config").items()).extracting("kind").doesNotContain("HorizontalPodAutoscaler");
	}

	@Test
	void leavesTheCheckpointKindUnderItsApiGroupRatherThanPromotingIt() {
		// The recommender's saved histogram, one per target — machinery, not something an
		// operator browses. Demoted, not hidden: the group survives the promotion holding
		// exactly what was left behind.
		vpaCrds();

		NavCategory customResources = category("as-4", "Custom Resources");
		NavCategory group = customResources.subgroups()
			.stream()
			.filter((c) -> c.label().equals(VPA_GROUP))
			.findFirst()
			.orElseThrow();

		assertThat(group.items()).extracting("kind").containsExactly("VerticalPodAutoscalerCheckpoint");
	}

	@Test
	void doesNotDuplicateThePromotedVpaKindUnderCustomResources() {
		vpaCrds();

		NavCategory group = category("as-5", "Custom Resources").subgroups()
			.stream()
			.filter((c) -> c.label().equals(VPA_GROUP))
			.findFirst()
			.orElseThrow();

		assertThat(group.items()).extracting("kind").doesNotContain("VerticalPodAutoscaler");
	}

	@Test
	void holdsTheHpaAloneWhenTheVpaCrdIsNotInstalled() {
		// The control for the conditional half. The category is still offered — HPA is a
		// built-in kind every cluster serves, so there is something to navigate to — and
		// it
		// holds exactly one kind. Declare a VPA kind unconditionally and
		// `containsExactly`
		// is what goes red, which is the Gateway rule stated as a test: a CRD-delivered
		// kind does not go in the menu of a cluster whose API does not serve it.
		crd("ingressroutes", "IngressRoute", "traefik.io", "Namespaced");

		assertThat(category("as-6", "Autoscaling").items()).extracting("kind")
			.containsExactly("HorizontalPodAutoscaler");
	}

	@Test
	void doesNotDependOnHowManyHorizontalPodAutoscalersTheClusterHolds() {
		// The pair to the test above, which measures the same cluster with none: an empty
		// list is a correct answer for a built-in kind and is how an operator sees "no
		// HPAs
		// yet". kweblens shows all 39 built-in kinds regardless of object count, and
		// making
		// this the one exception would be a new principle that nothing on screen
		// explains.
		horizontalPodAutoscaler();

		assertThat(category("as-7", "Autoscaling").items()).extracting("kind")
			.containsExactly("HorizontalPodAutoscaler");
	}

	@Test
	void resolvesBothKindsByTheirRouteIds() {
		// Everything above is presentation. The built-in id resolves on a cluster that
		// was
		// never offered the VPA half, and a promoted kind keeps the id it had under
		// Custom
		// Resources, so a bookmarked list or an agent's resourceId is unaffected.
		vpaCrds();
		navFor("as-8");

		assertThat(this.clusterNav.find("as-8", "horizontalpodautoscalers")).get()
			.extracting("kind")
			.isEqualTo("HorizontalPodAutoscaler");
		assertThat(this.clusterNav.find("as-8", "autoscaling.k8s.io.verticalpodautoscalers")).get()
			.extracting("kind")
			.isEqualTo("VerticalPodAutoscaler");
	}

}

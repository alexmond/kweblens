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

/**
 * The Autoscaling category (#428): the built-in HorizontalPodAutoscaler and the
 * CRD-delivered VerticalPodAutoscaler under one heading, shown only on a cluster that has
 * one of them.
 *
 * <p>
 * <b>The absent case is the point.</b> HPA used to sit in Config, where on most clusters
 * it was a row that could only ever say "0 items"; VPA sat under an API-group name in
 * Custom Resources. Joining them is easy — not growing a dead category on the clusters
 * that autoscale nothing is the part that has to be tested, so
 * {@link #addsNoCategoryWhenTheClusterAutoscalesNothing} is the control: make promotion
 * unconditional and it is the test that goes red.
 *
 * <p>
 * Non-static mock client: what the nav holds depends on which CRDs and objects exist, so
 * a client shared across the class would let one test's cluster decide another test's
 * nav.
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
	void addsNoCategoryWhenTheClusterAutoscalesNothing() {
		// No VPA CRDs and no HorizontalPodAutoscaler. Making promotion unconditional —
		// declaring Autoscaling and always passing it through — makes this test red,
		// which
		// is what "no dead category" has to mean to be worth claiming.
		crd("ingressroutes", "IngressRoute", "traefik.io", "Namespaced");

		assertThat(navFor("as-6")).extracting(NavCategory::label).doesNotContain("Autoscaling");
	}

	@Test
	void appearsOnAClusterThatHasAnHpaButNoVpaCrds() {
		// The other half of the same rule: the category is about what the cluster has,
		// not
		// about which of the two kinds delivers it.
		horizontalPodAutoscaler();

		assertThat(category("as-7", "Autoscaling").items()).extracting("kind")
			.containsExactly("HorizontalPodAutoscaler");
	}

	@Test
	void stillResolvesTheHpaRouteIdOnAClusterThatIsNotOfferedTheCategory() {
		// Withholding the category is presentation only. A bookmarked list, a saved link
		// or an agent's resourceId still resolves, because find() reads the catalog.
		navFor("as-8");

		assertThat(this.clusterNav.find("as-8", "horizontalpodautoscalers")).get()
			.extracting("kind")
			.isEqualTo("HorizontalPodAutoscaler");
	}

}

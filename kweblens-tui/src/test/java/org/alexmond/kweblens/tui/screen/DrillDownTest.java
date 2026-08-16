package org.alexmond.kweblens.tui.screen;

import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.tui.filter.ObjectFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where enter goes, and — where it does not — why, in words.
 *
 * <p>
 * Every query produced here is asserted to <b>parse in this product's own filter
 * grammar</b>. That is not belt-and-braces: the whole value of drill-down-as-a-filter is
 * that the operator can read and edit what it wrote, and a query the filter box cannot
 * parse would be a title claiming a narrowing that is not in force.
 */
class DrillDownTest {

	private static GenericKubernetesResource object(String kind, String name, Map<String, Object> spec) {
		GenericKubernetesResourceBuilder builder = new GenericKubernetesResourceBuilder().withApiVersion("v1")
			.withKind(kind)
			.withNewMetadata()
			.withNamespace("kube-system")
			.withName(name)
			.endMetadata();
		if (spec != null) {
			builder.addToAdditionalProperties("spec", spec);
		}
		return builder.build();
	}

	@Test
	void aWorkloadsMatchLabelsBecomeALabelRequirementPerLabel() {
		DrillDown.Target target = DrillDown.from("Deployment", object("Deployment", "coredns",
				Map.of("selector", Map.of("matchLabels", Map.of("k8s-app", "kube-dns")))));

		assertThat(target.available()).isTrue();
		assertThat(target.kind()).isEqualTo("pods");
		assertThat(target.namespace()).isEqualTo("kube-system");
		assertThat(target.filter()).isEqualTo("k8s-app=kube-dns");
		assertThat(ObjectFilter.parse(target.filter()).failed()).isFalse();
	}

	@Test
	void aServicesSelectorIsFlatRatherThanUnderMatchLabels() {
		DrillDown.Target target = DrillDown.from("Service",
				object("Service", "kube-dns", Map.of("selector", Map.of("k8s-app", "kube-dns"))));

		assertThat(target.filter()).isEqualTo("k8s-app=kube-dns");
		assertThat(ObjectFilter.parse(target.filter()).failed()).isFalse();
	}

	@Test
	void aNamespaceOpensItsPodsWithNoFilterBecauseTheScopeIsTheRelationship() {
		DrillDown.Target target = DrillDown.from("Namespace", object("Namespace", "kube-system", null));

		assertThat(target.kind()).isEqualTo("pods");
		assertThat(target.namespace()).isEqualTo("kube-system");
		assertThat(target.filter()).isEmpty();
	}

	@Test
	void aPodOpensTheEventsAboutItAndTheHeuristicIsOnScreenAsAQuery() {
		DrillDown.Target target = DrillDown.from("Pod", object("Pod", "coredns-abc", null));

		assertThat(target.kind()).isEqualTo("events");
		assertThat(target.filter()).isEqualTo("name:coredns-abc");
		assertThat(ObjectFilter.parse(target.filter()).failed()).isFalse();
	}

	@Test
	void aNodeDeclinesAndNamesTheReasonRatherThanShowingTheWrongPods() {
		DrillDown.Target target = DrillDown.from("Node", object("Node", "node-1", null));

		assertThat(target.available()).isFalse();
		assertThat(target.reason()).contains("spec.nodeName");
	}

	@Test
	void aSelectorThisGrammarCannotWriteIsDeclinedRatherThanApproximated() {
		DrillDown.Target target = DrillDown.from("Deployment", object("Deployment", "odd",
				Map.of("selector", Map.of("matchExpressions", java.util.List.of(Map.of("key", "app"))))));

		assertThat(target.available()).isFalse();
		assertThat(target.reason()).contains("matchExpressions");
	}

	@Test
	void aKindWithNoRelationshipSaysSo() {
		assertThat(DrillDown.from("ConfigMap", object("ConfigMap", "settings", null)).reason())
			.contains("Nothing to drill into from a ConfigMap");
		assertThat(DrillDown.from("Pod", null).reason()).contains("Nothing selected");
	}

}

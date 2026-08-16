package org.alexmond.kweblens.tui.kind;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.resource.DiscoveredKind;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.WellKnownKinds;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Everything the command line can name, and how the names are decided.
 *
 * <p>
 * The kinds below are shaped like a real cluster's discovery, including the two
 * collisions that actually happen ({@code events} in the core group and in
 * {@code events.k8s.io}) and a CRD ({@code traefik.io/ingressroutes}) that no catalog in
 * this repo lists — which is the whole point of the ticket: reachability comes from the
 * API server, not from a table somebody remembered to update.
 */
class KindIndexTest {

	private static final DiscoveredKind INGRESS_ROUTES = new DiscoveredKind(
			ResourceDescriptor.namespaced("traefik.io.ingressroutes", "IngressRoute", "IngressRoute", "traefik.io",
					"v1alpha1", "ingressroutes"),
			"ingressroute", List.of("ir"));

	private static final DiscoveredKind DEPLOYMENTS = new DiscoveredKind(
			ResourceDescriptor.namespaced("apps.deployments", "Deployment", "Deployment", "apps", "v1", "deployments"),
			"deployment", List.of("deploy"));

	private static final DiscoveredKind CORE_EVENTS = new DiscoveredKind(WellKnownKinds.EVENTS, "event", List.of("ev"));

	private static final DiscoveredKind GROUPED_EVENTS = new DiscoveredKind(
			ResourceDescriptor.namespaced("events.k8s.io.events", "Event", "Event", "events.k8s.io", "v1", "events"),
			"event", List.of("ev"));

	private final KindIndex index = KindIndex.of(List.of(GROUPED_EVENTS, INGRESS_ROUTES, DEPLOYMENTS, CORE_EVENTS,
			new DiscoveredKind(WellKnownKinds.PODS, "pod", List.of("po"))));

	@Test
	void aKindAnswersToItsPluralSingularKindAndEveryShortNameTheServerDeclared() {
		assertThat(this.index.resolve("pods")).contains(WellKnownKinds.PODS);
		assertThat(this.index.resolve("pod")).contains(WellKnownKinds.PODS);
		assertThat(this.index.resolve("Pod")).as("case is not part of the name").contains(WellKnownKinds.PODS);
		assertThat(this.index.resolve("po")).contains(WellKnownKinds.PODS);
		assertThat(this.index.resolve("deploy")).contains(DEPLOYMENTS.descriptor());
	}

	@Test
	void aCrdKindNoCatalogListsIsReachableByItsShortNameBecauseTheServerPublishedIt() {
		assertThat(this.index.resolve("ir")).contains(INGRESS_ROUTES.descriptor());
		assertThat(this.index.resolve("ingressroutes")).contains(INGRESS_ROUTES.descriptor());
		assertThat(this.index.resolve("ingressroutes.traefik.io")).contains(INGRESS_ROUTES.descriptor());
		assertThat(this.index.resolve("traefik.io/v1alpha1/ingressroutes")).contains(INGRESS_ROUTES.descriptor());
	}

	@Test
	void whenTwoGroupsWantTheSameWordTheCoreGroupTakesItAndTheOtherKeepsItsQualifiedNames() {
		assertThat(this.index.resolve("events")).as("core wins the bare word").contains(WellKnownKinds.EVENTS);
		assertThat(this.index.resolve("ev")).contains(WellKnownKinds.EVENTS);
		assertThat(this.index.resolve("events.events.k8s.io")).as("the loser is not lost")
			.contains(GROUPED_EVENTS.descriptor());
		assertThat(this.index.resolve("events.k8s.io/v1/events")).contains(GROUPED_EVENTS.descriptor());
		assertThat(this.index.resolve("v1/events")).contains(WellKnownKinds.EVENTS);
	}

	@Test
	void theOrderKindsArriveInDoesNotDecideWhoWinsACollision() {
		KindIndex reversed = KindIndex.of(List.of(CORE_EVENTS, GROUPED_EVENTS));
		KindIndex forwards = KindIndex.of(List.of(GROUPED_EVENTS, CORE_EVENTS));

		assertThat(reversed.resolve("events")).isEqualTo(forwards.resolve("events")).contains(WellKnownKinds.EVENTS);
	}

	@Test
	void completionOffersWhatWasDiscoveredAndStopsAtThePrefix() {
		assertThat(this.index.complete("po", 10)).containsExactly("po", "pod", "pods");
		assertThat(this.index.complete("ingress", 10)).contains("ingressroute", "ingressroutes",
				"ingressroutes.traefik.io");
		assertThat(this.index.complete("zzz", 10)).isEmpty();
		assertThat(this.index.complete("", 3)).as("an empty prefix still offers something to learn from").hasSize(3);
	}

	@Test
	void theInlineSuggestionIsTheNextNameAndNeverTheOneAlreadyTyped() {
		assertThat(this.index.suggestion("po")).contains("pod");
		assertThat(this.index.suggestion("pods")).as("the whole word is typed; there is nothing left to suggest")
			.isEmpty();
		assertThat(this.index.suggestion("")).isEmpty();
	}

	@Test
	void anUndiscoveredClusterNamesNothingAndSaysSoByBeingEmptyRatherThanByGuessing() {
		assertThat(KindIndex.empty().resolve("pods")).isEmpty();
		assertThat(KindIndex.empty().size()).isZero();
		assertThat(KindIndex.of(List.of()).aliases()).isEmpty();
	}

}

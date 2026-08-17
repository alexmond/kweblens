package org.alexmond.kweblens.tui.screen;

import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.resource.WellKnownKinds;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The gate for #365's navigation.</b> The command line reaches any discovered kind,
 * the stack walks back one level at a time, and {@code esc} clears a filter before it
 * pops.
 *
 * <p>
 * No terminal is involved: keys go in as {@link KeyStroke}s and what comes out is a view,
 * a filter and a breadcrumb list. That split is the reason these claims are assertable at
 * all, and it is the same one {@code ResourceModel} already keeps.
 */
class ViewControllerTest {

	private final FakeNavigation navigation = new FakeNavigation();

	private final ResourceModel model = new ResourceModel();

	private final ViewController controller = new ViewController(this.navigation, this.model,
			View.of(WellKnownKinds.PODS, "kube-system"), () -> 10);

	private static GenericKubernetesResource deployment(String name, Map<String, String> matchLabels) {
		return new GenericKubernetesResourceBuilder().withApiVersion("apps/v1")
			.withKind("Deployment")
			.withNewMetadata()
			.withNamespace("kube-system")
			.withName(name)
			.endMetadata()
			.addToAdditionalProperties("spec", Map.of("selector", Map.of("matchLabels", matchLabels)))
			.build();
	}

	private void type(String text) {
		text.chars().forEach((c) -> this.controller.key(KeyStroke.of((char) c)));
	}

	private void command(String line) {
		this.controller.key(KeyStroke.of(':'));
		type(line);
		this.controller.key(KeyStroke.key(KeyStroke.Kind.ENTER));
	}

	@Test
	void theCommandLineReachesACrdKindTheCatalogNeverHeardOf() {
		command("ir");

		assertThat(this.controller.message()).isEmpty();
		assertThat(this.navigation.last().descriptor()).isEqualTo(FakeNavigation.INGRESS_ROUTES);
		assertThat(this.controller.current().crumb()).isEqualTo("ingressroutes");
	}

	/**
	 * GH#434's controller half. A kind that resolves and a view that could not be filled
	 * are different failures and the second one used to have nowhere to go: the session
	 * threw, and the exception went past this class entirely. It lands in the same
	 * {@link ViewController#message()} as the case above, and the level stays where the
	 * operator put it — {@code esc} is the way back, not an automatic rollback.
	 */
	@Test
	void aViewTheSessionCouldNotFillSaysSoAndStaysWhereTheOperatorPutIt() {
		this.navigation.refusing("Could not watch IngressRoute (kube-system): forbidden");

		command("ir");

		assertThat(this.controller.message()).isEqualTo("Could not watch IngressRoute (kube-system): forbidden");
		assertThat(this.controller.current().descriptor())
			.as("the level the operator asked for is the level they are on")
			.isEqualTo(FakeNavigation.INGRESS_ROUTES);
		assertThat(this.navigation.last().descriptor()).isEqualTo(FakeNavigation.INGRESS_ROUTES);
	}

	@Test
	void aKindNobodyServesIsRefusedWithTheNearestNamesRatherThanSilently() {
		command("podz");

		assertThat(this.controller.message()).contains("No kind named 'podz'").contains("this cluster serves");
		assertThat(this.navigation.shown()).isEmpty();
	}

	@Test
	void aCommandTakesANamespaceAndAFilterAndTheFilterIsWhatNarrowsTheRows() {
		this.model.replaceAll(List.of(new ResourceRow("kube-system/coredns-a", "kube-system", "coredns-a", null, "1d"),
				new ResourceRow("kube-system/traefik", "kube-system", "traefik", null, "1d")));

		command("po kube-system /coredns");

		assertThat(this.navigation.last().namespace()).isEqualTo("kube-system");
		assertThat(this.controller.current().filter()).isEqualTo("coredns");
		this.model.applyFilter(this.navigation.lastFilter());
		assertThat(this.model.rows()).extracting(ResourceRow::name).containsExactly("coredns-a");
	}

	@Test
	void anUnsupportedHalfOfTheGrammarIsRefusedRatherThanQuietlyDropped() {
		command("po -f cored");

		assertThat(this.controller.message()).contains("No fuzzy matching");
		assertThat(this.navigation.shown()).isEmpty();
	}

	@Test
	void drillingThreeLevelsShowsThreeBreadcrumbsAndEscapePopsThemOneAtATime() {
		// namespaces -> pods in one -> events about one pod: three levels, and every
		// relationship is a filter the operator can read.
		command("ns");
		assertThat(this.controller.crumbs()).containsExactly("pods", "namespaces");

		this.navigation.withObject(new GenericKubernetesResourceBuilder().withApiVersion("v1")
			.withKind("Namespace")
			.withNewMetadata()
			.withName("kube-system")
			.endMetadata()
			.build());
		this.model.replaceAll(List.of(new ResourceRow("/kube-system", "", "kube-system", null, "1d")));
		this.controller.key(KeyStroke.key(KeyStroke.Kind.ENTER));

		assertThat(this.controller.crumbs()).containsExactly("pods", "namespaces", "pods");
		assertThat(this.navigation.last().namespace()).isEqualTo("kube-system");

		this.navigation.withObject(new GenericKubernetesResourceBuilder().withApiVersion("v1")
			.withKind("Pod")
			.withNewMetadata()
			.withNamespace("kube-system")
			.withName("coredns-abc")
			.endMetadata()
			.build());
		this.model
			.replaceAll(List.of(new ResourceRow("kube-system/coredns-abc", "kube-system", "coredns-abc", null, "1d")));
		this.controller.key(KeyStroke.key(KeyStroke.Kind.ENTER));

		assertThat(this.controller.crumbs()).as("three drills, four levels counting the root")
			.containsExactly("pods", "namespaces", "pods", "events");
		assertThat(this.controller.current().filter()).isEqualTo("name:coredns-abc");
		assertThat(this.controller.depth()).isEqualTo(4);

		// The first escape widens this level rather than leaving it: a drill-down IS a
		// filter, so "clear the filter before popping" applies to it like any other. That
		// is deliberate — it is the step where the operator gets to see every event in
		// the
		// namespace rather than only this pod's, without retyping anything.
		this.controller.key(KeyStroke.key(KeyStroke.Kind.ESCAPE));
		assertThat(this.controller.current().filter()).isEmpty();
		assertThat(this.controller.crumbs()).containsExactly("pods", "namespaces", "pods", "events");

		this.controller.key(KeyStroke.key(KeyStroke.Kind.ESCAPE));
		assertThat(this.controller.crumbs()).as("then one level, and only one")
			.containsExactly("pods", "namespaces", "pods");
		this.controller.key(KeyStroke.key(KeyStroke.Kind.ESCAPE));
		assertThat(this.controller.crumbs()).containsExactly("pods", "namespaces");
		this.controller.key(KeyStroke.key(KeyStroke.Kind.ESCAPE));
		assertThat(this.controller.crumbs()).containsExactly("pods");
	}

	@Test
	void escapeClearsTheFilterBeforeItPopsAnything() {
		command("ns");
		this.controller.key(KeyStroke.of('/'));
		type("kube");
		this.controller.key(KeyStroke.key(KeyStroke.Kind.ENTER));
		assertThat(this.controller.current().filter()).isEqualTo("kube");
		assertThat(this.controller.depth()).isEqualTo(2);

		assertThat(this.controller.key(KeyStroke.key(KeyStroke.Kind.ESCAPE))).isEqualTo(ViewController.Outcome.REPAINT);

		assertThat(this.controller.current().filter()).as("the first escape took the filter").isEmpty();
		assertThat(this.controller.depth()).as("and left the level alone").isEqualTo(2);

		this.controller.key(KeyStroke.key(KeyStroke.Kind.ESCAPE));
		assertThat(this.controller.depth()).as("the second escape took the level").isEqualTo(1);
	}

	@Test
	void atTheRootQQuitsAndEscapeDoesNot() {
		assertThat(this.controller.key(KeyStroke.key(KeyStroke.Kind.ESCAPE)))
			.as("escape at the root is not one keystroke away from losing the session")
			.isEqualTo(ViewController.Outcome.NONE);
		assertThat(this.controller.key(KeyStroke.of('q'))).isEqualTo(ViewController.Outcome.QUIT);
	}

	@Test
	void aDrillDownIsAVisibleLabelSelectorInThisProductsOwnGrammar() {
		command("deploy");
		this.navigation.withObject(deployment("coredns", Map.of("k8s-app", "kube-dns")));
		this.model.replaceAll(List.of(new ResourceRow("kube-system/coredns", "kube-system", "coredns", null, "1d")));

		this.controller.key(KeyStroke.key(KeyStroke.Kind.ENTER));

		assertThat(this.controller.current().filter()).isEqualTo("k8s-app=kube-dns");
		assertThat(this.navigation.last().descriptor()).isEqualTo(WellKnownKinds.PODS);
		// And the query it produced really selects what it claims to: the same grammar
		// the
		// filter box parses, not a second one.
		assertThat(RowFilters.of("k8s-app=kube-dns", "Pod")
			.test(new ResourceRow("kube-system/coredns-1", "kube-system", "coredns-1", null, "1d",
					Map.of("k8s-app", "kube-dns"))))
			.isTrue();
		assertThat(RowFilters.of("k8s-app=kube-dns", "Pod")
			.test(new ResourceRow("kube-system/traefik", "kube-system", "traefik", null, "1d",
					Map.of("app", "traefik"))))
			.isFalse();
	}

	@Test
	void aDrillDownWithNowhereToGoSaysWhyRatherThanShowingAnEmptyList() {
		command("no");
		this.navigation.withObject(new GenericKubernetesResourceBuilder().withApiVersion("v1")
			.withKind("Node")
			.withNewMetadata()
			.withName("node-1")
			.endMetadata()
			.build());
		this.model.replaceAll(List.of(new ResourceRow("/node-1", "", "node-1", null, "1d")));
		int before = this.navigation.shown().size();

		this.controller.key(KeyStroke.key(KeyStroke.Kind.ENTER));

		assertThat(this.controller.message()).contains("spec.nodeName").contains("no field selectors");
		assertThat(this.navigation.shown()).hasSize(before);
	}

	@Test
	void theNumberKeysReScopeTheLevelToANamespaceYouHaveActuallyVisited() {
		command("po kube-system");
		command("po default");

		this.controller.key(KeyStroke.of('2'));

		assertThat(this.navigation.last().namespace()).as("1 is the newest, 2 the one before").isEqualTo("kube-system");
		this.controller.key(KeyStroke.of('0'));
		assertThat(this.navigation.last().namespace()).as("0 is always every namespace").isNull();
		this.controller.key(KeyStroke.of('9'));
		assertThat(this.controller.message()).contains("No namespace on 9");
	}

	@Test
	void tabTakesTheInlineCompletionWhichCameFromDiscovery() {
		this.controller.key(KeyStroke.of(':'));
		type("ingress");
		assertThat(this.controller.prompt().suggestion()).isEqualTo("ingressroute");
		assertThat(this.controller.prompt().candidates()).contains("ingressroutes.traefik.io");

		this.controller.key(KeyStroke.key(KeyStroke.Kind.TAB));

		assertThat(this.controller.prompt().text()).isEqualTo("ingressroute");
	}

}

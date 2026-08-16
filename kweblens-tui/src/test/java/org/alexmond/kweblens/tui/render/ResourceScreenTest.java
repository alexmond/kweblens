package org.alexmond.kweblens.tui.render;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.resource.WellKnownKinds;
import org.alexmond.kweblens.tui.data.ResourceQuery;
import org.alexmond.kweblens.tui.screen.KeyStroke;
import org.alexmond.kweblens.tui.screen.ResourceModel;
import org.alexmond.kweblens.tui.screen.ResourceRow;
import org.alexmond.kweblens.tui.screen.TickRate;
import org.alexmond.kweblens.tui.screen.WatchCoalescer;
import org.alexmond.kweblens.tui.screen.WatchSupervisor;

import static org.assertj.core.api.Assertions.assertThat;

/** What the two chrome lines claim — every part of which an operator will believe. */
class ResourceScreenTest {

	private final ResourceModel model = new ResourceModel();

	private ResourceScreen screen(ResourceQuery query, TickRate tick) {
		WatchSupervisor supervisor = new WatchSupervisor(Clock.systemUTC(),
				(generation, rows) -> this.model.replaceAll(rows), (onEnd) -> List.of());
		return new ResourceScreen(this.model, new WatchCoalescer(this.model, (objects) -> List.of()), supervisor, query,
				tick);
	}

	@Test
	void theHeaderNamesThePostureTheClusterTheKindTheScopeAndTheTick() {
		ResourceScreen screen = screen(new ResourceQuery("prod", WellKnownKinds.PODS, "kube-system"),
				TickRate.defaults());

		assertThat(screen.header()).isEqualTo("kweblens-tui [R] · prod · Pod · kube-system · 0 rows · tick 100ms");
	}

	@Test
	void noNamespaceMeansEveryNamespaceAndSaysSo() {
		ResourceScreen screen = screen(new ResourceQuery("prod", WellKnownKinds.PODS, null), TickRate.defaults());

		assertThat(screen.header()).contains("all namespaces");
	}

	@Test
	void aClusterScopedKindIsNotAllNamespaces() {
		ResourceScreen screen = screen(new ResourceQuery("prod", WellKnownKinds.NODES, null), TickRate.defaults());

		assertThat(screen.header()).contains("cluster-scoped").doesNotContain("all namespaces");
	}

	@Test
	void aClampedTickIsInTheHeaderForAsLongAsTheScreenIsUp() {
		ResourceScreen screen = screen(new ResourceQuery("prod", WellKnownKinds.PODS, null),
				TickRate.of(Duration.ofMillis(1)));

		assertThat(screen.header()).contains("tick 20ms (tick 1ms raised to 20ms)");
	}

	@Test
	void theFooterSaysWhereTheCursorIsAndWhatItIsOn() {
		this.model.replaceAll(List.of(new ResourceRow("ns/a", "ns", "a", "Running", "1d"),
				new ResourceRow("ns/b", "ns", "b", "Running", "1d")));
		ResourceScreen screen = screen(new ResourceQuery("prod", WellKnownKinds.PODS, "ns"), TickRate.defaults());
		this.model.selectTo(1);

		assertThat(screen.footer()).contains("2/2").contains("b");
	}

	@Test
	void anEmptyListSaysZeroOfZeroAndNamesNoRow() {
		ResourceScreen screen = screen(new ResourceQuery("prod", WellKnownKinds.PODS, "ns"), TickRate.defaults());

		assertThat(screen.footer()).contains("0/0").contains("—");
	}

	@Test
	void theFrameTitleCarriesTheKindTheScopeAndTheCount() {
		this.model.replaceAll(List.of(new ResourceRow("ns/a", "ns", "a", "Running", "1d"),
				new ResourceRow("ns/b", "ns", "b", "Running", "1d")));
		ResourceScreen screen = screen(new ResourceQuery("prod", WellKnownKinds.PODS, "kube-system"),
				TickRate.defaults());

		assertThat(screen.title()).startsWith("pods(kube-system)[2]");
	}

	@Test
	void theFrameTitleShowsTheActiveFilterAndHowMuchItIsHiding() {
		this.model.replaceAll(List.of(new ResourceRow("ns/coredns-a", "ns", "coredns-a", "Running", "1d"),
				new ResourceRow("ns/traefik", "ns", "traefik", "Running", "1d")));
		ResourceScreen screen = screen(new ResourceQuery("prod", WellKnownKinds.PODS, "ns"), TickRate.defaults());

		screen.controller().key(KeyStroke.of('/'));
		"coredns".chars().forEach((c) -> screen.controller().key(KeyStroke.of((char) c)));
		screen.controller().key(KeyStroke.key(KeyStroke.Kind.ENTER));

		assertThat(screen.title()).contains("pods(ns)[1]").contains("of 2").contains("</coredns>");
	}

	@Test
	void theHintBarIsTheBindingTableAndNotAStringBesideIt() {
		ResourceScreen screen = screen(new ResourceQuery("prod", WellKnownKinds.PODS, "ns"), TickRate.defaults());
		screen.controller().key(KeyStroke.of('?'));

		assertThat(screen.controller().help()).isTrue();
	}

}

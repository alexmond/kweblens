package org.alexmond.kweblens.tui.render;

import java.util.Map;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.tui.kind.KindCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Navigation on the <b>running</b> screen: the same real TamboUI loop
 * {@link ScreenLoopTest} uses, with keys dispatched as events.
 *
 * <p>
 * The controller's own tests ({@code ViewControllerTest}) prove the rules. This proves
 * the wiring the rules travel through — that a key reaches the table, that switching
 * kinds really closes one watch and opens another, and that the model is emptied rather
 * than showing the previous kind's rows under the new kind's title.
 */
class ScreenNavigationTest {

	private static void press(ScreenHarness harness, char character) {
		harness.runner().dispatch(KeyEvent.ofChar(character));
	}

	private static void type(ScreenHarness harness, String text) {
		text.chars().forEach((c) -> press(harness, (char) c));
	}

	private static void press(ScreenHarness harness, KeyCode code) {
		harness.runner().dispatch(KeyEvent.ofKey(code));
	}

	@Test
	void aCommandSwitchesTheKindAndTheOldWatchIsReleasedBeforeTheNewOneOpens() throws Exception {
		FakeCluster cluster = new FakeCluster().withObjects(3);
		try (ScreenHarness harness = ScreenHarness.start(cluster, 500)) {
			assertThat(cluster.watchesOpened()).isEqualTo(1);
			assertThat(harness.session().model().size()).isEqualTo(3);

			harness.session().kinds(new KindCatalog(cluster).of("fake"));
			press(harness, ':');
			type(harness, "ns");
			press(harness, KeyCode.ENTER);
			// Awaited on the LAST thing the switch does, not the first: setting the query
			// happens before the watch is opened and the list is taken, so a test that
			// waited on the kind would read a half-finished switch.
			harness.await(() -> cluster.lists() >= 2, "the new kind to be listed");

			assertThat(cluster.watchesOpened()).as("one new watch for the new kind").isEqualTo(2);
			assertThat(cluster.watchesClosed()).as("and the previous one was released, not leaked").isEqualTo(1);
			assertThat(harness.screen().title()).startsWith("namespaces");
			assertThat(harness.screen().controller().crumbs()).containsExactly("pods", "namespaces");
		}
	}

	@Test
	void switchingKindEmptiesTheModelRatherThanLeavingTheOldKindsRowsUnderTheNewTitle() throws Exception {
		FakeCluster cluster = new FakeCluster().withObjects(4);
		try (ScreenHarness harness = ScreenHarness.start(cluster, 500)) {
			assertThat(harness.session().model().size()).isEqualTo(4);
			cluster.setObjects(java.util.List.of(FakeCluster.object("only-one")));

			harness.session().kinds(new KindCatalog(cluster).of("fake"));
			press(harness, ':');
			type(harness, "ns");
			press(harness, KeyCode.ENTER);
			harness.await(() -> cluster.lists() >= 2, "the new kind to be listed");

			assertThat(harness.session().model().rows()).extracting((row) -> row.name()).containsExactly("only-one");
		}
	}

	@Test
	void escapeAtTheRootWithNoFilterLeavesTheScreenRunningAndQQuits() throws Exception {
		FakeCluster cluster = new FakeCluster().withObjects(2);
		try (ScreenHarness harness = ScreenHarness.start(cluster, 500)) {
			press(harness, KeyCode.ESCAPE);
			harness.tickAndSettle();
			assertThat(harness.running()).as("escape is not one keystroke away from losing the session").isTrue();

			press(harness, 'q');
			harness.await(() -> !harness.running(), "q at the root to quit");
		}
	}

	@Test
	void drillingIntoADeploymentOpensItsPodsWithTheSelectorVisibleInTheTitle() throws Exception {
		FakeCluster cluster = new FakeCluster().withObjects(1);
		cluster
			.withObject(new io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder().withApiVersion("apps/v1")
				.withKind("Deployment")
				.withNewMetadata()
				.withNamespace("ns")
				.withName("coredns")
				.endMetadata()
				.addToAdditionalProperties("spec",
						Map.of("selector", Map.of("matchLabels", Map.of("k8s-app", "kube-dns"))))
				.build());
		try (ScreenHarness harness = ScreenHarness.start(cluster, 500)) {
			harness.session().kinds(new KindCatalog(cluster).of("fake"));
			press(harness, ':');
			type(harness, "deploy");
			press(harness, KeyCode.ENTER);
			harness.await(() -> cluster.lists() >= 2, "deployments to open");

			press(harness, KeyCode.ENTER);
			harness.await(() -> harness.screen().controller().depth() == 3, "the drill-down");

			assertThat(harness.screen().title()).contains("pods(ns)").contains("</k8s-app=kube-dns>");
			assertThat(harness.screen().controller().crumbs()).containsExactly("pods", "deployments", "pods");
		}
	}

}

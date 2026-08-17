package org.alexmond.kweblens.tui.render;

import java.util.List;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.resource.WellKnownKinds;
import org.alexmond.kweblens.tui.kind.KindCatalog;
import org.alexmond.kweblens.tui.screen.MovableClock;
import org.alexmond.kweblens.tui.screen.ResourceRow;
import org.alexmond.kweblens.tui.screen.WatchSupervisor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The gate for GH#431.</b> A reconnect that finishes after the operator has moved the
 * screen to another kind belongs to a subscription nobody is showing, and neither its
 * rows nor its verdict on liveness may reach the screen.
 *
 * <h2>The window, and why it has to be pinned</h2>
 *
 * A recovery is re-subscribe, re-list, {@code replaceAll}, and the re-list reads the
 * session's query on the recovery thread. {@code switchTo} runs on the render thread and
 * retargets that query, empties the model and opens a subscription of its own — so a
 * {@code :ns} typed while the re-list is on the network produces a list of the
 * <em>old</em> kind, projected through a projection now pointed at the <em>new</em> one,
 * arriving after the new kind's rows are already on screen. Installed unconditionally, it
 * puts pods under the namespaces title and says the table is live.
 *
 * <p>
 * {@link FakeCluster#holdNextList()} parks the reconnect exactly there — between its
 * re-subscribe and its re-list — so the switch happens at a pinned point inside the
 * window rather than wherever the scheduler puts it. The benign orderings (the switch
 * before the loss, or after the recovery has landed) pass on a broken build, which is why
 * neither of them is what this test drives.
 *
 * <h2>Both directions of the header</h2>
 *
 * The screen must not still say NOT LIVE about a watch it replaced itself — the switch
 * opened a subscription and listed it on the render thread — and it must not be talked
 * out of NOT LIVE by a list it is not showing. So liveness is asserted twice: once at the
 * switch, before the reconnect has finished, and once after the superseded outcome has
 * been discarded.
 */
class ScreenSwitchDuringRecoveryTest {

	private final MovableClock clock = MovableClock.at("2026-08-13T12:00:00Z");

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
	void aReconnectThatFinishesAfterAKindSwitchInstallsNothingAndDecidesNothing() throws Exception {
		FakeCluster cluster = new FakeCluster()
			.setObjects(WellKnownKinds.PODS, List.of(FakeCluster.object("pod-alpha"), FakeCluster.object("pod-beta")))
			.setObjects(WellKnownKinds.NAMESPACES, List.of(FakeCluster.object("Namespace", "kube-system"),
					FakeCluster.object("Namespace", "default")));
		try (ScreenHarness harness = ScreenHarness.start(cluster, 500, this.clock)) {
			WatchSupervisor supervisor = harness.session().supervisor();
			harness.session().kinds(new KindCatalog(cluster).of("fake"));

			// The control: pods are on screen, the table is live, and the watch is
			// demonstrably delivering.
			cluster.fire("MODIFIED", FakeCluster.object("pod-alpha"));
			harness.tickAndSettle();
			assertThat(supervisor.live()).isTrue();
			assertThat(harness.session().model().rows()).extracting(ResourceRow::name)
				.containsExactly("pod-alpha", "pod-beta");

			// The watch dies, and the re-list is parked: the screen is now sitting inside
			// the recovery, NOT LIVE, with a replacement watch open and no rows for it
			// yet.
			cluster.holdNextList();
			cluster.killWatch(false);
			harness.tickUntil(cluster::listHeld, "the reconnect to reach its re-list");
			assertThat(cluster.watchesOpened()).as("the replacement watch is already open").isEqualTo(2);
			assertThat(supervisor.live()).as("and the screen says so while it waits").isFalse();

			// The operator types :ns inside that window. This runs on the render thread,
			// through the real key path, while the recovery thread is still parked.
			press(harness, ':');
			type(harness, "ns");
			press(harness, KeyCode.ENTER);
			harness.await(() -> cluster.lists() >= 2, "the new kind to be listed");

			assertThat(harness.session().model().rows()).extracting(ResourceRow::name)
				.containsExactly("default", "kube-system");
			assertThat(supervisor.live())
				.as("the screen replaced the watch itself and has this kind's rows, so NOT LIVE is over")
				.isTrue();
			assertThat(harness.screen().header()).doesNotContain("NOT LIVE").contains("2 rows");

			// Let the superseded re-list finish. It lists PODS — the query it read before
			// the switch — and projects them through a projection now pointed at
			// namespaces.
			cluster.releaseList();
			harness.await(supervisor::recoveryPending, "the superseded reconnect to hand back its rows");
			harness.tickAndSettle();

			assertThat(harness.session().model().rows())
				.as("the pods that reconnect listed are not rows of the kind on screen, and were not installed")
				.extracting(ResourceRow::name)
				.containsExactly("default", "kube-system");
			assertThat(supervisor.superseded()).as("counted rather than assumed: the guard is proved to have fired")
				.isEqualTo(1);
			assertThat(supervisor.recoveries()).as("nothing was recovered — the screen had already moved on").isZero();
			assertThat(supervisor.live()).as("and liveness is still the switch's answer, not the discarded list's")
				.isTrue();

			// The control at the other end: the subscription the switch opened is the one
			// being heard, so nothing above turned the new kind's watch off.
			cluster.fire("ADDED", FakeCluster.object("Namespace", "after-the-switch"));
			harness.tickAndSettle();
			assertThat(harness.session().model().rows()).extracting(ResourceRow::name)
				.containsExactly("after-the-switch", "default", "kube-system");
		}
	}

}

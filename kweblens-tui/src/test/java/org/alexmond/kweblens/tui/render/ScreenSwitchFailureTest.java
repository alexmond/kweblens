package org.alexmond.kweblens.tui.render;

import java.time.Duration;
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
 * <b>The gate for GH#434.</b> A {@code :} command whose own subscribe or list is refused
 * leaves the screen in one state, says which, and does not take the event loop with it.
 *
 * <h2>Two failure points, and they are not the same moment</h2>
 *
 * {@code switchTo} closes the old watch, retargets, empties the model and then makes two
 * calls that reach the cluster. {@code watch} fails <b>before any subscription exists</b>
 * — nothing is open, and nothing ever will be unless something asks again. {@code list}
 * fails <b>after one does</b>, so a watch is open and delivering into a buffer held
 * behind a list that never landed. A fix for the second alone leaves the first, which is
 * why there are two tests here and not one with a parameter.
 *
 * <h2>What escaping costs, measured rather than assumed</h2>
 *
 * See {@link TuiRunnerEscapedExceptionTest}: TamboUI catches the exception and never
 * gives the screen back. So "the loop is still running" is <b>not</b> the assertion that
 * matters — it is true either way. The assertion that matters is that ticks are still
 * being handled, which every {@code tickAndSettle} below depends on, and that the screen
 * can still recover when the cluster does.
 *
 * <h2>Which coherent state, and why this one</h2>
 *
 * <b>On the kind the operator asked for, empty, NOT LIVE, with the reason in the
 * footer.</b> The view stack was pushed before the session was asked for anything, so
 * rolling the rows back would put one kind's rows under another kind's title — GH#431
 * arrived at from the other side — and rolling the stack back too would need the same
 * subscribe-and-list pair that has just been refused. {@code esc} is the way back, and it
 * is a navigation like any other, reporting its own outcome.
 */
class ScreenSwitchFailureTest {

	/** Long enough to clear the supervisor's first backoff, which is one second. */
	private static final Duration PAST_THE_BACKOFF = Duration.ofSeconds(5);

	private final MovableClock clock = MovableClock.at("2026-08-16T12:00:00Z");

	private static void press(ScreenHarness harness, char character) {
		harness.runner().dispatch(KeyEvent.ofChar(character));
	}

	private static void type(ScreenHarness harness, String text) {
		text.chars().forEach((c) -> press(harness, (char) c));
	}

	private static void press(ScreenHarness harness, KeyCode code) {
		harness.runner().dispatch(KeyEvent.ofKey(code));
	}

	private static FakeCluster twoKinds() {
		return new FakeCluster()
			.setObjects(WellKnownKinds.PODS, List.of(FakeCluster.object("pod-alpha"), FakeCluster.object("pod-beta")))
			.setObjects(WellKnownKinds.NAMESPACES, List.of(FakeCluster.object("Namespace", "default"),
					FakeCluster.object("Namespace", "kube-system")));
	}

	@Test
	void aSwitchWhoseWatchIsRefusedStaysOnTheNewKindEmptyAndNotLive() throws Exception {
		FakeCluster cluster = twoKinds();
		try (ScreenHarness harness = ScreenHarness.start(cluster, 500, this.clock)) {
			WatchSupervisor supervisor = harness.session().supervisor();
			harness.session().kinds(new KindCatalog(cluster).of("fake"));

			// The control: pods are on screen and the table is live.
			harness.tickAndSettle();
			assertThat(supervisor.live()).isTrue();
			assertThat(harness.session().model().rows()).extracting(ResourceRow::name)
				.containsExactly("pod-alpha", "pod-beta");

			// The cluster will not open a watch on the kind the operator is about to ask
			// for. This is the RBAC case: discovery names the kind, the verb is refused.
			cluster.refuseWatch(new IllegalStateException("namespaces is forbidden: cannot watch namespaces"));
			press(harness, ':');
			type(harness, "ns");
			press(harness, KeyCode.ENTER);
			harness.tickUntil(() -> !supervisor.live(), "the refused switch to reach the header");

			assertThat(harness.session().query().kind()).as("the screen is on the kind that was asked for")
				.isEqualTo("Namespace");
			assertThat(harness.screen().controller().current().descriptor().kind())
				.as("and so is the level the operator is standing on")
				.isEqualTo("Namespace");
			assertThat(harness.session().model().rows()).as("with nothing in it, because nothing was listed").isEmpty();
			assertThat(harness.screen().header()).as("and the header does not call an empty table live")
				.contains("NOT LIVE")
				.contains("could not watch")
				.contains("cannot watch namespaces");
			assertThat(harness.screen().footer()).as("the footer names the kind and what could not be done")
				.contains("Could not watch Namespace")
				.contains("cannot watch namespaces");

			// And it is not a dead end: the retry loop the supervisor runs for a watch
			// that died takes this kind up too, so the screen heals when the cluster
			// does.
			cluster.serveAgain();
			this.clock.advance(PAST_THE_BACKOFF);
			harness.tickUntil(supervisor::live, "the screen to recover the kind it switched to");
			assertThat(harness.session().model().rows()).extracting(ResourceRow::name)
				.containsExactly("default", "kube-system");
			assertThat(harness.screen().header()).doesNotContain("NOT LIVE").contains("2 rows");
		}
	}

	@Test
	void aSwitchWhoseListIsRefusedKeepsNoneOfThePagesItDidGet() throws Exception {
		FakeCluster cluster = twoKinds();
		// One object per page, so the refusal lands with a page already projected.
		try (ScreenHarness harness = ScreenHarness.start(cluster, 1, this.clock)) {
			WatchSupervisor supervisor = harness.session().supervisor();
			harness.session().kinds(new KindCatalog(cluster).of("fake"));

			harness.tickAndSettle();
			assertThat(supervisor.live()).isTrue();
			assertThat(cluster.watchesOpened()).isEqualTo(1);

			cluster.refuseList(1, new IllegalStateException("namespaces is forbidden: cannot list namespaces"));
			press(harness, ':');
			type(harness, "ns");
			press(harness, KeyCode.ENTER);
			harness.tickUntil(() -> !supervisor.live(), "the refused list to reach the header");

			assertThat(cluster.watchesOpened()).as("this failure is the other side of the pair: the watch was opened")
				.isGreaterThanOrEqualTo(2);
			assertThat(harness.session().query().kind()).isEqualTo("Namespace");
			assertThat(harness.session().model().rows())
				.as("the page the list did deliver is dropped — a partial count reads as a collection's size")
				.isEmpty();
			assertThat(harness.screen().header()).contains("NOT LIVE")
				.contains("could not list")
				.contains("cannot list namespaces");
			assertThat(harness.screen().footer()).contains("Could not list Namespace")
				.contains("cannot list namespaces");

			cluster.serveAgain();
			this.clock.advance(PAST_THE_BACKOFF);
			harness.tickUntil(supervisor::live, "the screen to recover the kind it switched to");
			assertThat(harness.session().model().rows()).extracting(ResourceRow::name)
				.containsExactly("default", "kube-system");
			assertThat(harness.screen().header()).doesNotContain("NOT LIVE").contains("2 rows");
		}
	}

}

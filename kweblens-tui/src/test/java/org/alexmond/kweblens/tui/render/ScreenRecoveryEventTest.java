package org.alexmond.kweblens.tui.render;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.tui.screen.MovableClock;
import org.alexmond.kweblens.tui.screen.ResourceRow;
import org.alexmond.kweblens.tui.screen.WatchSupervisor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The gate for GH#420</b>, the mirror of {@link ScreenStaleEventTest}. An event from
 * the subscription whose list is still in flight is newer than that list, and must
 * survive the {@code replaceAll} that installs it.
 *
 * <h2>The window, and why a flush has to happen inside it</h2>
 *
 * A recovery is re-subscribe, re-list, {@code replaceAll}. The replacement watch is
 * delivering from the re-subscribe, so its events buffer while the list is on the network
 * — and a tick in that interval <em>flushes</em> them, onto the stale rows, which is
 * harmless. The harm is the next tick: {@code replaceAll} is a replacement, so everything
 * that flush applied is gone, and an event that happened after the list was taken has
 * been lost with nothing coming to correct it. A row that then stops changing keeps the
 * pre-event value until the next full recovery.
 *
 * <p>
 * That is why the test below ticks <b>between</b> firing the events and releasing the
 * list. Without that tick the events are still in the buffer when the rows land, and the
 * tick's supervisor-then-flush order applies them on top by itself — the benign
 * interleaving, which is most of them, and which passes on a broken build.
 *
 * <h2>Both directions, in one run</h2>
 *
 * The same run drives GH#417's half as well: while the new subscription's events are
 * being held, the <em>superseded</em> one speaks, and what it says must still be refused.
 * The two rules are one sentence — a subscription's events are kept only from the moment
 * it is opened, and applied only once its own list has landed — and a fix for either that
 * broke the other would pass a test that only drove one.
 */
class ScreenRecoveryEventTest {

	private final MovableClock clock = MovableClock.at("2026-08-13T12:00:00Z");

	@Test
	void anEventNewerThanTheReListSurvivesTheReplacementThatInstallsIt() throws Exception {
		FakeCluster cluster = new FakeCluster()
			.setObjects(List.of(FakeCluster.object("alpha"), FakeCluster.object("beta"), FakeCluster.object("ghost")));
		try (ScreenHarness harness = ScreenHarness.start(cluster, 500, this.clock)) {
			WatchSupervisor supervisor = harness.session().supervisor();
			// The control: the table is live and the watch is demonstrably delivering.
			cluster.fire("MODIFIED", FakeCluster.object("alpha"));
			harness.tickAndSettle();
			assertThat(supervisor.live()).isTrue();
			assertThat(harness.session().model().rows()).extracting(ResourceRow::name)
				.containsExactly("alpha", "beta", "ghost");

			// The cluster moved on while nobody was watching: ghost is gone. Park the
			// re-list, so the screen sits inside the recovery with the replacement watch
			// already open and the rows not yet installed.
			cluster.setObjects(List.of(FakeCluster.object("alpha"), FakeCluster.object("beta")));
			cluster.holdNextList();
			cluster.killWatch(false);
			harness.tickUntil(cluster::listHeld, "the reconnect to reach its re-list");
			assertThat(cluster.watchesOpened()).as("the replacement watch is already open").isEqualTo(2);

			// The replacement watch delivers two things the held list cannot know: beta
			// died after the list was taken, and delta was born after it. The superseded
			// watch speaks in the same breath, and must not be heard.
			cluster.fireFromWatch(1, "DELETED", FakeCluster.object("beta"));
			cluster.fireFromWatch(1, "ADDED", FakeCluster.object("delta"));
			cluster.fireFromWatch(0, "MODIFIED", FakeCluster.object("ghost"));

			// The tick that used to lose them: it drains the buffer onto rows that are
			// about to be replaced. Held, they survive it — and the table does not move
			// while the header says it is not live, which is the honest posture anyway.
			harness.tickAndSettle();
			assertThat(harness.session().coalescer().held())
				.as("held behind their own list, not flushed onto stale rows")
				.isEqualTo(2);
			assertThat(harness.session().model().rows()).extracting(ResourceRow::name)
				.containsExactly("alpha", "beta", "ghost");

			// Let the re-list finish and install it on the very next tick.
			cluster.releaseList();
			harness.await(supervisor::recoveryPending, "the replacement rows to be ready to install");
			harness.tickAndSettle();

			assertThat(supervisor.recoveries()).isEqualTo(1);
			assertThat(supervisor.live()).isTrue();
			assertThat(harness.session().model().rows())
				.as("the new watch's delete and add outlived the list they arrived ahead of, "
						+ "and the dead watch still could not put ghost back")
				.extracting(ResourceRow::name)
				.containsExactly("alpha", "delta");
			assertThat(harness.session().coalescer().stale()).as("GH#417's half, counted rather than assumed")
				.isEqualTo(1);
			assertThat(harness.session().coalescer().held()).as("and the hold was released, not merely emptied")
				.isZero();

			// The control at the other end: the replacement watch is still the one being
			// heard once the recovery is over, so nothing above turned it off.
			cluster.fire("ADDED", FakeCluster.object("after-the-reconnect"));
			harness.tickAndSettle();
			assertThat(harness.session().model().rows()).extracting(ResourceRow::name)
				.containsExactly("after-the-reconnect", "alpha", "delta");
		}
	}

}

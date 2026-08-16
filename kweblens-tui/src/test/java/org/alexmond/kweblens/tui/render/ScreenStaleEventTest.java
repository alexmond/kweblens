package org.alexmond.kweblens.tui.render;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.tui.screen.MovableClock;
import org.alexmond.kweblens.tui.screen.ResourceRow;
import org.alexmond.kweblens.tui.screen.WatchSupervisor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The gate for GH#417.</b> An event minted by the watch that died cannot be applied on
 * top of the list that replaced it.
 *
 * <h2>The ordering, driven rather than hoped for</h2>
 *
 * The window is one tick wide and it is an <em>ordering</em>: a recovery is re-subscribe,
 * re-list, {@code replaceAll}, and the buffer is flushed after the rows are installed —
 * deliberately, so that what the new watch caught while it was re-listing lands on top
 * rather than under. An event from the <em>old</em> watch sitting in that same buffer is
 * therefore applied over a row the fresh list had just corrected. A test that fires the
 * event and hopes proves nothing, because most interleavings are the benign one: the tick
 * that notices the loss flushes the buffer before the reconnect has even started.
 *
 * <p>
 * So this test pins every step. {@link FakeCluster#holdNextList()} parks the reconnect
 * between its re-subscribe and its re-list, which is the window itself;
 * {@link FakeCluster#fireFromWatch} speaks through the dead subscription's own sink,
 * which is the same consumer object fabric8 would call; and
 * {@link WatchSupervisor#recoveryPending()} says the replacement rows are ready, so the
 * one tick that follows is the one that installs them. No sleeps, and no interleaving
 * left to the scheduler.
 *
 * <h2>What it contradicts, and why that row</h2>
 *
 * The stale event puts back a row the re-list says is gone. That is not a colourful
 * choice: "the row deleted while the screen was blind" is exactly the case
 * {@code replaceAll} exists for (#413), so an event allowed over the top of the
 * replacement re-opens the hole the replacement was there to close.
 */
class ScreenStaleEventTest {

	private final MovableClock clock = MovableClock.at("2026-08-13T12:00:00Z");

	@Test
	void anEventFromTheWatchThatDiedCannotUndoTheListThatReplacedIt() throws Exception {
		FakeCluster cluster = new FakeCluster()
			.setObjects(List.of(FakeCluster.object("alpha"), FakeCluster.object("ghost")));
		try (ScreenHarness harness = ScreenHarness.start(cluster, 500, this.clock)) {
			WatchSupervisor supervisor = harness.session().supervisor();
			// The control: the table is live, and the watch is demonstrably delivering.
			cluster.fire("MODIFIED", FakeCluster.object("alpha"));
			harness.tickAndSettle();
			assertThat(supervisor.live()).isTrue();
			assertThat(harness.session().model().rows()).extracting(ResourceRow::name)
				.containsExactly("alpha", "ghost");

			// The cluster moves on while nobody is watching: ghost is deleted. Hold the
			// re-list so the screen is parked inside the recovery, watch re-opened, rows
			// not yet installed.
			cluster.setObjects(List.of(FakeCluster.object("alpha")));
			cluster.holdNextList();
			cluster.killWatch(false);
			harness.tickUntil(cluster::listHeld, "the reconnect to reach its re-list");
			assertThat(supervisor.live()).as("still down until the rows land").isFalse();
			assertThat(cluster.watchesOpened()).as("the replacement watch is already open").isEqualTo(2);

			// The dead watch speaks, after its ending and after its close. Nothing about
			// the event says it is old; only the subscription it came from does.
			cluster.fireFromWatch(0, "MODIFIED", FakeCluster.object("ghost"));

			// Let the re-list finish, then install it on the very next tick — the tick
			// that also flushes the buffer, in that order.
			cluster.releaseList();
			harness.await(supervisor::recoveryPending, "the replacement rows to be ready to install");
			harness.tickAndSettle();

			assertThat(supervisor.recoveries()).isEqualTo(1);
			assertThat(supervisor.live()).isTrue();
			assertThat(harness.session().model().rows()).as("the dead watch cannot put back what the re-list removed")
				.extracting(ResourceRow::name)
				.containsExactly("alpha");
			assertThat(harness.session().coalescer().stale()).as("and the refusal is counted, not assumed")
				.isEqualTo(1);

			// The control at the other end: the replacement watch is the one being heard.
			cluster.fire("ADDED", FakeCluster.object("after-the-reconnect"));
			harness.tickAndSettle();
			assertThat(harness.session().model().rows()).extracting(ResourceRow::name)
				.containsExactly("after-the-reconnect", "alpha");
		}
	}

	@Test
	void aLiveScreenRefusesNothingAtAll() throws Exception {
		FakeCluster cluster = new FakeCluster().withObjects(2);
		try (ScreenHarness harness = ScreenHarness.start(cluster, 500, this.clock)) {
			for (int i = 0; i < 10; i++) {
				cluster.fire("MODIFIED", FakeCluster.object("obj-0000" + i % 2));
				harness.tickAndSettle();
			}

			// The control for the guard itself. A generation check that refused the only
			// watch there is would look exactly like a working one on a screen nobody
			// changes, and the table would quietly stop moving.
			assertThat(harness.session().coalescer().stale()).isZero();
			assertThat(harness.session().coalescer().received()).isEqualTo(10);
			assertThat(harness.session().model().size()).isEqualTo(2);
		}
	}

}

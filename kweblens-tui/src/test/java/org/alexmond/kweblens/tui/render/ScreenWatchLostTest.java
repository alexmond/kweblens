package org.alexmond.kweblens.tui.render;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.tui.screen.MovableClock;
import org.alexmond.kweblens.tui.screen.ResourceRow;
import org.alexmond.kweblens.tui.screen.WatchSupervisor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The gate for GH#413.</b> A watch that dies is visible, in the header, on the running
 * screen — and a watch that is alive is not.
 *
 * <p>
 * These run the same real TamboUI loop against {@link FakeBackend} that
 * {@link ScreenLoopTest} does, because the claim being tested is about what the screen
 * says on a tick, and a hand-rolled loop would let the answer be whatever the test
 * wanted. The clock is {@link MovableClock} so "stopped updating 0s ago" and the retry
 * backoff are assertions rather than sleeps.
 *
 * <p>
 * <b>Every case carries its control.</b> The failure this defends against is not "the
 * notice never appears"; it is a notice that appears whether or not anything is wrong,
 * which is indistinguishable from no notice at all after a day of looking at it. So the
 * live case is asserted silent, with a watch event landing to prove the watch was really
 * open, before anything is killed.
 */
class ScreenWatchLostTest {

	private final MovableClock clock = MovableClock.at("2026-08-13T12:00:00Z");

	@Test
	void aLiveTableIsSilentAndTheSameTableSaysSoWhenItsWatchDies() throws Exception {
		FakeCluster cluster = new FakeCluster().withObjects(3);
		try (ScreenHarness harness = ScreenHarness.start(cluster, 500, this.clock)) {
			// The control, and it is two claims: the header is clean, AND the watch is
			// demonstrably delivering. "No notice" from a watch that never opened would
			// prove nothing.
			cluster.fire("ADDED", FakeCluster.object("proof-of-life"));
			harness.tickAndSettle();
			assertThat(harness.session().model().size()).isEqualTo(4);
			assertThat(harness.session().supervisor().live()).isTrue();
			assertThat(harness.screen().header()).doesNotContain("NOT LIVE").contains("4 rows");

			// Now kill it, and refuse the reconnect so the notice stays up to be read.
			cluster.refuseWatch(new IllegalStateException("connection refused"));
			cluster.killWatch(false);
			harness.tickUntil(() -> !harness.session().supervisor().live(), "the loss to reach the header");

			assertThat(harness.screen().header()).startsWith("NOT LIVE · watch failed: 410: too old resource version")
				.contains("stopped updating 0s ago");
			// The row count is still there and is still 4 — which is exactly why the
			// notice
			// has to lead the line rather than trail it.
			assertThat(harness.screen().header()).contains("4 rows");
		}
	}

	@Test
	void aStreamThatEndsCleanlyIsNotLiveEitherAndSaysWhichHappened() throws Exception {
		FakeCluster cluster = new FakeCluster().withObjects(2);
		try (ScreenHarness harness = ScreenHarness.start(cluster, 500, this.clock)) {
			cluster.refuseWatch(new IllegalStateException("connection refused"));
			cluster.killWatch(true);
			harness.tickUntil(() -> !harness.session().supervisor().live(), "the clean end to reach the header");

			// Same posture, different sentence. Nothing is arriving either way, so the
			// screen must not claim to be live; but "the stream ended" and a 410 send an
			// operator to look at different things, and that is the whole content of the
			// signal.
			assertThat(harness.screen().header()).startsWith("NOT LIVE · watch ended: the stream ended")
				.doesNotContain("watch failed");
		}
	}

	@Test
	void theScreenReSubscribesExactlyOnceAndReListsWhatChangedWhileItWasBlind() throws Exception {
		FakeCluster cluster = new FakeCluster().withObjects(2);
		try (ScreenHarness harness = ScreenHarness.start(cluster, 500, this.clock)) {
			assertThat(harness.session().model().size()).isEqualTo(2);
			assertThat(cluster.watchesOpened()).isEqualTo(1);
			int listsBefore = cluster.lists();

			// The cluster moves on while nobody is watching: one object survives, one is
			// gone, one is new.
			cluster.setObjects(List.of(FakeCluster.object("obj-00000"), FakeCluster.object("arrived-while-blind")));
			cluster.killWatch(false);
			harness.tickUntil(() -> harness.session().supervisor().recoveries() == 1, "the reconnect to land");

			assertThat(cluster.watchesOpened()).as("exactly one new watch, not one per tick").isEqualTo(2);
			assertThat(cluster.watchesClosed()).as("and the dead one was released").isEqualTo(1);
			assertThat(cluster.lists()).as("a re-list, because the TUI tracks no resourceVersion")
				.isEqualTo(listsBefore + 1);

			// The row that vanished while the screen was blind is gone. An upsert-only
			// recovery would have left it on screen forever, which is the same lie in a
			// smaller place.
			assertThat(harness.session().model().rows()).extracting(ResourceRow::name)
				.containsExactly("arrived-while-blind", "obj-00000");
			assertThat(harness.screen().header()).doesNotContain("NOT LIVE");

			// And the replacement watch really is delivering.
			cluster.fire("ADDED", FakeCluster.object("after-the-reconnect"));
			harness.tickAndSettle();
			assertThat(harness.session().model().size()).isEqualTo(3);
		}
	}

	@Test
	void aClusterThatKeepsRefusingLeavesOneWatchOpenAndNotAStackOfThem() throws Exception {
		FakeCluster cluster = new FakeCluster().withObjects(1);
		try (ScreenHarness harness = ScreenHarness.start(cluster, 500, this.clock)) {
			WatchSupervisor supervisor = harness.session().supervisor();
			cluster.refuseWatch(new IllegalStateException("connect timed out"));
			cluster.killWatch(false);

			for (int round = 1; round <= 4; round++) {
				int target = round;
				harness.tickUntil(() -> supervisor.failures() >= target, "refusal " + target);
				// Past the backoff, so the next attempt is due. Ticking alone would never
				// get there — and that is itself the point: the retry gap is time, not
				// ticks, so a busy screen does not hammer a cluster that is down.
				this.clock.advance(Duration.ofSeconds(60));
			}

			assertThat(supervisor.attempts()).as("it kept trying").isGreaterThanOrEqualTo(4);
			assertThat(cluster.watchesOpened()).as("and never opened one it could not close").isEqualTo(1);
			assertThat(cluster.watchesClosed()).isEqualTo(1);
			assertThat(harness.screen().header()).contains("NOT LIVE")
				.contains("reconnect failed: connect timed out")
				.contains("retrying in ");
		}
	}

}

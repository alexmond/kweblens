package org.alexmond.kweblens.tui.render;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import dev.tamboui.tui.event.TickEvent;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.resource.WellKnownKinds;
import org.alexmond.kweblens.tui.data.ResourceQuery;
import org.alexmond.kweblens.tui.screen.ResourceRow;
import org.alexmond.kweblens.tui.screen.RowBatch;
import org.alexmond.kweblens.tui.screen.TickRate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The gate for GH#423.</b> {@code ticksHandled} counts ticks that have
 * <em>finished</em>.
 *
 * <h2>Why a counter's timing is worth a test of its own</h2>
 *
 * {@code ScreenHarness.tickAndSettle} uses this counter as a barrier, and every timing
 * assertion in {@link ScreenWatchLostTest} is made just after one. While the counter was
 * bumped on <em>entry</em> to the handler, that barrier released the test while the tick
 * was still inside {@code WatchSupervisor.tick()} — so
 * {@code aClusterThatKeepsRefusingLeavesOneWatchOpenAndNotAStackOfThem} could advance
 * {@code MovableClock} by sixty seconds into a live read of it, and the supervisor would
 * start a retry the test had already decided was not due. The header then said
 * {@code reconnecting (attempt 5)} where the test expected the last refusal. Green here,
 * red on CI, for three commits.
 *
 * <p>
 * The test below does not reproduce that schedule — it pins the property the schedule
 * depended on, which is the only part that can be asserted without a race: <b>a tick that
 * is still working has not been counted.</b> The tick is held open by parking the
 * projection, because that is inside the flush and therefore inside the handler; no sleep
 * and no retry is involved, only two latches.
 */
class ScreenTickBarrierTest {

	private static final ResourceQuery QUERY = new ResourceQuery("fake", WellKnownKinds.PODS, "ns");

	@Test
	void aTickIsCountedWhenItFinishesAndNotWhenItStarts() throws Exception {
		CountDownLatch working = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		RowBatch parked = (objects) -> {
			working.countDown();
			await(release);
			return List.of(new ResourceRow("ns/a", "ns", "a", "Running", "1d"));
		};
		FakeCluster cluster = new FakeCluster();
		try (ScreenSession session = new ScreenSession(cluster, QUERY, TickRate.defaults(), Clock.systemUTC(),
				(query) -> parked)) {
			session.subscribe();
			// Subscribe and load are one operation (GH#420): a subscription's events are
			// held until its own list is in the model, so a subscribe on its own leaves a
			// buffer that never flushes and a tick with nothing to do.
			session.load(500);
			cluster.fire("ADDED", FakeCluster.object("a"));

			Thread tick = new Thread(() -> session.screen().handle(TickEvent.of(1, Duration.ofMillis(100)), null),
					"tick-barrier");
			tick.setDaemon(true);
			tick.start();
			assertThat(working.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

			assertThat(session.screen().ticksHandled())
				.as("the tick is inside the flush right now, so it must not claim to be done")
				.isZero();

			release.countDown();
			tick.join(Duration.ofSeconds(5).toMillis());

			assertThat(session.screen().ticksHandled()).isEqualTo(1);
			assertThat(session.model().size()).isEqualTo(1);
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
				throw new IllegalStateException("the parked projection was never released");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}

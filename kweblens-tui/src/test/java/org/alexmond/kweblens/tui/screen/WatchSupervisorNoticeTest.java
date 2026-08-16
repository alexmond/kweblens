package org.alexmond.kweblens.tui.screen;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.tui.data.WatchEnd;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the header says <b>while</b> a reconnect is running — the other half of GH#423.
 *
 * <p>
 * The notice used to drop the reason the last attempt failed for the duration of the next
 * one, leaving {@code reconnecting (attempt 5)} and nothing else. Two things were wrong
 * with that. It loses the only fact that distinguishes a cluster that is slow from a
 * credential that is refused, and those send an operator to different places. And it
 * makes the sentence appear and disappear on a cadence nobody chose, so "is the failure
 * visible" had no stable answer — which is what a timing-sensitive assertion in
 * {@code ScreenWatchLostTest} was quietly depending on.
 *
 * <h2>Every tick here is dispatched on purpose (GH#427)</h2>
 *
 * The first version of this test reached the first refusal by ticking up to 500 times and
 * calling the test failed if it had not arrived — which made a pass depend on the runner
 * being fast enough, the very defect #423 was fixed for. There is no need to search for
 * the moment: the two things a tick does are separable and each has a state that says it
 * happened. The tick that adopts the loss <b>also starts the first attempt</b> (the first
 * retry is due immediately, by design), the recovery thread stamps its refusal where
 * {@link WatchSupervisor#recoveryPending()} can see it, and exactly one further tick
 * installs it. So this test dispatches <b>two</b> ticks to reach the first refusal, never
 * a variable number, and the only wait is {@link Eventually} on the stamped state.
 */
class WatchSupervisorNoticeTest {

	private final MovableClock clock = MovableClock.at("2026-08-13T12:00:00Z");

	private final ResourceModel model = new ResourceModel();

	/**
	 * As {@code ScreenSession} installs a recovery, minus the coalescer half (GH#420).
	 */
	private final RecoveryInstall install = (generation, rows) -> this.model.replaceAll(rows);

	@Test
	void aReconnectInFlightStillSaysWhyTheLastOneFailed() throws Exception {
		AtomicInteger attempts = new AtomicInteger();
		CountDownLatch running = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (WatchSupervisor supervisor = new WatchSupervisor(this.clock, this.install, (lease) -> {
			if (attempts.incrementAndGet() > 1) {
				// The second attempt is held open, so the header can be read at a moment
				// that used to say nothing about the failure at all.
				running.countDown();
				awaitLatch(release);
			}
			throw new IllegalStateException("connect timed out");
		})) {
			supervisor.listener().accept(WatchEnd.failed(new IllegalStateException("410: too old resource version")));

			// One tick adopts the loss and starts attempt 1 — the first retry is due the
			// moment the loss is noticed, so both happen inside this call.
			assertThat(supervisor.tick()).as("the tick that notices the loss owes a repaint").isTrue();
			assertThat(supervisor.attempts()).isEqualTo(1);
			Eventually.await(supervisor::recoveryPending, "the first attempt to be refused by the cluster");

			// And one more installs the refusal the recovery thread stamped.
			assertThat(supervisor.tick()).as("the tick that installs the refusal owes a repaint").isTrue();
			assertThat(supervisor.failures()).isEqualTo(1);
			assertThat(supervisor.notice()).contains("reconnect failed: connect timed out").contains("retrying in");

			this.clock.advance(Duration.ofSeconds(2));
			supervisor.tick();
			assertThat(running.await(5, TimeUnit.SECONDS)).as("the second attempt is in flight").isTrue();

			assertThat(supervisor.notice()).as("mid-attempt the operator is still told what went wrong last time")
				.contains("NOT LIVE")
				.contains("reconnect failed: connect timed out")
				.contains("reconnecting (attempt 2)");
			release.countDown();
		}
	}

	private static void awaitLatch(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("the held reconnect was never released");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}

package org.alexmond.kweblens.tui.screen;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 */
class WatchSupervisorNoticeTest {

	private final MovableClock clock = MovableClock.at("2026-08-13T12:00:00Z");

	private final ResourceModel model = new ResourceModel();

	@Test
	void aReconnectInFlightStillSaysWhyTheLastOneFailed() throws Exception {
		CountDownLatch running = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch first = new CountDownLatch(1);
		try (WatchSupervisor supervisor = new WatchSupervisor(this.clock, this.model, (lease) -> {
			if (first.getCount() > 0) {
				first.countDown();
				throw new IllegalStateException("connect timed out");
			}
			running.countDown();
			awaitLatch(release);
			throw new IllegalStateException("connect timed out");
		})) {
			supervisor.listener().accept(WatchEnd.failed(new IllegalStateException("410: too old resource version")));
			tickUntil(supervisor, () -> supervisor.failures() >= 1, "the first refusal");
			assertThat(supervisor.notice()).contains("reconnect failed: connect timed out").contains("retrying in");

			// Second attempt, held open, so the header is read at a moment that used to
			// say nothing about the failure at all.
			this.clock.advance(Duration.ofSeconds(2));
			supervisor.tick();
			assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

			assertThat(supervisor.notice()).as("mid-attempt the operator is still told what went wrong last time")
				.contains("NOT LIVE")
				.contains("reconnect failed: connect timed out")
				.contains("reconnecting (attempt 2)");
			release.countDown();
		}
	}

	private static void tickUntil(WatchSupervisor supervisor, java.util.function.BooleanSupplier condition,
			String what) {
		for (int i = 0; i < 500; i++) {
			if (condition.getAsBoolean()) {
				return;
			}
			supervisor.tick();
		}
		throw new AssertionError("ticked 500 times waiting for " + what);
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

package org.alexmond.kweblens.tui.screen;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.tui.data.WatchEnd;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The gate for GH#413's first half.</b> A watch that dies says so; a watch that is
 * alive says nothing; and the reconnect that follows happens once per loss rather than
 * once per tick.
 *
 * <p>
 * Every test here carries its own control. "The notice appeared" proves nothing on its
 * own — a notice that is always on is worse than no notice, because it trains the
 * operator to ignore the one place the screen admits it is stale — so the live case is
 * asserted silent in the same harness that then makes it loud.
 */
class WatchSupervisorTest {

	private final MovableClock clock = MovableClock.at("2026-08-13T12:00:00Z");

	private final ResourceModel model = new ResourceModel();

	private static ResourceRow row(String name) {
		return new ResourceRow("ns/" + name, "ns", name, "Running", "1d");
	}

	/** Tick until {@code condition} holds — the reconnect finishes on its own thread. */
	private void tickUntil(WatchSupervisor supervisor, BooleanSupplier condition, String what) {
		Eventually.await(supervisor::tick, condition, what);
	}

	@Test
	void aLiveWatchSaysNothingAndCostsTheTickNothing() {
		try (WatchSupervisor supervisor = new WatchSupervisor(this.clock, this.model, (onEnd) -> List.of())) {
			supervisor.listener();

			// The control. Ticks pass, time passes, and the header stays clean — so a
			// notice that shows up later is a signal and not this class's resting state.
			for (int i = 0; i < 10; i++) {
				assertThat(supervisor.tick()).isFalse();
				this.clock.advance(Duration.ofSeconds(1));
			}
			assertThat(supervisor.notice()).isEmpty();
			assertThat(supervisor.live()).isTrue();
			assertThat(supervisor.attempts()).isZero();
		}
	}

	@Test
	void aWatchThatFailsIsVisibleOnTheVeryNextTickAndNamesTheReason() {
		AtomicReference<Consumer<WatchEnd>> ends = new AtomicReference<>();
		try (WatchSupervisor supervisor = new WatchSupervisor(this.clock, this.model, (onEnd) -> {
			throw new IllegalStateException("cluster unreachable");
		})) {
			ends.set(supervisor.listener());
			assertThat(supervisor.notice()).isEmpty();

			ends.get().accept(WatchEnd.failed(new IllegalStateException("410: too old resource version")));

			assertThat(supervisor.tick()).as("the tick that notices owes a repaint").isTrue();
			assertThat(supervisor.live()).isFalse();
			assertThat(supervisor.notice()).startsWith("NOT LIVE · watch failed: 410: too old resource version")
				.contains("stopped updating 0s ago");
		}
	}

	@Test
	void aStreamThatEndedCleanlyIsStillNotLiveAndSaysSoInItsOwnWords() {
		try (WatchSupervisor supervisor = new WatchSupervisor(this.clock, this.model, (onEnd) -> List.of())) {
			Consumer<WatchEnd> ends = supervisor.listener();

			ends.accept(WatchEnd.completed());
			supervisor.tick();

			// Same posture — nothing is arriving either way — and a different sentence,
			// because "it ended" and "it broke" send an operator to different places.
			assertThat(supervisor.live()).isFalse();
			assertThat(supervisor.notice()).contains("NOT LIVE").contains("watch ended: the stream ended");
		}
	}

	@Test
	void theElapsedTimeMovesOnceASecondAndNotOncePerTick() {
		try (WatchSupervisor supervisor = new WatchSupervisor(this.clock, this.model, (onEnd) -> {
			throw new IllegalStateException("still down");
		})) {
			supervisor.listener().accept(WatchEnd.failed(new IllegalStateException("gone")));
			tickUntil(supervisor, () -> supervisor.failures() >= 1, "the first attempt to be refused");

			assertThat(supervisor.tick()).as("a tick with nothing new owes no repaint").isFalse();
			this.clock.advance(Duration.ofMillis(400));
			assertThat(supervisor.tick()).as("still the same whole second").isFalse();
			this.clock.advance(Duration.ofMillis(700));
			assertThat(supervisor.tick()).as("a new whole second: the header text changed").isTrue();
			assertThat(supervisor.notice()).contains("stopped updating 1s ago");
		}
	}

	@Test
	void oneLossIsOneReconnectAndItReplacesTheRowsRatherThanAddingToThem() {
		this.model.replaceAll(List.of(row("a"), row("deleted-while-blind")));
		AtomicInteger reconnects = new AtomicInteger();
		try (WatchSupervisor supervisor = new WatchSupervisor(this.clock, this.model, (onEnd) -> {
			reconnects.incrementAndGet();
			return List.of(row("a"), row("b"));
		})) {
			Consumer<WatchEnd> ends = supervisor.listener();

			ends.accept(WatchEnd.failed(new IllegalStateException("gone")));
			tickUntil(supervisor, () -> supervisor.recoveries() == 1, "the reconnect to land");

			assertThat(reconnects).hasValue(1);
			assertThat(supervisor.live()).isTrue();
			assertThat(supervisor.notice()).isEmpty();
			// A re-list is a REPLACEMENT: the row deleted while the screen was blind is
			// the
			// only thing an upsert-only recovery would have left on screen forever.
			assertThat(this.model.rows()).extracting(ResourceRow::name).containsExactly("a", "b");

			// And the dead handle reporting again — fabric8 can, and the old generation
			// is
			// stale — does not start a second reconnect.
			ends.accept(WatchEnd.failed(new IllegalStateException("gone, again")));
			for (int i = 0; i < 20; i++) {
				supervisor.tick();
			}
			assertThat(supervisor.live()).isTrue();
			assertThat(reconnects).hasValue(1);
		}
	}

	@Test
	void aClusterThatKeepsRefusingIsRetriedOnABackoffAndNeverTwiceAtOnce() {
		AtomicInteger inFlight = new AtomicInteger();
		AtomicInteger overlaps = new AtomicInteger();
		try (WatchSupervisor supervisor = new WatchSupervisor(this.clock, this.model, (onEnd) -> {
			if (inFlight.incrementAndGet() > 1) {
				overlaps.incrementAndGet();
			}
			inFlight.decrementAndGet();
			throw new IllegalStateException("connect timed out");
		})) {
			supervisor.listener().accept(WatchEnd.failed(new IllegalStateException("gone")));

			tickUntil(supervisor, () -> supervisor.failures() >= 1, "the first refusal");
			assertThat(supervisor.attempts()).isEqualTo(1);
			assertThat(supervisor.notice()).contains("reconnect failed: connect timed out").contains("retrying in 1s");

			// Ticks alone do not retry: the gap is time, so a busy screen does not hammer
			// a
			// cluster that is down.
			for (int i = 0; i < 20; i++) {
				supervisor.tick();
			}
			assertThat(supervisor.attempts()).isEqualTo(1);

			this.clock.advance(Duration.ofSeconds(1));
			tickUntil(supervisor, () -> supervisor.failures() >= 2, "the second refusal");
			assertThat(supervisor.attempts()).isEqualTo(2);
			assertThat(supervisor.notice()).contains("retrying in 2s");

			assertThat(overlaps).as("exactly one reconnect is ever in flight").hasValue(0);
		}
	}

}

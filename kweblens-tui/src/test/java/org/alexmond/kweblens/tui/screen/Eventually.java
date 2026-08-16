package org.alexmond.kweblens.tui.screen;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * The one way a test in this module waits for something another thread has to do.
 *
 * <h2>Why there is only one</h2>
 *
 * Twice in two days {@code main} went red on CI and green here, and both times the test
 * was waiting on a background thread by <b>counting iterations</b> — 500 supervisor ticks
 * for the first refusal (GH#427), 200 harness ticks for a held re-list. An iteration
 * count is a timeout with no unit: 500 no-op ticks is tens of microseconds of wall clock,
 * so the bound says nothing about how long the reconnect thread was actually given, and
 * the same code passes or fails on how busy the runner is. Raising the bound moves the
 * failure, it does not remove it.
 *
 * <p>
 * So the bound here is <b>time</b>, and the only thing that ends the wait early is the
 * condition itself. {@link #LIMIT} is long enough that only a thread that is stuck — not
 * one that is merely late — can reach it, and the failure names what was being waited for
 * and how many passes it took, so a real hang is diagnosable rather than just red.
 *
 * <h2>Nudging, and why the wait yields</h2>
 *
 * Some states only advance when the render thread ticks, so a wait may carry a
 * {@code nudge} it runs each pass; others — a stamped {@code AtomicReference}, a latch a
 * worker counts down — need nothing but patience, and those pass no nudge at all. Either
 * way the loop spins briefly and then <b>parks</b>: a test thread that only spins is
 * competing for the core with the very thread it is waiting for, which is exactly the
 * shape of runner-dependence this class exists to remove.
 */
public final class Eventually {

	/** How long a wait may take before the test is called failed. */
	public static final Duration LIMIT = Duration.ofSeconds(10);

	/** Passes spent hot before parking — enough that the common case never sleeps. */
	private static final int SPIN_PASSES = 100;

	private static final long PARK_NANOS = 100_000L;

	private Eventually() {
	}

	/**
	 * Wait for a state some other thread produces.
	 * @param condition the state being waited for
	 * @param what it in words, for the failure message
	 */
	public static void await(BooleanSupplier condition, String what) {
		await(() -> {
		}, condition, what);
	}

	/**
	 * Wait for a state that only advances when {@code nudge} runs — a tick, usually.
	 * @param nudge run once per pass, before the next look at {@code condition}
	 * @param condition the state being waited for
	 * @param what it in words, for the failure message
	 */
	public static void await(Runnable nudge, BooleanSupplier condition, String what) {
		long deadline = System.nanoTime() + LIMIT.toNanos();
		long passes = 0;
		while (true) {
			if (condition.getAsBoolean()) {
				return;
			}
			if (System.nanoTime() - deadline >= 0) {
				throw new AssertionError("waited " + LIMIT.toSeconds() + "s over " + passes + " passes for " + what
						+ ", and it never happened");
			}
			nudge.run();
			passes++;
			if (passes < SPIN_PASSES) {
				Thread.onSpinWait();
			}
			else {
				LockSupport.parkNanos(PARK_NANOS);
			}
		}
	}

}

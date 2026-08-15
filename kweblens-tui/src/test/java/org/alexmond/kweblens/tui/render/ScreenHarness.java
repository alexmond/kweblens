package org.alexmond.kweblens.tui.render;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.TickEvent;

import org.alexmond.kweblens.resource.WellKnownKinds;
import org.alexmond.kweblens.tui.data.ResourceQuery;
import org.alexmond.kweblens.tui.screen.TickRate;

/**
 * The real TamboUI loop, running against {@link FakeBackend}, with ticks under the test's
 * control.
 *
 * <p>
 * Ticks are dispatched by hand ({@code noTick()}) so that "one repaint per tick" is an
 * assertion rather than a race against a scheduler. Everything else is the shipped path:
 * the same {@code TuiRunner.run}, the same input reader thread, the same
 * {@code pollEvent} priority rule, the same {@link ScreenSession}.
 */
public final class ScreenHarness implements AutoCloseable {

	/** Not 80×24: see {@link FakeBackend}. */
	public static final int WIDTH = 132;

	public static final int HEIGHT = 44;

	/** How many ticks {@link #tickUntil} will spend before it calls the test failed. */
	private static final int TICK_LIMIT = 200;

	private final AtomicLong frame = new AtomicLong();

	private final FakeBackend backend;

	private final ScreenSession session;

	private final TuiRunner runner;

	private final Thread loop;

	private ScreenHarness(FakeBackend backend, ScreenSession session, TuiRunner runner) {
		this.backend = backend;
		this.session = session;
		this.runner = runner;
		this.loop = new Thread(this::pump, "screen-harness-loop");
		this.loop.setDaemon(true);
	}

	/**
	 * Start a screen over {@code cluster}, loaded and drawn once before returning, so a
	 * test's first assertion is about a steady state.
	 */
	public static ScreenHarness start(FakeCluster cluster, int chunkSize) throws Exception {
		return start(cluster, chunkSize, Clock.systemUTC());
	}

	/**
	 * The same screen on a clock the test moves by hand, which is what makes "stopped
	 * updating 12s ago" and a retry backoff assertions rather than sleeps.
	 */
	public static ScreenHarness start(FakeCluster cluster, int chunkSize, Clock clock) throws Exception {
		FakeBackend backend = new FakeBackend(WIDTH, HEIGHT);
		ResourceQuery query = new ResourceQuery("fake", WellKnownKinds.PODS, "ns");
		ScreenSession session = new ScreenSession(cluster, query, TickRate.defaults(), clock);
		TuiConfig config = TuiConfig.builder()
			.backend(backend)
			.noTick()
			.rawMode(false)
			.alternateScreen(false)
			.hideCursor(false)
			.shutdownHook(false)
			.pollTimeout(Duration.ofMillis(5))
			.errorOutput(new java.io.PrintStream(new ByteArrayOutputStream(), true))
			.build();
		TuiRunner runner = TuiRunner.create(config);
		session.subscribe();
		session.load(chunkSize);
		ScreenHarness harness = new ScreenHarness(backend, session, runner);
		harness.loop.start();
		harness.await(() -> harness.screen().renders() >= 1, "the first frame");
		return harness;
	}

	private void pump() {
		try {
			this.runner.run(this.session.screen(), this.session.screen());
		}
		catch (Exception ex) {
			throw new IllegalStateException("the screen loop failed", ex);
		}
	}

	/** Post one tick, as TamboUI's scheduler would. */
	public void tick() {
		this.runner.dispatch(TickEvent.of(this.frame.incrementAndGet(), Duration.ofMillis(100)));
	}

	/**
	 * Post one tick and return once its repaint (if it owed one) has finished.
	 *
	 * <p>
	 * The barrier is a <b>second tick</b>. The loop is sequential — poll, handle, render
	 * — so the second tick being handled proves the first tick's render completed. It is
	 * also free, and provably so: it finds an empty buffer, returns {@code Flush.IDLE}
	 * and owes no repaint, which is why every assertion below counts exactly one render
	 * per {@code tickAndSettle} rather than two.
	 */
	public void tickAndSettle() {
		int before = this.screen().ticksHandled();
		tick();
		await(() -> this.screen().ticksHandled() > before, "the tick to be handled");
		tick();
		await(() -> this.screen().ticksHandled() > before + 1, "the barrier tick, proving the repaint finished");
	}

	/**
	 * Keep ticking until {@code condition} holds.
	 *
	 * <p>
	 * A reconnect is not finished by the tick that starts it — the network half runs on
	 * the supervisor's own thread — so a test that wants to see a recovery has to let the
	 * loop keep running rather than await on a single tick.
	 */
	public void tickUntil(BooleanSupplier condition, String what) {
		for (int i = 0; i < TICK_LIMIT; i++) {
			if (condition.getAsBoolean()) {
				return;
			}
			tickAndSettle();
		}
		throw new AssertionError("ticked " + TICK_LIMIT + " times waiting for " + what);
	}

	/** Wait for {@code condition}, or fail the test after two seconds. */
	public void await(BooleanSupplier condition, String what) {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.onSpinWait();
		}
		throw new AssertionError("timed out waiting for " + what);
	}

	public FakeBackend backend() {
		return this.backend;
	}

	public ScreenSession session() {
		return this.session;
	}

	public ResourceScreen screen() {
		return this.session.screen();
	}

	public TuiRunner runner() {
		return this.runner;
	}

	public boolean running() {
		return this.loop.isAlive();
	}

	@Override
	public void close() throws Exception {
		this.runner.quit();
		this.loop.join(Duration.ofSeconds(5).toMillis());
		this.session.close();
		this.runner.close();
	}

}

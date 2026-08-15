package org.alexmond.kweblens.tui.render;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.tamboui.tui.TuiConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.resource.WellKnownKinds;
import org.alexmond.kweblens.tui.data.ResourceQuery;
import org.alexmond.kweblens.tui.screen.TickRate;

import static org.assertj.core.api.Assertions.assertThat;

/** Starting the screen and taking it down again: the order, and the releases. */
class TuiScreenTest {

	private static final ResourceQuery QUERY = new ResourceQuery("fake", WellKnownKinds.PODS, "ns");

	@Test
	void withoutATtyItRefusesAndSaysWhereToLook() {
		Assumptions.assumeTrue(System.console() == null,
				"this asserts the no-terminal branch, so it needs a JVM without one");
		StringWriter out = new StringWriter();

		int code = new TuiScreen(new FakeCluster()).run(QUERY, 500, TickRate.defaults(), new PrintWriter(out));

		assertThat(code).isEqualTo(TuiScreen.EXIT_NO_TERMINAL);
		assertThat(out.toString()).contains("Not a terminal").contains("--once");
	}

	@Test
	void theScreenLoadsWatchesRunsAndReleasesTheWatchOnTheWayOut() throws Exception {
		FakeCluster cluster = new FakeCluster().withObjects(10);
		FakeBackend backend = new FakeBackend(ScreenHarness.WIDTH, ScreenHarness.HEIGHT);
		AtomicReference<ScreenSession> session = new AtomicReference<>();
		AtomicInteger code = new AtomicInteger(-1);
		StringWriter out = new StringWriter();

		Thread thread = new Thread(() -> code.set(new TuiScreen(cluster).run(QUERY, 5, TickRate.defaults(),
				config(backend), new PrintWriter(out), session::set)), "tui-screen-test");
		thread.setDaemon(true);
		thread.start();
		await(() -> session.get() != null && session.get().screen().renders() >= 1);

		assertThat(session.get().model().size()).as("five per page, two pages, nothing held between them")
			.isEqualTo(10);
		backend.type('q');
		thread.join(Duration.ofSeconds(5).toMillis());

		assertThat(code).hasValue(0);
		assertThat(cluster.watchClosed()).as("a watch left open holds a connection on the cluster").isTrue();
		assertThat(out.toString()).isEmpty();
	}

	@Test
	void aScreenThatCannotStartReportsItRatherThanThrowing() {
		StringWriter out = new StringWriter();

		// A backend whose size() throws is the cheapest way to make TuiRunner.create
		// fail.
		int code = new TuiScreen(new FakeCluster()).run(QUERY, 500, TickRate.defaults(), config(new BrokenBackend()),
				new PrintWriter(out), (session) -> {
				});

		assertThat(code).isEqualTo(TuiScreen.EXIT_SCREEN_FAILED);
		assertThat(out.toString()).contains("The screen could not run");
	}

	private static void await(java.util.function.BooleanSupplier condition) {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.onSpinWait();
		}
		throw new AssertionError("timed out");
	}

	private static TuiConfig config(dev.tamboui.terminal.Backend backend) {
		return TuiConfig.builder()
			.backend(backend)
			.tickRate(Duration.ofMillis(20))
			.rawMode(false)
			.alternateScreen(false)
			.hideCursor(false)
			.shutdownHook(false)
			.pollTimeout(Duration.ofMillis(5))
			.errorOutput(new PrintStream(new ByteArrayOutputStream(), true))
			.build();
	}

	/** A terminal that cannot say how big it is, i.e. one that cannot be drawn on. */
	private static final class BrokenBackend extends FakeBackend {

		BrokenBackend() {
			super(ScreenHarness.WIDTH, ScreenHarness.HEIGHT);
		}

		@Override
		public dev.tamboui.layout.Size size() {
			throw new IllegalStateException("no terminal here");
		}

	}

}

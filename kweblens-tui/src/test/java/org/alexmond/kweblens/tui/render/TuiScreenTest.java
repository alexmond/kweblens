package org.alexmond.kweblens.tui.render;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.tamboui.tui.TuiConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import org.alexmond.kweblens.resource.WellKnownKinds;
import org.alexmond.kweblens.tui.data.ResourceQuery;
import org.alexmond.kweblens.tui.kind.KindCatalog;
import org.alexmond.kweblens.tui.screen.Eventually;
import org.alexmond.kweblens.tui.screen.TickRate;

import static org.assertj.core.api.Assertions.assertThat;

/** Starting the screen and taking it down again: the order, and the releases. */
class TuiScreenTest {

	private static final ResourceQuery QUERY = new ResourceQuery("fake", WellKnownKinds.PODS, "ns");

	/** What the refusal was printed to, read back as a value. */
	private final StringWriter refused = new StringWriter();

	/**
	 * A screen over {@code cluster}, with the command line's vocabulary discovered from
	 * it.
	 */
	private static TuiScreen screen(FakeCluster cluster) {
		return new TuiScreen(cluster, new KindCatalog(cluster));
	}

	@Test
	void withoutATtyItRefusesAndSaysWhereToLook() {
		Assumptions.assumeTrue(System.console() == null,
				"this asserts the no-terminal branch, so it needs a JVM without one");
		StringWriter out = new StringWriter();

		int code = screen(new FakeCluster()).run(QUERY, 500, TickRate.defaults(), new PrintWriter(out));

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

		Thread thread = new Thread(() -> code.set(screen(cluster).run(QUERY, 5, TickRate.defaults(), config(backend),
				new PrintWriter(out), session::set)), "tui-screen-test");
		thread.setDaemon(true);
		thread.start();
		Eventually.await(() -> session.get() != null && session.get().screen().renders() >= 1,
				"the screen to load and draw its first frame");

		assertThat(session.get().model().size()).as("five per page, two pages, nothing held between them")
			.isEqualTo(10);
		backend.type('q');
		thread.join(Duration.ofSeconds(5).toMillis());

		assertThat(code).hasValue(0);
		assertThat(cluster.watchClosed()).as("a watch left open holds a connection on the cluster").isTrue();
		assertThat(out.toString()).isEmpty();
	}

	/**
	 * The control for GH#442, and it fails by <b>hanging</b> without the check: a screen
	 * with no area draws nothing, returns nothing and says nothing, which is what makes
	 * it indistinguishable from a hang from the outside.
	 */
	@Test
	void aTerminalWithNoRoomToDrawIsRefusedInWordsRatherThanDrawnOnAnyway() {
		FakeBackend backend = new FakeBackend(0, 0);

		int code = refusal(backend);

		assertThat(code).isEqualTo(TuiScreen.EXIT_NO_AREA);
		assertThat(this.refused.toString()).contains("No room to draw")
			.contains("0×0 (columns × rows)")
			.contains("--once");
		assertThat(backend.sizeReads())
			.as("the size named is the one the RENDERER asks the backend for, "
					+ "not $LINES/$COLUMNS — a message from another source could contradict the blank screen")
			.isPositive();
	}

	/**
	 * The discriminator. A check that matched a literal 0×0, or that read the
	 * environment, passes the test above and fails this one: this terminal has columns
	 * and no rows, and that is just as much nowhere to draw.
	 */
	@Test
	void aTerminalWithColumnsButNoRowsIsRefusedWithItsOwnNumbers() {
		int code = refusal(new FakeBackend(132, 0));

		assertThat(code).isEqualTo(TuiScreen.EXIT_NO_AREA);
		assertThat(this.refused.toString()).contains("132×0 (columns × rows)");
	}

	@Test
	void aScreenThatCannotStartReportsItRatherThanThrowing() {
		StringWriter out = new StringWriter();

		// A backend whose size() throws is the cheapest way to make TuiRunner.create
		// fail.
		int code = screen(new FakeCluster()).run(QUERY, 500, TickRate.defaults(), config(new BrokenBackend()),
				new PrintWriter(out), (session) -> {
				});

		assertThat(code).isEqualTo(TuiScreen.EXIT_SCREEN_FAILED);
		assertThat(out.toString()).contains("The screen could not run");
	}

	/**
	 * A degenerate size can also arrive later, and by then the terminal is not available
	 * to print on: the alternate screen is up and has no cells, and
	 * {@code TerminalOutputGuard} owns {@code System.out}. So the notice goes to the log,
	 * and the session is <b>kept</b> — a terminal that stops reporting an area usually
	 * starts again, and the screen redraws itself when it does.
	 */
	@Test
	void aScreenThatLosesItsAreaWhileRunningSaysSoInTheLogAndCarriesOn() throws Exception {
		FakeCluster cluster = new FakeCluster().withObjects(10);
		FakeBackend backend = new FakeBackend(ScreenHarness.WIDTH, ScreenHarness.HEIGHT);
		AtomicReference<ScreenSession> session = new AtomicReference<>();
		AtomicInteger code = new AtomicInteger(-1);
		Logger logger = (Logger) LoggerFactory.getLogger(DrawableAreaWatch.class);
		ListAppender<ILoggingEvent> lines = new ListAppender<>();
		// The render thread logs into this while THIS thread iterates it in said(), and
		// ListAppender's own list is a plain ArrayList — which threw
		// ConcurrentModificationException on CI and passed everywhere else (#453). The
		// other two ListAppenders in the tree are driven from the test thread only.
		lines.list = new CopyOnWriteArrayList<>();
		lines.start();
		logger.addAppender(lines);
		try {
			Thread thread = new Thread(() -> code.set(screen(cluster).run(QUERY, 5, TickRate.defaults(),
					config(backend), new PrintWriter(this.refused), session::set)), "tui-shrink-test");
			thread.setDaemon(true);
			thread.start();
			Eventually.await(() -> session.get() != null && session.get().screen().renders() >= 1, "the first frame");
			assertThat(said(lines)).as("a screen with room to draw says nothing").isEmpty();

			shrink(backend, 0, 0);
			Eventually.await(() -> said(lines).stream().anyMatch((line) -> line.contains("nowhere to draw")),
					"the log to say the screen has nowhere to draw");
			assertThat(thread.isAlive()).as("a size that goes degenerate is not fatal — it usually comes back")
				.isTrue();

			shrink(backend, ScreenHarness.WIDTH, ScreenHarness.HEIGHT);
			Eventually.await(() -> said(lines).stream().anyMatch((line) -> line.contains("again")),
					"the log to say the screen is drawing again");

			backend.type('q');
			thread.join(Duration.ofSeconds(5).toMillis());
			assertThat(code).hasValue(0);
			assertThat(this.refused.toString()).as("nothing was refused, so nothing was printed").isEmpty();
		}
		finally {
			logger.detachAppender(lines);
			lines.stop();
		}
	}

	/**
	 * Resize, then press a key. The key is what makes the next frame certain: a repaint
	 * owed to a cursor move is dispatched by this thread, where a resize-driven one
	 * depends on TamboUI's scheduler.
	 */
	private static void shrink(FakeBackend backend, int width, int height) {
		backend.resizeTo(width, height);
		backend.type('j');
	}

	private static List<String> said(ListAppender<ILoggingEvent> lines) {
		return lines.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	/**
	 * Run the screen on a background thread and wait for it to come back. Without the
	 * GH#442 check it never does — which is the defect, so the wait has to be bounded or
	 * the control would hang instead of failing.
	 */
	private int refusal(FakeBackend backend) {
		AtomicInteger code = new AtomicInteger(-1);
		Thread thread = new Thread(() -> code.set(screen(new FakeCluster().withObjects(3)).run(QUERY, 5,
				TickRate.defaults(), config(backend), new PrintWriter(this.refused), (session) -> {
				})), "tui-no-area-test");
		thread.setDaemon(true);
		thread.start();
		Eventually.await(() -> code.get() != -1,
				"the screen to refuse a terminal with no room to draw and come back (GH#442)");
		return code.get();
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

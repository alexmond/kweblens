package org.alexmond.kweblens.tui.render;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.ResizeEvent;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.tui.screen.ColumnLayout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The gate for #364.</b> A burst of watch events becomes at most one repaint per tick,
 * the keyboard still answers under one, and a resize re-lays-out.
 *
 * <p>
 * This is the terminal's form of {@code useResourceData.test.ts}, which fires <b>157</b>
 * {@code ADDED} events — the real ReplicaSet count that once froze a tab — and asserts
 * {@code updates === 1} after one animation frame and {@code 2} after the next. The
 * numbers below are the same numbers; only the period differs, because a terminal has no
 * animation frame.
 *
 * <p>
 * It runs the <b>real</b> TamboUI loop against {@link FakeBackend}. That is not a
 * convenience: the failure this test exists to catch is a property of
 * {@code TuiRunner.pollEvent}, which deprioritises {@code TickEvent} but treats a
 * {@code UiRunnable} — what {@code runLater} posts — as FIFO with keystrokes. A test
 * against a hand-rolled loop would pass on a design that starves the keyboard.
 * {@link #aRedrawPostedPerEventIsFifoWithKeystrokes()} is the control that proves this
 * harness can tell the two apart.
 */
class ScreenLoopTest {

	private static final int BURST = 157;

	private static final int BIG_BURST = 2_000;

	@Test
	void aBurstOfWatchEventsBecomesOneRepaintPerTick() throws Exception {
		FakeCluster cluster = new FakeCluster();
		try (ScreenHarness harness = ScreenHarness.start(cluster, 500)) {
			int base = harness.screen().renders();

			for (int i = 0; i < BURST; i++) {
				cluster.fire("ADDED", FakeCluster.object("rs-" + i));
			}

			// Buffered. Nothing applied, nothing drawn, no UI work posted anywhere.
			assertThat(harness.session().coalescer().buffered()).isEqualTo(BURST);
			assertThat(harness.session().model().size()).isZero();
			assertThat(harness.screen().renders()).isEqualTo(base);

			// One tick applies the whole burst in a SINGLE repaint.
			harness.tickAndSettle();
			assertThat(harness.session().model().size()).isEqualTo(BURST);
			assertThat(harness.screen().renders()).isEqualTo(base + 1);

			// A later event on a new tick is its own single repaint — still flushing.
			cluster.fire("ADDED", FakeCluster.object("rs-999"));
			harness.tickAndSettle();
			assertThat(harness.session().model().size()).isEqualTo(BURST + 1);
			assertThat(harness.screen().renders()).isEqualTo(base + 2);

			// And a tick with nothing buffered costs a map check, not a frame.
			harness.tickAndSettle();
			assertThat(harness.screen().renders()).isEqualTo(base + 2);
		}
	}

	@Test
	void theKeyboardAnswersWhileTwoThousandEventsArrive() throws Exception {
		FakeCluster cluster = new FakeCluster().withObjects(BIG_BURST);
		try (ScreenHarness harness = ScreenHarness.start(cluster, 500)) {
			assertThat(harness.session().model().size()).isEqualTo(BIG_BURST);

			for (int i = 0; i < BIG_BURST; i++) {
				cluster.fire("ADDED", FakeCluster.object("burst-" + i));
			}
			// The keystroke is queued AFTER the whole burst: behind it in every sense.
			harness.backend().type('q');

			long start = System.nanoTime();
			harness.await(() -> !harness.running(), "the app to quit while 2000 events are in flight");
			long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();

			assertThat(harness.session().coalescer().received()).isEqualTo(BIG_BURST);
			assertThat(millis).as("the key waits on nothing the burst posted, because it posts nothing")
				.isLessThan(1_000);
		}
	}

	/**
	 * The control, and it is what makes the test above mean anything: with a redraw
	 * posted per event, the keystroke waits behind <b>every one of them</b>.
	 *
	 * <p>
	 * No screen is involved — this is TamboUI's queue, measured directly.
	 * {@code pollEvent} pulls a {@code UiRunnable} ahead of a {@code TickEvent} but not
	 * ahead of an earlier {@code UiRunnable}, so 2 000 posted redraws are 2 000 items the
	 * key is behind. In the GH#361 spike's live run this was not merely slow: the events
	 * kept coming, the queue never drained, and the app would not quit at all.
	 */
	@Test
	void aRedrawPostedPerEventIsFifoWithKeystrokes() throws Exception {
		FakeBackend backend = new FakeBackend(ScreenHarness.WIDTH, ScreenHarness.HEIGHT);
		AtomicInteger ran = new AtomicInteger();
		AtomicInteger ranBeforeTheKey = new AtomicInteger(-1);
		try (TuiRunner runner = TuiRunner.create(config(backend))) {
			Thread loop = new Thread(() -> run(runner, ran, ranBeforeTheKey), "per-event-control");
			loop.setDaemon(true);
			loop.start();

			for (int i = 0; i < BIG_BURST; i++) {
				runner.runLater(ran::incrementAndGet);
			}
			backend.type('q');
			loop.join(Duration.ofSeconds(10).toMillis());

			assertThat(ranBeforeTheKey.get()).as("every posted redraw ran before the keystroke was even looked at")
				.isEqualTo(BIG_BURST);
		}
	}

	private static void run(TuiRunner runner, AtomicInteger ran, AtomicInteger ranBeforeTheKey) {
		try {
			runner.run((event, self) -> {
				if (event instanceof KeyEvent key && key.isCharIgnoreCase('q')) {
					ranBeforeTheKey.set(ran.get());
					self.quit();
				}
				return false;
			}, (frame) -> {
			});
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Test
	void aResizeRelaysOutAndTheHandlerNeverSeesTheEvent() throws Exception {
		FakeCluster cluster = new FakeCluster().withObjects(300);
		try (ScreenHarness harness = ScreenHarness.start(cluster, 500)) {
			// The positive control: the screen is at the size the test asked for, not at
			// 80x24, which is what a probe that never asked would report.
			assertThat(harness.screen().observedArea().width()).isEqualTo(ScreenHarness.WIDTH);
			assertThat(harness.screen().observedArea().height()).isEqualTo(ScreenHarness.HEIGHT);
			ColumnLayout wide = harness.screen().columns();
			int layouts = harness.screen().layouts();
			int rowsWhenTall = harness.screen().rowsBuiltLastFrame();

			harness.backend().resizeTo(100, 30);
			harness.runner().dispatch(ResizeEvent.of(100, 30));
			harness.await(() -> harness.screen().layouts() > layouts, "the re-layout");

			assertThat(harness.screen().observedArea().width()).isEqualTo(100);
			assertThat(harness.screen().columns()).isNotEqualTo(wide);
			assertThat(harness.screen().columns().total()).isLessThanOrEqualTo(100 - 2);
			assertThat(harness.screen().rowsBuiltLastFrame()).isLessThan(rowsWhenTall);

			// And the finding this design is built on: TuiRunner.run consumes ResizeEvent
			// itself, so a handler branch for it never fires. If this stops being zero,
			// TamboUI changed and Frame.area() is no longer the only way to see a resize.
			assertThat(harness.screen().resizeEventsSeenByHandler()).isZero();
		}
	}

	@Test
	void rowsAreBuiltForTheViewportAndNotForTheList() throws Exception {
		FakeCluster cluster = new FakeCluster().withObjects(BIG_BURST);
		try (ScreenHarness harness = ScreenHarness.start(cluster, 500)) {
			assertThat(harness.session().model().size()).isEqualTo(BIG_BURST);
			assertThat(harness.screen().rowsBuiltLastFrame()).isLessThanOrEqualTo(ScreenHarness.HEIGHT);
		}
	}

	@Test
	void theCursorMovesAndScrollsOverTwoThousandRows() throws Exception {
		FakeCluster cluster = new FakeCluster().withObjects(BIG_BURST);
		try (ScreenHarness harness = ScreenHarness.start(cluster, 500)) {
			harness.backend().type('G');
			harness.await(() -> harness.session().model().selectedIndex() == BIG_BURST - 1, "G to reach the last row");
			assertThat(harness.session().model().selectedRow()).get()
				.hasFieldOrPropertyWithValue("name", String.format("obj-%05d", BIG_BURST - 1));

			harness.backend().type('k');
			harness.await(() -> harness.session().model().selectedIndex() == BIG_BURST - 2, "k to move up one");

			harness.backend().type('g');
			harness.await(() -> harness.session().model().selectedIndex() == 0, "g to return to the first row");
			// The window followed the cursor rather than the cursor leaving the window.
			assertThat(harness.session().model().window(40).first()).isZero();
		}
	}

	private static TuiConfig config(FakeBackend backend) {
		return TuiConfig.builder()
			.backend(backend)
			.noTick()
			.rawMode(false)
			.alternateScreen(false)
			.hideCursor(false)
			.shutdownHook(false)
			.pollTimeout(Duration.ofMillis(5))
			.errorOutput(new PrintStream(new ByteArrayOutputStream(), true))
			.build();
	}

}

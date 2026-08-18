package org.alexmond.kweblens.tui.render;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.tui.data.PreviousLog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The log pane through the shipped loop: the real {@code TuiRunner}, the real
 * {@link ScreenSession}, {@link FakeBackend} for a terminal — and a
 * {@link FakeCluster#logs} whose streams record whether they were released.
 *
 * <p>
 * <b>This file carries GH#369's two headline done-whens</b>, and both are here rather
 * than lower down because both are about the whole path. A release that the pane performs
 * but the session never reaches, or a flush that the model does but the screen repaints
 * around anyway, would both pass every unit test in {@code tui/log}.
 */
class ScreenLogPaneTest {

	private static FakeCluster cluster() {
		return new FakeCluster().withObjects(3);
	}

	private static void openPane(ScreenHarness harness) {
		harness.runner().dispatch(KeyEvent.ofChar('l'));
		harness.await(() -> harness.screen().controller().logsOpen(), "the log pane to open");
	}

	private static void closePane(ScreenHarness harness) {
		harness.runner().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
		harness.await(() -> !harness.screen().controller().logsOpen(), "the log pane to close");
	}

	/**
	 * <b>Done-when 1.</b> Twenty opens and twenty closes against a pod that says nothing,
	 * and no connection is left holding.
	 *
	 * <p>
	 * The pod being <em>quiet</em> is the whole design of this test. A chatty pod
	 * releases by accident — the failed downstream write throws out of the read loop and
	 * the reader is closed, which is what actually tears the connection down — so a test
	 * that opened a busy pod's logs and closed them would pass while the shipped code
	 * leaked one connection per view. Nothing is emitted here at all.
	 *
	 * <p>
	 * The assertion is over <em>every</em> stream rather than over a pair of counters:
	 * "opened 20, closed 20" is also true of a pane that closed one stream twice and
	 * leaked another.
	 */
	@Test
	void openingAndClosingTwentyTimesLeavesNoOpenLogConnection() throws Exception {
		FakeCluster cluster = cluster();
		try (ScreenHarness harness = ScreenHarness.start(cluster, 0)) {
			for (int i = 0; i < 20; i++) {
				openPane(harness);
				closePane(harness);
			}

			assertThat(cluster.logStreamsOpened()).as("twenty views were opened, so there is something to leak")
				.isEqualTo(20);
			assertThat(cluster.openLogStreams())
				.as("every one of them must have gone through LogStream.close(), which is LogService.release — "
						+ "closing the LogWatch alone leaves the request to the API server open and the reader parked")
				.isZero();
		}
	}

	/**
	 * The other half of done-when 1: a session torn down with the pane <em>still up</em>.
	 * Nothing on screen would ever report this one, and it holds the connection for the
	 * life of the process.
	 */
	@Test
	void aSessionClosedWithThePaneStillUpReleasesTheFollow() throws Exception {
		FakeCluster cluster = cluster();
		try (ScreenHarness harness = ScreenHarness.start(cluster, 0)) {
			openPane(harness);
			assertThat(cluster.openLogStreams()).isEqualTo(1);
		}

		assertThat(cluster.openLogStreams()).as("the session's own close owes the release too").isZero();
	}

	/**
	 * <b>Done-when 2.</b> A burst of 157 lines — the same number the list's coalescing
	 * gate fires, the real ReplicaSet count that once froze a tab — costs <b>one</b>
	 * repaint, on the one flush.
	 *
	 * <p>
	 * In a terminal this is not a frame-rate question. TamboUI's {@code pollEvent}
	 * deprioritises only {@code TickEvent}; a redraw posted per line is a
	 * {@code UiRunnable}, which is FIFO with real keys — the GH#361 spike measured a
	 * per-event control that rendered 223 times in four seconds and <em>never processed
	 * the keystroke</em>, so the app would not quit. {@code ScreenLoopTest} keeps that
	 * control standing for the table; this is the same rule for the pane that will meet
	 * it first.
	 *
	 * <p>
	 * The harness posts no ticks of its own ({@code noTick()}), so the 157 lines provably
	 * sit in the buffer until the one tick below.
	 */
	@Test
	void aBurstOfLinesCostsOneRepaintPerFlushPeriod() throws Exception {
		FakeCluster cluster = cluster();
		try (ScreenHarness harness = ScreenHarness.start(cluster, 0)) {
			openPane(harness);
			// Settle first: the frame the keystroke owed has not been drawn when
			// logsOpen() flips, and counting from before it would let that frame answer
			// for the burst's.
			harness.tickAndSettle();
			for (int i = 0; i < 157; i++) {
				cluster.emitLog("line-" + i);
			}
			harness.await(() -> harness.screen().controller().logs().buffered() == 157,
					"all 157 lines to reach the buffer");
			assertThat(harness.screen().controller().logs().size())
				.as("and not one of them is on screen yet — that is what makes the count below a count")
				.isZero();
			int painted = harness.screen().renders();

			harness.tickAndSettle();
			// Ten further ticks over an empty buffer. They are what makes the count below
			// a count rather than a race: `ticksHandled` is bumped inside `handle`,
			// before
			// the render it owes has run, so reading the render counter straight after
			// one
			// `tickAndSettle` can miss a frame that is still being drawn. It also turns
			// the assertion into the one that matters — a tick that finds nothing must
			// cost nothing, or a quiet pod repaints ten times a second forever.
			for (int quiet = 0; quiet < 5; quiet++) {
				harness.tickAndSettle();
			}

			assertThat(harness.screen().renders() - painted)
				.as("157 lines cost one repaint, and ten further ticks over an empty buffer cost none")
				.isEqualTo(1);
			assertThat(harness.screen().controller().logs().size()).isEqualTo(157);
			assertThat(harness.screen().controller().logs().received()).isEqualTo(157);
		}
	}

	/**
	 * And the loop still answers the keyboard while a pod is shouting. The failure the
	 * assertion above bounds is not "the pane is slow", it is "the app will not quit" —
	 * so the burst is followed by a real key press through the real {@code pollEvent}.
	 */
	@Test
	void aBurstDoesNotStarveTheKeyboard() throws Exception {
		FakeCluster cluster = cluster();
		try (ScreenHarness harness = ScreenHarness.start(cluster, 0)) {
			openPane(harness);
			for (int i = 0; i < 2_000; i++) {
				cluster.emitLog("line-" + i);
			}

			closePane(harness);

			assertThat(harness.running()).isTrue();
			assertThat(cluster.openLogStreams()).isZero();
		}
	}

	/**
	 * <b>The windowing property, on a real frame.</b> The viewport is 44 rows less four
	 * of chrome, so a short log and a full 5 000-line buffer build the same number of
	 * widget lines. Flat in document size is the property; #364 measured what linear
	 * costs (10 000 rows: 0.68 ms windowed against 120.8 ms naive, paid again on every
	 * tick).
	 */
	@Test
	void aFrameBuildsLinesForTheViewport_notForTheBuffer() throws Exception {
		List<Integer> built = new ArrayList<>();
		for (int lines : List.of(20, 20_000)) {
			FakeCluster cluster = cluster();
			try (ScreenHarness harness = ScreenHarness.start(cluster, 0)) {
				openPane(harness);
				for (int i = 0; i < lines; i++) {
					cluster.emitLog("line-" + i);
					if (i % 1_000 == 0) {
						harness.tickAndSettle();
					}
				}
				harness.tickAndSettle();
				harness.tickAndSettle();
				built.add(harness.screen().rowsBuiltLastFrame());
			}
		}

		assertThat(built.get(0)).as("a short log builds only what it has").isLessThan(ScreenHarness.HEIGHT - 4);
		assertThat(built.get(1)).as("a full buffer builds no more than the viewport holds")
			.isEqualTo(ScreenHarness.HEIGHT - 4);
	}

	/** The pane replaces the table, and the hint bar becomes the log pane's own. */
	@Test
	void thePaneReplacesTheTableAndTheHintBarBecomesTheLogPanes() throws Exception {
		try (ScreenHarness harness = ScreenHarness.start(cluster(), 0)) {
			openPane(harness);
			harness.tickAndSettle();

			assertThat(harness.screen().title()).startsWith("logs").contains("ns/obj-00000");
			assertThat(harness.screen().hints()).contains("c next container")
				.contains("t timestamps")
				.doesNotContain(": command");
			assertThat(harness.screen().footer()).startsWith("0/0");
		}
	}

	/**
	 * <b>Done-when 3, on a frame.</b> A pod that has never restarted opens a pane that
	 * says so, in the document, with the title calling it a previous run — not a blank
	 * pane, which reads as "it crashed without logging" and is a finding.
	 */
	@Test
	void aPreviousRunWithNoTerminatedInstanceSaysSoRatherThanDrawingNothing() throws Exception {
		FakeCluster cluster = cluster().withPreviousLog(PreviousLog.none("app"));
		try (ScreenHarness harness = ScreenHarness.start(cluster, 0)) {
			harness.runner().dispatch(KeyEvent.ofChar('p'));
			harness.await(() -> harness.screen().controller().logsOpen(), "the previous-run pane to open");
			harness.tickAndSettle();

			assertThat(harness.screen().title()).startsWith("previous run (snapshot)");
			assertThat(harness.screen().controller().logs().size()).as("something is on screen").isEqualTo(1);
			assertThat(harness.screen().controller().logs().visible(harness.screen().controller().logs().window(10)))
				.singleElement()
				.asString()
				.contains("has not restarted");
			assertThat(cluster.logStreamsOpened()).as("a snapshot follows nothing").isZero();
		}
	}

	/**
	 * A container switch is a re-open, so it releases and re-establishes — and it asks
	 * for the container it says it is showing.
	 */
	@Test
	void switchingContainerReleasesTheOldStreamAndOpensTheNamedOne() throws Exception {
		FakeCluster cluster = cluster().withContainers("app", "sidecar");
		try (ScreenHarness harness = ScreenHarness.start(cluster, 0)) {
			openPane(harness);
			harness.runner().dispatch(KeyEvent.ofChar('c'));
			harness.await(() -> "sidecar".equals(harness.screen().controller().logs().container()),
					"the pane to switch container");

			assertThat(cluster.logContainers()).containsExactly("app", "sidecar");
			assertThat(cluster.openLogStreams()).as("the first stream was released, not merely forgotten").isEqualTo(1);
		}
	}

	/**
	 * Timestamps are the API server's, so the toggle re-opens through
	 * {@code watchWithTimestamps} rather than decorating lines client-side — a stamp the
	 * terminal invented would be the time it read the line, not the time the container
	 * wrote it.
	 */
	@Test
	void togglingTimestampsReopensThroughTheTimestampedCall() throws Exception {
		FakeCluster cluster = cluster();
		try (ScreenHarness harness = ScreenHarness.start(cluster, 0)) {
			openPane(harness);
			harness.runner().dispatch(KeyEvent.ofChar('t'));
			harness.await(() -> harness.screen().controller().logs().hasTimestamps(), "timestamps to come on");

			assertThat(cluster.timestampedLogs()).isEqualTo(1);
			assertThat(cluster.logStreamsOpened()).isEqualTo(2);
			assertThat(cluster.openLogStreams()).isEqualTo(1);
		}
	}

	/**
	 * A container that exits stops the stream, and the pane leads with NOT LIVE — the
	 * header's rule (GH#413) applied here: a pane still drawing lines with no indication
	 * that nothing more is coming is a photograph presented as a live view.
	 */
	@Test
	void aStreamThatEndsPutsNotLiveAtTheFrontOfTheFooter() throws Exception {
		FakeCluster cluster = cluster();
		try (ScreenHarness harness = ScreenHarness.start(cluster, 0)) {
			openPane(harness);
			cluster.emitLog("goodbye");
			cluster.endLog();
			harness.tickUntil(() -> !harness.screen().controller().logs().live(), "the stream to be reported ended");

			assertThat(harness.screen().footer()).startsWith("NOT LIVE — the container's log stream ended");
		}
	}

	/**
	 * A cluster that will not serve the log reaches the operator as a footer sentence,
	 * and the screen survives it. An exception here would leave
	 * {@code ResourceScreen.handle} for TamboUI's runner, whose default error handler
	 * sets {@code inErrorState} and never clears it (GH#434).
	 */
	@Test
	void aRefusedLogIsASentenceAndTheLoopKeepsRunning() throws Exception {
		FakeCluster cluster = cluster().refuseContainers(new IllegalStateException("forbidden: pods/log"));
		try (ScreenHarness harness = ScreenHarness.start(cluster, 0)) {
			harness.runner().dispatch(KeyEvent.ofChar('l'));
			harness.await(() -> !harness.screen().controller().message().isEmpty(), "the refusal to be reported");
			harness.tickAndSettle();

			assertThat(harness.screen().controller().logsOpen()).isFalse();
			assertThat(harness.screen().footer()).contains("forbidden: pods/log");
			assertThat(harness.running()).as("the loop is still alive").isTrue();
		}
	}

	/**
	 * The watch under the pane is not stopped. A watch closed on the way into a log pane
	 * would leave the list stale when the pane closed, with a row count that reads as
	 * current — GH#413 arrived at from a new direction, the same one
	 * {@code ScreenDetailPaneTest} guards for the other pane.
	 */
	@Test
	void openingTheLogPaneDoesNotStopTheWatchUnderIt() throws Exception {
		FakeCluster cluster = cluster();
		try (ScreenHarness harness = ScreenHarness.start(cluster, 0)) {
			openPane(harness);
			harness.tickAndSettle();

			assertThat(cluster.watchClosed()).isFalse();
			assertThat(harness.screen().header()).doesNotContain("NOT LIVE");
		}
	}

}

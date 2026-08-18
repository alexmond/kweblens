package org.alexmond.kweblens.tui.log;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.tui.screen.RowWindow;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The log pane's document: what a burst costs, where the cursor goes, and what the pane
 * says about a stream that has stopped.
 *
 * <p>
 * No terminal, no thread, no cluster — lines go in through {@link LogModel#append} the
 * way the reader thread puts them there, and what comes out is a document, a cursor and
 * two sentences.
 */
class LogModelTest {

	private static LogModel following() {
		return LogModel.following("ns", "web-0", "app", List.of("app"), false);
	}

	/**
	 * <b>Done-when 2, at the model.</b> 157 lines — the list coalescer's number — produce
	 * exactly one change to the document, on the one flush. The screen test proves the
	 * repaint; this proves there is only one thing that could cause one.
	 */
	@Test
	void aBurstOfLinesIsOneFlushAndOneChange() {
		LogModel model = following();

		for (int i = 0; i < 157; i++) {
			model.append("line-" + i);
		}

		assertThat(model.size()).as("appending puts nothing in the document — that is the whole rule").isZero();
		assertThat(model.buffered()).isEqualTo(157);
		assertThat(model.flush()).as("one flush, one change").isTrue();
		assertThat(model.size()).isEqualTo(157);
		assertThat(model.flush()).as("and a second flush finds nothing, so no repaint is owed").isFalse();
		assertThat(model.received()).isEqualTo(157);
	}

	@Test
	void followingPinsTheCursorToTheTail() {
		LogModel model = following();
		model.append("a");
		model.append("b");
		model.flush();

		assertThat(model.following()).isTrue();
		assertThat(model.selectedIndex()).isEqualTo(1);
		assertThat(model.status()).contains("2/2").contains("following");
	}

	/**
	 * Scrolling up leaves the tail, and {@code G} rejoins it — one piece of state, the
	 * cursor, so there is nothing for a second flag to disagree with.
	 */
	@Test
	void scrollingUpPausesTheFollowAndTheLastLineResumesIt() {
		LogModel model = following();
		for (int i = 0; i < 10; i++) {
			model.append("line-" + i);
		}
		model.flush();

		assertThat(model.moveSelection(-3)).isTrue();
		assertThat(model.following()).isFalse();
		assertThat(model.status()).contains("paused (G follows again)");

		model.append("line-10");
		model.flush();
		assertThat(model.selectedIndex()).as("a paused pane does not jump to a line that just arrived").isEqualTo(6);

		assertThat(model.selectTo(Integer.MAX_VALUE)).isTrue();
		assertThat(model.following()).isTrue();
	}

	/**
	 * <b>The reader's place does not drift.</b> When the ring is full every append shifts
	 * every index down by one, so a cursor parked on a stack trace has to move with it.
	 * Ten lines into a full ring, and the line under the cursor is the same line.
	 */
	@Test
	void aPausedCursorStaysOnTheLineItWasOnWhileTheBoundDropsOlderOnes() {
		LogModel model = LogModel.following("ns", "web-0", "app", List.of("app"), false);
		// Flushed as it goes, because the BUFFER is bounded too and a straight 5 000-line
		// append would drop 3 000 of them before the ring ever saw one. That is the
		// buffer's bound doing its job — see LogBufferTest — and it is also how a test
		// that meant to fill the ring can quietly end up filling neither.
		for (int i = 0; i < LogRing.DEFAULT_CAPACITY; i++) {
			model.append("line-" + i);
			if (i % 1_000 == 0) {
				model.flush();
			}
		}
		model.flush();
		assertThat(model.size()).isEqualTo(LogRing.DEFAULT_CAPACITY);
		model.selectTo(4_000);
		assertThat(model.following()).isFalse();

		for (int i = 0; i < 10; i++) {
			model.append("overflow-" + i);
		}
		model.flush();

		assertThat(model.selectedIndex()).as("shifted down by exactly the ten that fell off the front")
			.isEqualTo(3_990);
		assertThat(model.visible(new RowWindow(model.selectedIndex(), 1, 0))).containsExactly("line-4000");
		assertThat(model.discarded()).isEqualTo(10);
		assertThat(model.status()).contains("oldest 10 dropped, buffer holds 5000");
	}

	/**
	 * <b>Done-when 3.</b> A previous run is a previous run in the <em>title</em>, because
	 * a reader who takes a terminated instance's log for the live one draws exactly the
	 * wrong conclusion about a crashloop. It is also never live, so there is no NOT LIVE
	 * to explain.
	 */
	@Test
	void aPreviousRunSaysSoInTheTitleAndIsNeverLive() {
		LogModel model = LogModel.previous("ns", "web-0", "app", List.of("app"), "panic: boom\nexit 2\n", null);

		assertThat(model.title()).startsWith("previous run (snapshot)").contains("ns/web-0").contains("app");
		assertThat(model.isPrevious()).isTrue();
		assertThat(model.live()).isFalse();
		assertThat(model.status()).doesNotContain("NOT LIVE").doesNotContain("following");
		assertThat(model.visible(model.window(10))).contains("panic: boom", "exit 2");
	}

	/**
	 * <b>Done-when 3, the other half.</b> No previous run is a sentence in the document,
	 * not an empty pane — an empty pane reads as "it crashed without logging", which is a
	 * finding, and it must not be manufactured by a missing branch.
	 */
	@Test
	void noPreviousRunIsWordsAndNotAnEmptyPane() {
		LogModel model = LogModel.previous("ns", "web-0", "app", List.of("app"), "",
				"No previous run of container 'app' — it has not restarted, so there is no terminated instance to read.");

		assertThat(model.size()).as("there is something on screen").isEqualTo(1);
		assertThat(model.visible(model.window(10))).singleElement()
			.asString()
			.contains("has not restarted")
			.contains("no terminated instance");
	}

	/**
	 * A stream that stops is admitted to, and the notice goes <b>first</b> — the header's
	 * rule (GH#413) applied to this pane: every number after it is a claim about a moment
	 * that has passed.
	 */
	@Test
	void aStreamThatEndsLeadsTheStatusWithNotLive() {
		LogModel model = following();
		model.append("a");
		model.flush();
		assertThat(model.status()).doesNotContain("NOT LIVE");

		model.ended("the container's log stream ended");

		assertThat(model.live()).isFalse();
		assertThat(model.status()).startsWith("NOT LIVE — the container's log stream ended");
		assertThat(model.status()).as("and it no longer claims to be following").doesNotContain("following");
	}

	@Test
	void theFirstReasonForEndingWinsBecauseTheSecondWouldOverwriteTheInformativeOne() {
		LogModel model = following();

		model.ended("the log stream failed: connection reset");
		model.ended("the container's log stream ended");

		assertThat(model.endedReason()).isEqualTo("the log stream failed: connection reset");
	}

	/**
	 * The title says which container of how many, so the reader knows {@code c} has
	 * somewhere to go — and a pod with one container is not decorated with "(1 of 1)".
	 */
	@Test
	void theTitleNamesWhichContainerOfHowManyOnlyWhenThereIsAChoice() {
		assertThat(LogModel.following("ns", "web-0", "app", List.of("app"), false).title()).endsWith("app")
			.doesNotContain("of 1");
		assertThat(LogModel.following("ns", "web-0", "sidecar", List.of("app", "sidecar", "proxy"), false).title())
			.contains("sidecar (2 of 3)");
		assertThat(LogModel.following("ns", "web-0", "", List.of(), true).title()).contains("default container")
			.endsWith("timestamps");
	}

	/**
	 * The window is what the renderer may build lines for, and it is flat in document
	 * size — the property #364 measured, applied to the longest list in the product.
	 */
	@Test
	void theWindowIsBoundedByTheViewportAndNotByTheDocument() {
		LogModel model = following();
		for (int i = 0; i < 5_000; i++) {
			model.append("line-" + i);
		}
		model.flush();

		RowWindow window = model.window(40);

		assertThat(window.size()).isEqualTo(40);
		assertThat(model.visible(window)).hasSize(40).last().isEqualTo("line-4999");
	}

	@Test
	void anEmptyDocumentHasAnEmptyWindowRatherThanACursorNowhere() {
		LogModel model = following();

		assertThat(model.window(40)).isEqualTo(RowWindow.EMPTY);
		assertThat(model.status()).startsWith("0/0");
	}

	/**
	 * A pod that outran the tick is reported, because a gap in a log the reader cannot
	 * see is the one thing worse than a slow pane.
	 */
	@Test
	void linesDroppedBeforeTheyCouldBeShownAreReported() {
		LogModel model = following();
		for (int i = 0; i < LogBuffer.DEFAULT_CAPACITY + 5; i++) {
			model.append("line-" + i);
		}
		model.flush();

		assertThat(model.droppedByBuffer()).isEqualTo(5);
		assertThat(model.status()).contains("5 lines arrived faster than the screen ticks");
	}

}

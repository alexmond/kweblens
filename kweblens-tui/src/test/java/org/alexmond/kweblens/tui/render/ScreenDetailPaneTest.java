package org.alexmond.kweblens.tui.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.fabric8.kubernetes.api.model.PodBuilder;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.event.EventSummary;
import org.alexmond.kweblens.resource.Relation;
import org.alexmond.kweblens.tui.data.ObjectDetail;
import org.alexmond.kweblens.tui.screen.KeyStroke;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The detail pane through the shipped loop: the real {@code TuiRunner}, the real
 * {@link ScreenSession}, {@link FakeBackend} for a terminal.
 *
 * <p>
 * What this adds over {@code ViewControllerDetailTest} is the two things only a frame can
 * answer: that the pane is what gets drawn, and that a frame builds lines for the
 * viewport rather than for the document.
 */
class ScreenDetailPaneTest {

	private static ObjectDetail detailOf(int yamlLines) {
		StringBuilder yaml = new StringBuilder(yamlLines * 12);
		for (int i = 0; i < yamlLines; i++) {
			yaml.append("  field-").append(i).append(": value\n");
		}
		return ObjectDetail.of(yaml.toString(), Map.of("selectedPods", Relation.of(List.of(pod()), true)),
				List.of(new EventSummary("Warning", "BackOff", "Pod/obj-00000", "ns", "Back-off restarting", "2m")));
	}

	private static io.fabric8.kubernetes.api.model.Pod pod() {
		return new PodBuilder().withNewMetadata().withName("backing-pod").withNamespace("ns").endMetadata().build();
	}

	private static FakeCluster cluster(int yamlLines) {
		return new FakeCluster().withObjects(3).withDetail(detailOf(yamlLines));
	}

	/**
	 * <b>The windowing property, on a real frame.</b> The viewport is 44 rows less four
	 * of chrome, so a 40-line document and a 10 000-line one build the same number of
	 * widget lines. Flat in document size is the property; #364 measured what linear
	 * costs (10 000 table rows: 0.68 ms windowed against 120.8 ms naive, paid again on
	 * every tick).
	 */
	@Test
	void aFrameBuildsLinesForTheViewport_notForTheDocument() throws Exception {
		List<Integer> built = new ArrayList<>();
		for (int lines : List.of(20, 10_000)) {
			try (ScreenHarness harness = ScreenHarness.start(cluster(lines), 0)) {
				harness.runner().dispatch(KeyEvent.ofChar('d'));
				harness.await(() -> harness.screen().controller().paneOpen(), "the detail pane to open");
				harness.tickAndSettle();
				built.add(harness.screen().rowsBuiltLastFrame());
			}
		}

		assertThat(built.get(0)).as("a short document builds only what it has").isLessThan(ScreenHarness.HEIGHT - 4);
		assertThat(built.get(1)).as("a 10 000-line document builds no more than the viewport holds")
			.isEqualTo(ScreenHarness.HEIGHT - 4);
	}

	@Test
	void thePaneReplacesTheTableAndTheHintBarBecomesThePanes() throws Exception {
		try (ScreenHarness harness = ScreenHarness.start(cluster(10), 0)) {
			harness.runner().dispatch(KeyEvent.ofChar('d'));
			harness.await(() -> harness.screen().controller().paneOpen(), "the detail pane to open");
			harness.tickAndSettle();

			assertThat(harness.screen().title()).startsWith("detail (snapshot)");
			assertThat(harness.screen().footer()).as("the footer counts lines in the pane, not rows in the table")
				.startsWith("1/");
			assertThat(harness.screen().controller().detail().lines()).extracting((line) -> line.text())
				.anySatisfy((line) -> assertThat(line).contains("Selected Pods"))
				.anySatisfy((line) -> assertThat(line).contains("truncated — we stopped at 1"));
		}
	}

	/**
	 * The pane is a snapshot and the watch under it is not stopped. A watch closed on the
	 * way into a pane would leave the list stale when the pane closed, with a row count
	 * that reads as current — GH#413 arrived at from a new direction.
	 */
	@Test
	void openingThePaneDoesNotStopTheWatchUnderIt() throws Exception {
		FakeCluster cluster = cluster(10);
		try (ScreenHarness harness = ScreenHarness.start(cluster, 0)) {
			harness.runner().dispatch(KeyEvent.ofChar('d'));
			harness.await(() -> harness.screen().controller().paneOpen(), "the detail pane to open");
			harness.tickAndSettle();

			assertThat(cluster.watchClosed()).isFalse();
			assertThat(harness.screen().header()).doesNotContain("NOT LIVE");
		}
	}

	/** Closing it puts the table back, with its own hint bar and its own title. */
	@Test
	void closingThePanePutsTheTableBack() throws Exception {
		try (ScreenHarness harness = ScreenHarness.start(cluster(10), 0)) {
			harness.runner().dispatch(KeyEvent.ofChar('d'));
			harness.await(() -> harness.screen().controller().paneOpen(), "the detail pane to open");

			harness.runner().dispatch(KeyEvent.ofChar('q'));
			harness.await(() -> !harness.screen().controller().paneOpen(), "the pane to close");
			harness.tickAndSettle();

			assertThat(harness.screen().title()).doesNotContain("detail");
			assertThat(harness.screen().rowsBuiltLastFrame()).as("the table is drawing rows again").isEqualTo(3);
		}
	}

	/**
	 * <b>The prompt stops being painted when it stops being open.</b> This is the frame
	 * half of the same rule {@code ViewControllerDetailTest} states as an outcome: the
	 * runner skips {@code safeRender} altogether when {@code handle} returns false, so a
	 * submit that closes the prompt and reports "nothing changed" leaves the last frame —
	 * the one with the {@code /} prompt in its footer — on the terminal. Nothing else
	 * repaints it: a healthy watch over a quiet cluster owes no tick repaint either.
	 *
	 * <p>
	 * The render counter is read after awaiting the {@code /} frame, because it is bumped
	 * last of all in {@code render} — waiting on it is what makes "the next frame" a
	 * frame and not a half-drawn one.
	 */
	@Test
	void submittingAnEmptySearchRedrawsTheFooterThatWasStillShowingThePrompt() throws Exception {
		try (ScreenHarness harness = ScreenHarness.start(cluster(10), 0)) {
			harness.runner().dispatch(KeyEvent.ofChar('d'));
			harness.await(() -> harness.screen().controller().paneOpen(), "the detail pane to open");
			// Settle first: the pane's own frame has not been drawn yet when paneOpen()
			// flips, and counting from before it would let that frame answer for the
			// prompt's.
			harness.tickAndSettle();
			int beforePrompt = harness.screen().renders();
			harness.runner().dispatch(KeyEvent.ofChar('/'));
			harness.await(() -> harness.screen().renders() > beforePrompt, "the frame that painted the prompt");
			assertThat(harness.screen().footer()).as("the prompt is what is on screen now").startsWith("/");
			int painted = harness.screen().renders();

			harness.runner().dispatch(KeyEvent.ofKey(KeyCode.ENTER));

			harness.await(() -> !harness.screen().controller().prompt().open(), "the prompt to close");
			harness.await(() -> harness.screen().renders() > painted,
					"a frame after the prompt closed — without one the terminal still shows it");
			assertThat(harness.screen().footer()).doesNotStartWith("/");
		}
	}

	/**
	 * A read the cluster refuses reaches the operator as a footer sentence, and the
	 * screen survives it. An exception here would leave {@code ResourceScreen.handle} for
	 * TamboUI's runner, whose default error handler sets {@code inErrorState} and never
	 * clears it (GH#434).
	 */
	@Test
	void aRefusedReadIsASentenceAndTheLoopKeepsRunning() throws Exception {
		FakeCluster cluster = new FakeCluster().withObjects(3)
			.refuseDetail(new IllegalStateException("forbidden: pods is not readable"));
		try (ScreenHarness harness = ScreenHarness.start(cluster, 0)) {
			harness.runner().dispatch(KeyEvent.ofChar('d'));
			harness.await(() -> !harness.screen().controller().message().isEmpty(), "the refusal to be reported");
			harness.tickAndSettle();

			assertThat(harness.screen().controller().paneOpen()).isFalse();
			assertThat(harness.screen().footer()).contains("forbidden: pods is not readable");
			assertThat(harness.running()).as("the loop is still alive").isTrue();
			assertThat(harness.screen().controller().key(KeyStroke.of('j')))
				.as("and still answering keys, which is what an inErrorState screen would not")
				.isNotNull();
		}
	}

}

package org.alexmond.kweblens.tui.detail;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.tui.screen.RowWindow;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pane's cursor, its window and its search — no terminal anywhere.
 */
class DetailModelTest {

	private static DetailModel model(int lines) {
		List<DetailLine> document = new ArrayList<>(lines);
		for (int i = 0; i < lines; i++) {
			document.add(DetailLine.text("line-" + i));
		}
		return new DetailModel("Pod web/one", document);
	}

	/**
	 * <b>The windowing property, and it is not the ratio.</b> A window over 10 000 lines
	 * builds the same number of widget lines as a window over 40 — flat in document size,
	 * so a big YAML costs a frame nothing extra. A naive pane is linear and pays it again
	 * on every tick for as long as the pane is open.
	 */
	@Test
	void theWindowIsBoundedByTheViewport_notByTheDocument() {
		RowWindow small = model(40).window(20);
		RowWindow huge = model(10_000).window(20);

		assertThat(small.size()).isEqualTo(20);
		assertThat(huge.size()).as("a 10 000-line document builds no more lines than a 40-line one").isEqualTo(20);
	}

	@Test
	void theWindowFollowsTheCursorDown() {
		DetailModel model = model(200);
		model.selectTo(150);

		RowWindow window = model.window(20);

		assertThat(window.first()).isEqualTo(131);
		assertThat(window.selectedInWindow()).isEqualTo(19);
		assertThat(model.visible(window)).hasSize(20).first().extracting(DetailLine::text).isEqualTo("line-131");
	}

	@Test
	void aDocumentShorterThanTheViewportIsAllOfIt() {
		RowWindow window = model(5).window(40);

		assertThat(window.first()).isZero();
		assertThat(window.size()).isEqualTo(5);
	}

	@Test
	void anEmptyDocumentHasAnEmptyWindow() {
		assertThat(new DetailModel("nothing", List.of()).window(40)).isEqualTo(RowWindow.EMPTY);
	}

	@Test
	void theCursorIsClampedToTheDocument() {
		DetailModel model = model(10);

		assertThat(model.selectTo(Integer.MAX_VALUE)).isTrue();
		assertThat(model.selectedIndex()).isEqualTo(9);
		assertThat(model.moveSelection(1)).as("a key pressed at the end costs no repaint").isFalse();
	}

	@Test
	void searchMovesToTheFirstMatchAtOrAfterTheCursor() {
		DetailModel model = model(100);
		model.selectTo(50);

		assertThat(model.search("line-7")).isTrue();

		assertThat(model.selectedIndex()).as("70, not 7: searching starts from where the reader is looking")
			.isEqualTo(70);
		assertThat(model.matchCount()).isEqualTo(11);
	}

	@Test
	void searchWrapsToTheTopWhenThereIsNothingBelow() {
		DetailModel model = model(100);
		model.selectTo(99);

		model.search("line-4");
		assertThat(model.selectedIndex()).isEqualTo(4);
	}

	@Test
	void nextAndPreviousWalkTheMatchesAndWrapBothWays() {
		DetailModel model = model(30);
		model.search("line-1");
		int first = model.selectedIndex();

		model.nextMatch();
		int second = model.selectedIndex();
		model.previousMatch();

		assertThat(second).isGreaterThan(first);
		assertThat(model.selectedIndex()).isEqualTo(first);
		model.previousMatch();
		assertThat(model.selectedIndex()).as("wraps to the last match rather than stopping").isGreaterThan(second);
	}

	@Test
	void searchIsCaseInsensitive() {
		DetailModel model = new DetailModel("s", List.of(DetailLine.text("apiVersion: v1")));

		assertThat(model.search("APIVERSION")).isTrue();
		assertThat(model.matchCount()).isEqualTo(1);
	}

	@Test
	void aSearchThatFoundNothingSaysSo_becauseAnUnchangedScreenIsNotAnAnswer() {
		DetailModel model = model(10);

		model.search("nothing-like-this");

		assertThat(model.matchCount()).isZero();
		assertThat(model.searchStatus()).isEqualTo("/nothing-like-this  no match");
	}

	@Test
	void theStatusCountsWhereTheCursorIsAmongTheMatches() {
		DetailModel model = model(30);

		model.search("line-2");

		assertThat(model.searchStatus()).isEqualTo("/line-2  match 1 of 11");
		model.nextMatch();
		assertThat(model.searchStatus()).isEqualTo("/line-2  match 2 of 11");
	}

	@Test
	void scrollingAwayFromTheMatchesStopsClaimingAPosition() {
		DetailModel model = model(30);
		model.search("line-2");

		model.selectTo(1);

		assertThat(model.searchStatus()).as("'match 0 of 11' would be a number about nothing")
			.isEqualTo("/line-2  11 matches");
	}

	@Test
	void clearingTheSearchReportsWhetherThereWasOne() {
		DetailModel model = model(10);

		assertThat(model.clearSearch()).as("nothing to clear").isFalse();
		model.search("line-3");
		assertThat(model.clearSearch()).isTrue();
		assertThat(model.searchStatus()).isEmpty();
		assertThat(model.matchCount()).isZero();
	}

	@Test
	void anEmptySearchClearsRatherThanMatchingEverything() {
		DetailModel model = model(10);
		model.search("line-3");

		model.search("   ");

		assertThat(model.query()).isEmpty();
	}

	@Test
	void nextMatchWithNoSearchDoesNothing() {
		DetailModel model = model(10);

		assertThat(model.nextMatch()).isFalse();
		assertThat(model.previousMatch()).isFalse();
	}

}

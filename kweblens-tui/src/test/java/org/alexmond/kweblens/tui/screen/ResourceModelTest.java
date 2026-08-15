package org.alexmond.kweblens.tui.screen;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Selection, scrolling and the visible window over a list far bigger than the screen —
 * with no terminal anywhere, because these are decisions with right and wrong answers.
 */
class ResourceModelTest {

	private static final int ROWS = 2_006;

	private static final int VIEWPORT = 42;

	private final ResourceModel model = new ResourceModel();

	private static ResourceRow row(String namespace, String name) {
		return new ResourceRow(namespace + "/" + name, namespace, name, null, "1d");
	}

	private void seed(int count) {
		List<ResourceRow> rows = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			rows.add(row("ns", String.format("obj-%05d", i)));
		}
		this.model.replaceAll(rows);
	}

	@Test
	void rowsAreOrderedByNamespaceAndNameHoweverTheyArrive() {
		this.model.upsert(List.of(row("zeta", "a"), row("alpha", "b")));
		this.model.upsert(List.of(row("alpha", "a")));

		assertThat(this.model.rows()).extracting(ResourceRow::key).containsExactly("alpha/a", "alpha/b", "zeta/a");
	}

	@Test
	void theWindowIsBoundedByTheViewportAndNotByTheList() {
		seed(ROWS);

		RowWindow window = this.model.window(VIEWPORT);

		assertThat(this.model.size()).isEqualTo(ROWS);
		assertThat(window.size()).isEqualTo(VIEWPORT);
		assertThat(this.model.visible(window)).hasSize(VIEWPORT);
	}

	@Test
	void theWindowFollowsTheCursorDownAndBackUp() {
		seed(ROWS);
		this.model.window(VIEWPORT);

		this.model.selectTo(ROWS - 1);
		RowWindow atEnd = this.model.window(VIEWPORT);
		assertThat(atEnd.first()).isEqualTo(ROWS - VIEWPORT);
		assertThat(atEnd.selectedInWindow()).isEqualTo(VIEWPORT - 1);
		assertThat(this.model.visible(atEnd)).last()
			.extracting(ResourceRow::name)
			.isEqualTo(String.format("obj-%05d", ROWS - 1));

		this.model.selectTo(0);
		RowWindow atStart = this.model.window(VIEWPORT);
		assertThat(atStart.first()).isZero();
		assertThat(atStart.selectedInWindow()).isZero();
	}

	@Test
	void movingPastEitherEndIsClampedAndReportsThatNothingMoved() {
		seed(10);

		assertThat(this.model.moveSelection(-1)).isFalse();
		assertThat(this.model.selectedIndex()).isZero();

		assertThat(this.model.selectTo(Integer.MAX_VALUE)).isTrue();
		assertThat(this.model.selectedIndex()).isEqualTo(9);
		assertThat(this.model.moveSelection(1)).as("a key pressed at the end owes no repaint").isFalse();
	}

	@Test
	void removingTheSelectedTailRowPullsTheCursorBackIntoTheList() {
		seed(10);
		this.model.selectTo(9);

		this.model.remove(List.of("ns/obj-00009"));

		assertThat(this.model.size()).isEqualTo(9);
		assertThat(this.model.selectedIndex()).isEqualTo(8);
		assertThat(this.model.selectedRow()).get().hasFieldOrPropertyWithValue("name", "obj-00008");
	}

	@Test
	void anEmptyModelHasNoWindowAndNoSelectedRow() {
		assertThat(this.model.window(VIEWPORT)).isEqualTo(RowWindow.EMPTY);
		assertThat(this.model.visible(RowWindow.EMPTY)).isEmpty();
		assertThat(this.model.selectedRow()).isEmpty();
	}

	@Test
	void aViewportWithNoHeightShowsNothingRatherThanEverything() {
		seed(ROWS);

		assertThat(this.model.window(0).size()).isZero();
	}

	@Test
	void removingSomethingThatIsNotThereChangesNothing() {
		seed(3);

		assertThat(this.model.remove(List.of("ns/nope"))).isFalse();
		assertThat(this.model.upsert(List.of())).isFalse();
	}

	@Test
	void distinctStatesAreTheVerdictsPresentAndNothingElse() {
		this.model.replaceAll(List.of(new ResourceRow("ns/a", "ns", "a", "Running", "1d"),
				new ResourceRow("ns/b", "ns", "b", "Running", "1d"),
				new ResourceRow("ns/c", "ns", "c", "CrashLoopBackOff", "1d"),
				new ResourceRow("ns/d", "ns", "d", null, "1d")));

		assertThat(this.model.distinctStates()).containsExactly("Running", "CrashLoopBackOff");
	}

}

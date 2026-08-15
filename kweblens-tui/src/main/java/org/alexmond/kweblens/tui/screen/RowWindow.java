package org.alexmond.kweblens.tui.screen;

/**
 * The slice of the model a frame is allowed to build widgets for.
 *
 * <p>
 * <b>This exists because row construction, not the widget, is what a big list costs.</b>
 * At 2 206 rows in a 132×44 terminal the GH#361 spike measured 40.7 ms of a 46.6 ms frame
 * going into building widget rows, while TamboUI's own {@code Table.render} was ~3 ms of
 * it; windowing took the frame to 7.8 ms. #364 re-measured the table in isolation and the
 * shape is the same — the numbers, and what they do and do not compare, are on
 * {@code ResourceTableView}. TamboUI's {@code Table} will happily scroll the whole list
 * and it is correct when it does; it just charges a {@code Row} per object per frame, and
 * a terminal repaints on a tick forever.
 *
 * @param first index of the first visible row, zero-based
 * @param size how many rows are visible
 * @param selectedInWindow the selected row's index <em>within this window</em>, or -1
 * when the selection is not in it (an empty model)
 */
public record RowWindow(int first, int size, int selectedInWindow) {

	/** The empty window, for a model with no rows or a viewport with no height. */
	public static final RowWindow EMPTY = new RowWindow(0, 0, -1);

	/** One past the last visible index. */
	public int end() {
		return this.first + this.size;
	}

}

package org.alexmond.kweblens.tui.render;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;

import org.alexmond.kweblens.tui.screen.ColumnLayout;
import org.alexmond.kweblens.tui.screen.ResourceModel;
import org.alexmond.kweblens.tui.screen.ResourceRow;
import org.alexmond.kweblens.tui.screen.RowWindow;

/**
 * Draws the table — and <b>only the rows that are on screen</b>.
 *
 * <p>
 * TamboUI's {@code Table} takes the whole row list and scrolls it correctly, and that is
 * the tempting call. It costs a {@code Row} and a {@code Cell} per object per frame.
 * Measured at 132×44, warmed, table build plus render only (selection at the end of the
 * list, which is where {@code G} puts you and the worst case for the naive path):
 *
 * <pre>
 * rows    windowed   naive
 * 2 206   0.69 ms    27.71 ms    40x
 * 10 000  0.68 ms   120.83 ms   178x
 * </pre>
 *
 * <b>The point is not the ratio, it is that windowed is flat and naive is linear.</b> A
 * terminal repaints on a tick for as long as it is open, so a per-object cost is paid
 * again every period, forever. (The GH#361 spike's 46.6 ms → 7.8 ms was a whole frame
 * including the terminal write; these numbers are the table alone, so they are smaller
 * and not comparable to it.)
 *
 * <p>
 * The consequence to keep in mind: {@link TableState} here is about the window, not the
 * model. Its {@code offset} is always 0 and its selection is
 * {@link RowWindow#selectedInWindow()}. The real scroll position lives in
 * {@link ResourceModel}, where it can be tested without a terminal.
 */
public class ResourceTableView {

	/** Marks the selected row. Two cells, and the layout has to pay for them. */
	static final String CURSOR = "> ";

	private static final String NO_VERDICT = "—";

	private final Style header = Style.create().bold().cyan();

	private final Style highlight = Style.create().reversed();

	/**
	 * Render the visible slice of {@code model} into {@code area}.
	 * @return how many widget rows were constructed — the number the windowing exists to
	 * keep small, and the one a measurement reads
	 */
	public int render(Frame frame, Rect area, ResourceModel model, ColumnLayout layout) {
		// One line of the area is the table's own header row.
		RowWindow window = model.window(Math.max(0, area.height() - 1));
		List<ResourceRow> visible = model.visible(window);
		List<Row> widgetRows = new ArrayList<>(visible.size());
		for (ResourceRow row : visible) {
			widgetRows.add(Row.from(cells(row, layout)));
		}
		TableState state = new TableState();
		if (window.selectedInWindow() >= 0) {
			state.select(window.selectedInWindow());
		}
		Table table = Table.builder()
			.rows(widgetRows)
			.header(Row.from(headerCells(layout)).style(this.header))
			.widths(widths(layout))
			.columnSpacing(ColumnLayout.GAP)
			.highlightSymbol(CURSOR)
			.highlightStyle(this.highlight)
			.build();
		frame.renderStatefulWidget(table, area, state);
		return widgetRows.size();
	}

	private static List<Cell> cells(ResourceRow row, ColumnLayout layout) {
		List<Cell> cells = new ArrayList<>(4);
		if (layout.namespace() > 0) {
			cells.add(Cell.from(row.namespace()));
		}
		cells.add(Cell.from(row.name()));
		if (layout.state() > 0) {
			cells.add(Cell.from((row.state() != null) ? row.state() : NO_VERDICT));
		}
		if (layout.age() > 0) {
			cells.add(Cell.from(row.age()));
		}
		return cells;
	}

	private static List<Cell> headerCells(ColumnLayout layout) {
		List<Cell> cells = new ArrayList<>(4);
		if (layout.namespace() > 0) {
			cells.add(Cell.from("NAMESPACE"));
		}
		cells.add(Cell.from("NAME"));
		if (layout.state() > 0) {
			cells.add(Cell.from("STATE"));
		}
		if (layout.age() > 0) {
			cells.add(Cell.from("AGE"));
		}
		return cells;
	}

	private static List<Constraint> widths(ColumnLayout layout) {
		List<Constraint> widths = new ArrayList<>(4);
		if (layout.namespace() > 0) {
			widths.add(Constraint.length(layout.namespace()));
		}
		widths.add(Constraint.min(layout.name()));
		if (layout.state() > 0) {
			widths.add(Constraint.length(layout.state()));
		}
		if (layout.age() > 0) {
			widths.add(Constraint.length(layout.age()));
		}
		return widths;
	}

}

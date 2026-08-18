package org.alexmond.kweblens.tui.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

	/** The current kind's column headings; empty for a kind that declares none. */
	private List<String> headers = List.of();

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
			widgetRows.add(Row.from(cells(row, layout).stream().map(Cell::from).toList()));
		}
		TableState state = new TableState();
		if (window.selectedInWindow() >= 0) {
			state.select(window.selectedInWindow());
		}
		Table table = Table.builder()
			.rows(widgetRows)
			.header(Row.from(headings(layout).stream().map(Cell::from).toList()).style(this.header))
			.widths(widths(layout))
			.columnSpacing(ColumnLayout.GAP)
			.highlightSymbol(CURSOR)
			.highlightStyle(this.highlight)
			.build();
		frame.renderStatefulWidget(table, area, state);
		return widgetRows.size();
	}

	/**
	 * Point the table at a kind's own columns. Called from {@link ResourceScreen} when a
	 * view changes, on the render thread; the headings and the widths are read from the
	 * layout, so this only has to carry the words.
	 */
	void headers(List<String> headers) {
		this.headers = (headers != null) ? List.copyOf(headers) : List.of();
	}

	/**
	 * The order on screen is NAMESPACE · NAME · STATE · the kind's own · AGE.
	 *
	 * <p>
	 * The kind's columns go <b>after</b> the verdict and before the age, which is where
	 * {@code kubectl get} puts them: the verdict stays next to the name it is about,
	 * because that is the pair an operator scans a list for, and AGE stays last, where
	 * every table in this ecosystem has it.
	 *
	 * <p>
	 * <b>Text, not widgets, and that is what makes it assertable.</b> {@code FakeBackend}
	 * records that a frame was drawn, not what it said, so a test that went through
	 * {@code Cell} could only count cells. Returning the strings and wrapping them one
	 * line later means {@code ResourceTableViewTest} can assert the exact row an operator
	 * reads — including the dashes, which are the part most easily got wrong.
	 */
	List<String> cells(ResourceRow row, ColumnLayout layout) {
		List<String> cells = new ArrayList<>(4 + layout.extras().size());
		if (layout.namespace() > 0) {
			cells.add(row.namespace());
		}
		cells.add(row.name());
		if (layout.state() > 0) {
			cells.add((row.state() != null) ? row.state() : NO_VERDICT);
		}
		for (int i = 0; i < layout.extras().size(); i++) {
			cells.add(row.cell(i));
		}
		if (layout.age() > 0) {
			cells.add(row.age());
		}
		return cells;
	}

	/** The heading row, in the same order and of the same length as {@link #cells}. */
	List<String> headings(ColumnLayout layout) {
		List<String> cells = new ArrayList<>(4 + layout.extras().size());
		if (layout.namespace() > 0) {
			cells.add("NAMESPACE");
		}
		cells.add("NAME");
		if (layout.state() > 0) {
			cells.add("STATE");
		}
		for (int i = 0; i < layout.extras().size(); i++) {
			cells.add(heading(i));
		}
		if (layout.age() > 0) {
			cells.add("AGE");
		}
		return cells;
	}

	/**
	 * A heading in the terminal's own voice — upper case, like the four the framework
	 * draws. The catalog's headings are title case because that is what a browser shows.
	 */
	private String heading(int index) {
		return (index < this.headers.size()) ? this.headers.get(index).toUpperCase(Locale.ROOT) : "";
	}

	private static List<Constraint> widths(ColumnLayout layout) {
		List<Constraint> widths = new ArrayList<>(4 + layout.extras().size());
		if (layout.namespace() > 0) {
			widths.add(Constraint.length(layout.namespace()));
		}
		widths.add(Constraint.min(layout.name()));
		if (layout.state() > 0) {
			widths.add(Constraint.length(layout.state()));
		}
		for (int extra : layout.extras()) {
			widths.add(Constraint.length(extra));
		}
		if (layout.age() > 0) {
			widths.add(Constraint.length(layout.age()));
		}
		return widths;
	}

}

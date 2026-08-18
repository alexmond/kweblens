package org.alexmond.kweblens.tui.render;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.paragraph.Paragraph;

import org.alexmond.kweblens.tui.log.LogModel;
import org.alexmond.kweblens.tui.screen.RowWindow;

/**
 * Draws the log pane — and <b>only the lines that are on screen</b>.
 *
 * <p>
 * The same rule as {@link ResourceTableView} and {@link DetailPaneView}, and this is the
 * surface where it matters most: the document is 5 000 lines by design rather than by
 * accident, it is being appended to while it is drawn, and the frame is repaid on every
 * tick for as long as the pane is open. #364 measured 10 000 rows at 0.68 ms windowed
 * against 120.8 ms naive; naive here would be 120 ms of work ten times a second, which is
 * not a slow pane but a terminal that has stopped answering the keyboard.
 *
 * <p>
 * Lines are drawn unstyled apart from the cursor, which reverses the row rather than
 * putting a marker in column zero: a log line's leading whitespace is often the only
 * indication of what nests under what in a stack trace, and shifting every line by two
 * columns would destroy it.
 */
class LogPaneView {

	private final Style plain = Style.create();

	private final Style cursor = Style.create().reversed();

	/**
	 * Render the visible slice of {@code model} into {@code area}.
	 * @return how many widget lines were constructed — the number the windowing exists to
	 * keep small, and the one a measurement reads
	 */
	int render(Frame frame, Rect area, LogModel model) {
		RowWindow window = model.window(Math.max(0, area.height()));
		List<String> visible = model.visible(window);
		List<Line> lines = new ArrayList<>(visible.size());
		for (int i = 0; i < visible.size(); i++) {
			Style style = (i == window.selectedInWindow()) ? this.cursor : this.plain;
			lines.add(Line.styled(visible.get(i), style));
		}
		frame.renderWidget(Paragraph.builder().text(Text.from(lines)).build(), area);
		return lines.size();
	}

}

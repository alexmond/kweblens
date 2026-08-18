package org.alexmond.kweblens.tui.render;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.paragraph.Paragraph;

import org.alexmond.kweblens.tui.detail.DetailLine;
import org.alexmond.kweblens.tui.detail.DetailModel;
import org.alexmond.kweblens.tui.screen.RowWindow;

/**
 * Draws the detail pane — and <b>only the lines that are on screen</b>.
 *
 * <p>
 * The same rule as {@link ResourceTableView}, and it applies here for the same measured
 * reason: a frame that builds a widget per line pays that cost again on every tick, for
 * as long as the pane is open. A table of 10 000 rows cost 0.68 ms windowed against 120.8
 * ms naive (#364), and a YAML document is exactly a long list of lines — a 3 000-line CRD
 * is an ordinary object, not a pathological one.
 *
 * <p>
 * The cursor is drawn by reversing the line rather than by a {@code >} in the margin: the
 * YAML is the one section a reader may want to copy, and a marker in column zero shifts
 * every line of it.
 */
class DetailPaneView {

	private final Style headline = Style.create().bold();

	private final Style section = Style.create().bold().cyan();

	private final Style subsection = Style.create().bold();

	private final Style heading = Style.create().cyan();

	private final Style notice = Style.create().yellow();

	private final Style plain = Style.create();

	private final Style cursor = Style.create().reversed();

	/**
	 * Render the visible slice of {@code model} into {@code area}.
	 * @return how many widget lines were constructed — the number the windowing exists to
	 * keep small, and the one a measurement reads
	 */
	int render(Frame frame, Rect area, DetailModel model) {
		RowWindow window = model.window(Math.max(0, area.height()));
		List<DetailLine> visible = model.visible(window);
		List<Line> lines = new ArrayList<>(visible.size());
		for (int i = 0; i < visible.size(); i++) {
			DetailLine line = visible.get(i);
			Style style = (i == window.selectedInWindow()) ? this.cursor : styleFor(line.tone());
			lines.add(Line.styled(line.text(), style));
		}
		frame.renderWidget(Paragraph.builder().text(Text.from(lines)).build(), area);
		return lines.size();
	}

	private Style styleFor(DetailLine.Tone tone) {
		return switch (tone) {
			case HEADLINE -> this.headline;
			case SECTION -> this.section;
			case SUBSECTION -> this.subsection;
			case HEADING -> this.heading;
			case NOTICE -> this.notice;
			case TEXT, YAML -> this.plain;
		};
	}

}

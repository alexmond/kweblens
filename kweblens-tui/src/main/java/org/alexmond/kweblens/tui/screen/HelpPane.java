package org.alexmond.kweblens.tui.screen;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.alexmond.kweblens.tui.detail.DetailLine;
import org.alexmond.kweblens.tui.detail.DetailModel;
import org.alexmond.kweblens.tui.filter.FilterHelp;

/**
 * The {@code ?} pane: <b>every key, and the filter grammar</b> — both derived, neither
 * written here.
 *
 * <h2>Why the filter grammar is on this screen at all</h2>
 *
 * {@link FilterHelp} was transcribed from {@code objectFilter.ts} (#366) so the
 * terminal's help could not drift from the terminal's parser, and then nothing in the
 * application read it (#470): the browser rendered its copy behind the search box's
 * {@code ?} button and the terminal rendered nothing at all. So a TUI operator had no
 * on-screen way to learn that a bare word is a substring, that {@code -} negates, that
 * terms AND, or that {@code /regex/}, {@code status:}, {@code label:} and {@code ~fuzzy}
 * exist. The constant's own javadoc is the argument: a filter language nobody can find is
 * a filter language nobody uses.
 *
 * <p>
 * <b>Here rather than at the prompt.</b> The other candidate was {@code ?} while the
 * {@code /} filter is open, and it cannot be built: while a prompt is open the keyboard
 * is text entry and every printable character is itself ({@link CommandLineModel}), so a
 * {@code ?} that opened a pane would be a character the filter box cannot type. The
 * {@code ?} pane is also where a reader already goes to be told what a key does, and the
 * filter is what {@code /} and the tail of a {@code :} line both take.
 *
 * <h2>Which makes it too long for a terminal, so it scrolls</h2>
 *
 * The three binding tables alone are 41 rows and were already being clipped at the bottom
 * of a 44-row screen; with the grammar under them the document is about twice a viewport.
 * It is therefore a <b>document with a cursor</b> — a {@link DetailModel}, the same type
 * the detail pane uses, which windows its lines for the renderer because #364 measured
 * what building a widget per line costs on a tick that repeats forever. The movement keys
 * scroll it and <b>any other key still closes it</b>: a help screen you have to find the
 * right key to leave is a help screen that needs help.
 *
 * <p>
 * Nothing in this class is a second copy of anything. The keys come from
 * {@link KeyMap#helpRows()}, the grammar from {@link FilterHelp}, the scroll hint from
 * the labels of the bindings that do the scrolling — and {@code ScreenHelpPaneTest} fails
 * both ways over the result: a row of either source missing from the pane, and a row on
 * the pane that its source never produced.
 */
public final class HelpPane {

	/** The keys section's heading. */
	public static final String KEYS_HEADING = "KEYS — every binding, including the ones the bar has no room for";

	/**
	 * What this session can reach — the two facts about the pane that are not the same on
	 * every cluster. Its own heading rather than two lines trailing the keys, because a
	 * section that ends at a blank line is a section whose end can be lost.
	 */
	public static final String SESSION_HEADING = "THIS SESSION — where the number keys go, and how far : reaches";

	/** The grammar section's heading. */
	public static final String FILTER_HEADING = "FILTER — what / takes, and the tail of a : line";

	/** The grammar's column headings, above the examples. */
	public static final String COLUMN_HEADING = "EXAMPLE";

	/** Where the prose under the examples starts. */
	public static final String NOTES_HEADING = "NOTES — where this is knowingly narrower than kubectl -l";

	/** What every row of either section is indented by. */
	public static final String INDENT = "  ";

	/**
	 * How wide a line may be. 132 columns is what {@code ScreenHarness} draws into and
	 * what {@code KeyMapTest} measures the hint bars against; prose is wrapped well
	 * inside it because a sentence the full width of a terminal is a sentence nobody
	 * finishes.
	 */
	public static final int WRAP = 110;

	/** Keys that move the cursor here instead of closing the pane. */
	private static final Set<KeyAction> SCROLLS = EnumSet.of(KeyAction.MOVE_DOWN, KeyAction.MOVE_UP,
			KeyAction.PAGE_DOWN, KeyAction.PAGE_UP, KeyAction.TOP, KeyAction.BOTTOM);

	/** The pane's document, or null when it is closed. */
	private DetailModel document;

	/** Whether the pane is up. */
	public boolean isOpen() {
		return this.document != null;
	}

	/**
	 * The document, or null when the pane is closed — ask {@link #isOpen()} first. It is
	 * what the renderer windows, and what a test reads to find out what is on the pane.
	 */
	public DetailModel document() {
		return this.document;
	}

	/**
	 * Open the pane, or close it if it is up.
	 * @param namespaces the number keys' namespaces, most recent first
	 * @param kinds how many kinds the command line can reach
	 * @return always true: either the pane appeared or it went away, and both owe a frame
	 */
	public boolean toggle(List<String> namespaces, int kinds) {
		this.document = (this.document != null) ? null : new DetailModel("help", lines(namespaces, kinds));
		return true;
	}

	/**
	 * One key while the pane is up.
	 * @param stroke what was pressed
	 * @param page how far a half-page key moves — the renderer's viewport
	 * @return whether a repaint is owed
	 */
	public boolean key(KeyStroke stroke, int page) {
		Optional<KeyAction> action = KeyMap.action(stroke).filter(SCROLLS::contains);
		if (action.isEmpty()) {
			this.document = null;
			return true;
		}
		return scroll(action.get(), page);
	}

	private boolean scroll(KeyAction action, int page) {
		return switch (action) {
			case MOVE_DOWN -> this.document.moveSelection(1);
			case MOVE_UP -> this.document.moveSelection(-1);
			case PAGE_DOWN -> this.document.moveSelection(page);
			case PAGE_UP -> this.document.moveSelection(-page);
			case TOP -> this.document.selectTo(0);
			case BOTTOM -> this.document.selectTo(Integer.MAX_VALUE);
			default -> false;
		};
	}

	/**
	 * The whole pane, as lines. Public because it is the content, and a test that wants
	 * to know what the pane says should not have to open one.
	 * @param namespaces the number keys' namespaces, most recent first
	 * @param kinds how many kinds the command line can reach
	 */
	public static List<DetailLine> lines(List<String> namespaces, int kinds) {
		List<DetailLine> lines = new ArrayList<>(96);
		lines.add(DetailLine.of(scrollHint(), DetailLine.Tone.HEADLINE));
		keys(lines, namespaces, kinds);
		lines.add(DetailLine.text(""));
		filter(lines);
		return lines;
	}

	/**
	 * What to press to read the rest, <b>derived from the bindings that do it</b> — the
	 * same rule as the hint bar, because a pane that names a key nothing produces is the
	 * failure {@link KeyMap} exists to prevent.
	 */
	private static String scrollHint() {
		return "HELP — " + KeyMap.label(KeyAction.MOVE_DOWN) + " and " + KeyMap.label(KeyAction.PAGE_DOWN)
				+ " scroll this pane; any other key closes it";
	}

	private static void keys(List<DetailLine> lines, List<String> namespaces, int kinds) {
		lines.add(DetailLine.of(KEYS_HEADING, DetailLine.Tone.SECTION));
		for (String row : KeyMap.helpRows()) {
			lines.add(DetailLine.text(INDENT + row));
		}
		lines.add(DetailLine.text(""));
		lines.add(DetailLine.of(SESSION_HEADING, DetailLine.Tone.HEADING));
		lines.add(DetailLine.text(INDENT + "namespaces on 1-9: " + namespaces(namespaces)));
		lines.add(DetailLine.text(INDENT + ":<kind> [namespace] [filter]  —  " + kinds + " kinds discovered"));
	}

	private static String namespaces(List<String> recent) {
		return (recent.isEmpty()) ? "none yet — they fill up as you visit them" : String.join(", ", recent);
	}

	private static void filter(List<DetailLine> lines) {
		lines.add(DetailLine.of(FILTER_HEADING, DetailLine.Tone.SECTION));
		int column = exampleColumn();
		lines.add(DetailLine.of(INDENT + pad(COLUMN_HEADING, column) + "MEANING", DetailLine.Tone.HEADING));
		for (FilterHelp.Row row : FilterHelp.ROWS) {
			lines.add(DetailLine.text(INDENT + pad(row.example(), column) + row.meaning()));
		}
		lines.add(DetailLine.text(""));
		lines.add(DetailLine.of(NOTES_HEADING, DetailLine.Tone.HEADING));
		for (String note : FilterHelp.NOTES) {
			wrap(lines, note);
		}
	}

	/**
	 * How wide the example column is: the widest example there is. Measured rather than
	 * chosen, so a longer example added to {@link FilterHelp} still lines up.
	 */
	private static int exampleColumn() {
		int widest = COLUMN_HEADING.length();
		for (FilterHelp.Row row : FilterHelp.ROWS) {
			widest = Math.max(widest, row.example().length());
		}
		return widest + 2;
	}

	private static String pad(String text, int width) {
		return text + " ".repeat(Math.max(1, width - text.length()));
	}

	/**
	 * One note, broken at spaces so no line runs past {@link #WRAP}. The continuation is
	 * indented past the bullet, so where one note ends and the next begins is visible
	 * without counting.
	 */
	private static void wrap(List<DetailLine> lines, String note) {
		StringBuilder line = new StringBuilder(WRAP);
		String prefix = INDENT + "· ";
		for (String word : note.split("\\s+")) {
			if (line.length() > 0 && prefix.length() + line.length() + 1 + word.length() > WRAP) {
				lines.add(DetailLine.text(prefix + line));
				line.setLength(0);
				prefix = INDENT + "  ";
			}
			if (line.length() > 0) {
				line.append(' ');
			}
			line.append(word);
		}
		if (line.length() > 0) {
			lines.add(DetailLine.text(prefix + line));
		}
	}

}

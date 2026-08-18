package org.alexmond.kweblens.tui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The binding table, and <b>the only source the on-screen key list is built from</b>.
 *
 * <h2>Why derivation is the requirement, not a nicety</h2>
 *
 * k9s builds its hint bar with {@code HydrateMenu(Hints())} — the menu is a projection of
 * the bindings — and it copies that shape from every TUI that has ever shipped a
 * hand-written help screen and watched it go quietly wrong. A help string is edited by
 * whoever is thinking about help; a binding is added by whoever is thinking about the
 * feature, and they are not the same afternoon. Here {@link #hints()} and
 * {@link #helpRows()} both walk {@link #BINDINGS}, {@link #action(KeyStroke)} dispatches
 * from it, and {@code KeyMapTest} fails if the rendered list and the table disagree in
 * either direction — a key missing from the bar, or a word in the bar that no key
 * produces.
 *
 * <h2>Two projections, one table</h2>
 *
 * <ul>
 * <li>{@link #hints()} — the footer. Only {@link KeyBinding#visible()} rows, because a
 * line that wraps is a line nobody reads.</li>
 * <li>{@link #helpRows()} — the {@code ?} pane. <b>Every</b> row of <b>both</b> tables,
 * so "not in the hint bar" never means "not written down anywhere".</li>
 * </ul>
 *
 * <h2>Two tables, because two screens</h2>
 *
 * The detail pane (GH#368) is a document, not a list: {@code /} searches it rather than
 * filtering rows, {@code n} and {@code N} walk the matches, and {@code :} would be a
 * command line over a table that is not on screen. So the pane has its own table,
 * {@link #PANE_BINDINGS}, projected by exactly the same three methods — the derivation
 * rule is per screen, and {@code KeyMapTest} runs both directions over both tables. What
 * would break the rule is a key handled in a branch somewhere with no row anywhere, and
 * that is still impossible.
 *
 * <h2>What this table does not cover</h2>
 *
 * The prompt. While the {@code :} command line or the {@code /} filter is open the
 * keyboard is text entry — every printable character is itself, escape abandons, enter
 * runs, tab completes — and there is no per-key behaviour to document, so there is
 * nothing for a table to hold. {@link CommandLineModel} owns those four keys and its own
 * tests assert them.
 */
public final class KeyMap {

	/**
	 * Every key this screen binds, in the order the hint bar prints them.
	 *
	 * <p>
	 * The order is deliberate: what you reach for (the command line, the filter, going in
	 * and coming back) first, then the ways to move through history, then quitting. The
	 * cursor keys are hidden not because they are unimportant but because they are the
	 * table itself — the same call k9s makes — and they are in the help pane in full.
	 */
	public static final List<KeyBinding> BINDINGS = List.of(
			KeyBinding.shown(KeyAction.COMMAND, ":", "command", KeyStroke.of(':')),
			KeyBinding.shown(KeyAction.FILTER, "/", "filter", KeyStroke.of('/')),
			KeyBinding.shown(KeyAction.DRILL_IN, "↵", "drill in", KeyStroke.key(KeyStroke.Kind.ENTER)),
			KeyBinding.shown(KeyAction.BACK, "esc/q", "back", KeyStroke.key(KeyStroke.Kind.ESCAPE), KeyStroke.of('q'),
					KeyStroke.of('Q')),
			KeyBinding.shown(KeyAction.HISTORY_PREVIOUS, "[", "prev cmd", KeyStroke.of('[')),
			KeyBinding.shown(KeyAction.HISTORY_NEXT, "]", "next cmd", KeyStroke.of(']')),
			KeyBinding.shown(KeyAction.LAST_COMMAND, "-", "last cmd", KeyStroke.of('-')),
			KeyBinding.shown(KeyAction.DETAIL, "d", "detail", KeyStroke.of('d')),
			KeyBinding.shown(KeyAction.NAMESPACE_FAVOURITE, "0-9", "namespace", digits()),
			KeyBinding.shown(KeyAction.HELP, "?", "keys", KeyStroke.of('?')),
			KeyBinding.hidden(KeyAction.MOVE_DOWN, "j/↓", "down", KeyStroke.of('j'),
					KeyStroke.key(KeyStroke.Kind.DOWN)),
			KeyBinding.hidden(KeyAction.MOVE_UP, "k/↑", "up", KeyStroke.of('k'), KeyStroke.key(KeyStroke.Kind.UP)),
			KeyBinding.hidden(KeyAction.PAGE_DOWN, "ctrl-d/pgdn", "half page down", KeyStroke.ctrl('d'),
					KeyStroke.key(KeyStroke.Kind.PAGE_DOWN)),
			KeyBinding.hidden(KeyAction.PAGE_UP, "ctrl-u/pgup", "half page up", KeyStroke.ctrl('u'),
					KeyStroke.key(KeyStroke.Kind.PAGE_UP)),
			KeyBinding.hidden(KeyAction.TOP, "g/home", "first row", KeyStroke.of('g'),
					KeyStroke.key(KeyStroke.Kind.HOME)),
			KeyBinding.hidden(KeyAction.BOTTOM, "G/end", "last row", KeyStroke.of('G'),
					KeyStroke.key(KeyStroke.Kind.END)),
			KeyBinding.hidden(KeyAction.QUIT, "ctrl-c", "quit", KeyStroke.ctrl('c')));

	/**
	 * Every key the detail pane binds (GH#368).
	 *
	 * <p>
	 * {@code esc}/{@code q} is {@link KeyAction#BACK} here as well, and it keeps the same
	 * order it keeps over a list: <b>clear the search first, close the pane second</b>.
	 * One press must undo one thing, or the operator has to guess which of two it undid.
	 */
	public static final List<KeyBinding> PANE_BINDINGS = List.of(
			KeyBinding.shown(KeyAction.SEARCH, "/", "search", KeyStroke.of('/')),
			KeyBinding.shown(KeyAction.NEXT_MATCH, "n", "next match", KeyStroke.of('n')),
			KeyBinding.shown(KeyAction.PREVIOUS_MATCH, "N", "prev match", KeyStroke.of('N')),
			KeyBinding.shown(KeyAction.BACK, "esc/q", "back", KeyStroke.key(KeyStroke.Kind.ESCAPE), KeyStroke.of('q'),
					KeyStroke.of('Q')),
			KeyBinding.hidden(KeyAction.MOVE_DOWN, "j/↓", "down a line", KeyStroke.of('j'),
					KeyStroke.key(KeyStroke.Kind.DOWN)),
			KeyBinding.hidden(KeyAction.MOVE_UP, "k/↑", "up a line", KeyStroke.of('k'),
					KeyStroke.key(KeyStroke.Kind.UP)),
			KeyBinding.hidden(KeyAction.PAGE_DOWN, "ctrl-d/pgdn", "half page down in the pane", KeyStroke.ctrl('d'),
					KeyStroke.key(KeyStroke.Kind.PAGE_DOWN)),
			KeyBinding.hidden(KeyAction.PAGE_UP, "ctrl-u/pgup", "half page up in the pane", KeyStroke.ctrl('u'),
					KeyStroke.key(KeyStroke.Kind.PAGE_UP)),
			KeyBinding.hidden(KeyAction.TOP, "g/home", "first line", KeyStroke.of('g'),
					KeyStroke.key(KeyStroke.Kind.HOME)),
			KeyBinding.hidden(KeyAction.BOTTOM, "G/end", "last line", KeyStroke.of('G'),
					KeyStroke.key(KeyStroke.Kind.END)),
			KeyBinding.hidden(KeyAction.QUIT, "ctrl-c", "quit", KeyStroke.ctrl('c')));

	/** What separates two hints on the footer. */
	static final String SEPARATOR = " · ";

	/**
	 * How a pane-only binding is written in the help, so the two tables cannot be
	 * confused.
	 */
	static final String PANE_PREFIX = "in the detail pane: ";

	private KeyMap() {
	}

	/** {@code 0}-{@code 9}, built rather than written out ten times. */
	private static KeyStroke[] digits() {
		KeyStroke[] strokes = new KeyStroke[10];
		for (int digit = 0; digit < strokes.length; digit++) {
			strokes[digit] = KeyStroke.of((char) ('0' + digit));
		}
		return strokes;
	}

	/**
	 * What {@code stroke} does, or empty when nothing does — which is a real answer: an
	 * unbound key must cost no repaint.
	 */
	public static Optional<KeyAction> action(KeyStroke stroke) {
		return action(BINDINGS, stroke);
	}

	/** What {@code stroke} does while the detail pane is up, or empty. */
	public static Optional<KeyAction> paneAction(KeyStroke stroke) {
		return action(PANE_BINDINGS, stroke);
	}

	private static Optional<KeyAction> action(List<KeyBinding> table, KeyStroke stroke) {
		for (KeyBinding binding : table) {
			if (binding.matches(stroke)) {
				return Optional.of(binding.action());
			}
		}
		return Optional.empty();
	}

	/** The hint bar, derived from the visible rows. */
	public static String hints() {
		return hints(BINDINGS);
	}

	/** The hint bar the detail pane shows, derived the same way from its own table. */
	public static String paneHints() {
		return hints(PANE_BINDINGS);
	}

	static String hints(List<KeyBinding> table) {
		StringBuilder line = new StringBuilder(96);
		for (KeyBinding binding : table) {
			if (binding.visible()) {
				if (line.length() > 0) {
					line.append(SEPARATOR);
				}
				line.append(binding.hint());
			}
		}
		return line.toString();
	}

	/**
	 * The help pane: every row of both tables, hidden ones included, so a key that exists
	 * only inside the detail pane is still written down somewhere.
	 */
	public static List<String> helpRows() {
		List<String> rows = new ArrayList<>(BINDINGS.size() + PANE_BINDINGS.size());
		for (KeyBinding binding : BINDINGS) {
			rows.add(binding.hint());
		}
		for (KeyBinding binding : PANE_BINDINGS) {
			rows.add(PANE_PREFIX + binding.hint());
		}
		return List.copyOf(rows);
	}

	/** Just the visible rows, for a test that wants the table rather than the string. */
	public static List<KeyBinding> visible() {
		return visible(BINDINGS);
	}

	static List<KeyBinding> visible(List<KeyBinding> table) {
		return table.stream().filter(KeyBinding::visible).toList();
	}

}

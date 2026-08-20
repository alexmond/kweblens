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
 * <li>{@link #helpRows()} — the {@code ?} pane. <b>Every</b> row of <b>every</b> table,
 * so "not in the hint bar" never means "not written down anywhere".</li>
 * </ul>
 *
 * <h2>Four tables, because four screens</h2>
 *
 * The detail pane (GH#368) is a document, not a list: {@code /} searches it rather than
 * filtering rows, {@code n} and {@code N} walk the matches, and {@code :} would be a
 * command line over a table that is not on screen. The log pane (GH#369) is neither — it
 * is a document that keeps growing, so {@code c} picks another container, {@code t}
 * re-opens the stream with timestamps and {@code G} means "follow the tail again". The
 * {@code ?} pane (GH#470) became a screen too when it grew a cursor, and GH#476 is what
 * leaving it out of this arrangement cost: with no table of its own it drew the
 * <em>list's</em> bar, so the footer offered {@code :}, {@code ↵} and {@code d} while the
 * only keys that did anything were the ones that scroll and the ones that close. Each
 * screen has its own table ({@link #PANE_BINDINGS}, {@link #LOG_BINDINGS},
 * {@link #HELP_BINDINGS}), projected by exactly the same three methods — the derivation
 * rule is per screen, and {@code KeyMapTest} runs both directions over all four. What
 * would break the rule is a key handled in a branch somewhere with no row anywhere, and
 * that is still impossible.
 *
 * <p>
 * <b>A table, not a filter over another table.</b> The {@code ?} pane used to dispatch
 * through {@link #BINDINGS} narrowed by a private set of scrolling actions, which is a
 * second, invisible declaration of what it binds: drop a movement row from the list's
 * table and the pane silently loses a key with nothing to fail. Its table is now both
 * what it dispatches from and what its bar is projected from, so the two cannot disagree.
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
			KeyBinding.shown(KeyAction.LOGS, "l", "logs", KeyStroke.of('l')),
			// Hidden, and it is a width decision rather than a documentation one. The bar
			// is 121 characters before this ticket and 130 with "l logs"; "p prev logs"
			// would take it to 144, past a 132-column terminal, and a line that wraps is
			// a
			// line nobody reads. The key is in the help pane, and the log pane's OWN bar
			// shows it — which is where a reader who has pressed l is looking.
			KeyBinding.hidden(KeyAction.PREVIOUS_LOGS, "p", "previous run's logs", KeyStroke.of('p')),
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

	/**
	 * Every key the log pane binds (GH#369).
	 *
	 * <p>
	 * A third table for a third screen, not a variant of the second: a log is neither a
	 * list nor a document that ends. {@code c} and {@code t} exist only here because only
	 * here is there a container to switch and a stream to re-open with timestamps, and
	 * {@code p} is bound in <em>both</em> this table and the list's — from a row it opens
	 * the terminated instance, and from inside the pane it toggles back and forth, which
	 * is one behaviour reached from two places rather than two keys.
	 *
	 * <p>
	 * {@code G} says "last line (follow)" rather than "last line": in this pane the two
	 * are the same act, because the tail is followed exactly while the cursor is on it.
	 */
	public static final List<KeyBinding> LOG_BINDINGS = List.of(
			KeyBinding.shown(KeyAction.PREVIOUS_LOGS, "p", "previous run", KeyStroke.of('p')),
			KeyBinding.shown(KeyAction.NEXT_CONTAINER, "c", "next container", KeyStroke.of('c')),
			KeyBinding.shown(KeyAction.TIMESTAMPS, "t", "timestamps", KeyStroke.of('t')),
			KeyBinding.shown(KeyAction.BACK, "esc/q", "back", KeyStroke.key(KeyStroke.Kind.ESCAPE), KeyStroke.of('q'),
					KeyStroke.of('Q')),
			KeyBinding.hidden(KeyAction.MOVE_DOWN, "j/↓", "down a line", KeyStroke.of('j'),
					KeyStroke.key(KeyStroke.Kind.DOWN)),
			KeyBinding.hidden(KeyAction.MOVE_UP, "k/↑", "up a line", KeyStroke.of('k'),
					KeyStroke.key(KeyStroke.Kind.UP)),
			KeyBinding.hidden(KeyAction.PAGE_DOWN, "ctrl-d/pgdn", "half page down in the log", KeyStroke.ctrl('d'),
					KeyStroke.key(KeyStroke.Kind.PAGE_DOWN)),
			KeyBinding.hidden(KeyAction.PAGE_UP, "ctrl-u/pgup", "half page up in the log", KeyStroke.ctrl('u'),
					KeyStroke.key(KeyStroke.Kind.PAGE_UP)),
			KeyBinding.hidden(KeyAction.TOP, "g/home", "oldest line held", KeyStroke.of('g'),
					KeyStroke.key(KeyStroke.Kind.HOME)),
			KeyBinding.hidden(KeyAction.BOTTOM, "G/end", "last line (follow)", KeyStroke.of('G'),
					KeyStroke.key(KeyStroke.Kind.END)),
			KeyBinding.hidden(KeyAction.QUIT, "ctrl-c", "quit", KeyStroke.ctrl('c')));

	/**
	 * Every key the {@code ?} pane binds (GH#476).
	 *
	 * <p>
	 * A fourth table for a fourth screen. Here the movement keys are <b>shown</b> rather
	 * than hidden, which is the opposite of the other three and is not an oversight: on a
	 * list the table itself is the evidence that a cursor moves, while this pane is twice
	 * a viewport of prose whose own "these keys scroll" headline is the first thing to
	 * scroll off the top — after which the bar is the only place left saying how to move
	 * and how to leave.
	 *
	 * <p>
	 * {@code esc}/{@code q} says <em>close</em> rather than <em>back</em> because there
	 * is no level to pop, and it carries the parenthesis because "any other key closes"
	 * is the one fact here that no binding can be: it is the <em>absence</em> of
	 * bindings, and a table cannot hold an absence. Saying it in the description of the
	 * key that does have a row is what puts it on screen for as long as the bar is.
	 *
	 * <p>
	 * There is deliberately <b>no {@code ctrl-c quit} row</b>, the one the other three
	 * tables all carry. {@link HelpPane} answers a key with a boolean and cannot quit, so
	 * {@code ctrl-c} closes the pane like every other unbound key — and a row promising a
	 * quit would be exactly the lie this class exists to prevent.
	 */
	public static final List<KeyBinding> HELP_BINDINGS = List.of(
			KeyBinding.shown(KeyAction.MOVE_DOWN, "j/↓", "down a line", KeyStroke.of('j'),
					KeyStroke.key(KeyStroke.Kind.DOWN)),
			KeyBinding.shown(KeyAction.MOVE_UP, "k/↑", "up a line", KeyStroke.of('k'),
					KeyStroke.key(KeyStroke.Kind.UP)),
			KeyBinding.shown(KeyAction.PAGE_DOWN, "ctrl-d/pgdn", "half page down", KeyStroke.ctrl('d'),
					KeyStroke.key(KeyStroke.Kind.PAGE_DOWN)),
			KeyBinding.shown(KeyAction.PAGE_UP, "ctrl-u/pgup", "half page up", KeyStroke.ctrl('u'),
					KeyStroke.key(KeyStroke.Kind.PAGE_UP)),
			KeyBinding.hidden(KeyAction.TOP, "g/home", "first line", KeyStroke.of('g'),
					KeyStroke.key(KeyStroke.Kind.HOME)),
			KeyBinding.hidden(KeyAction.BOTTOM, "G/end", "last line", KeyStroke.of('G'),
					KeyStroke.key(KeyStroke.Kind.END)),
			KeyBinding.shown(KeyAction.BACK, "esc/q", "close (as does any other key)",
					KeyStroke.key(KeyStroke.Kind.ESCAPE), KeyStroke.of('q'), KeyStroke.of('Q')));

	/** What separates two hints on the footer. */
	static final String SEPARATOR = " · ";

	/**
	 * How a pane-only binding is written in the help, so the three tables cannot be
	 * confused.
	 */
	static final String PANE_PREFIX = "in the detail pane: ";

	/** The same, for the log pane. */
	static final String LOG_PREFIX = "in the log pane: ";

	/** And for the {@code ?} pane's own keys, listed on the pane they drive. */
	static final String HELP_PREFIX = "in the help pane: ";

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

	/** What {@code stroke} does while the log pane is up, or empty. */
	public static Optional<KeyAction> logAction(KeyStroke stroke) {
		return action(LOG_BINDINGS, stroke);
	}

	/**
	 * What {@code stroke} does while the {@code ?} pane is up, or empty — and empty is
	 * the common answer there, because everything this table does not name closes it.
	 */
	public static Optional<KeyAction> helpAction(KeyStroke stroke) {
		return action(HELP_BINDINGS, stroke);
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

	/** The hint bar the log pane shows, derived the same way from its own table. */
	public static String logHints() {
		return hints(LOG_BINDINGS);
	}

	/** The hint bar the {@code ?} pane shows, derived the same way from its own table. */
	public static String helpHints() {
		return hints(HELP_BINDINGS);
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
	 * The help pane: every row of all four tables, hidden ones included, so a key that
	 * exists only inside a pane is still written down somewhere.
	 *
	 * <p>
	 * Including the {@code ?} pane's own, which is circular only in appearance. A table
	 * left out of this projection is a table with one fewer both-directions check on it,
	 * and "this one is different" is how the exceptions start.
	 */
	public static List<String> helpRows() {
		List<String> rows = new ArrayList<>(
				BINDINGS.size() + PANE_BINDINGS.size() + LOG_BINDINGS.size() + HELP_BINDINGS.size());
		for (KeyBinding binding : BINDINGS) {
			rows.add(binding.hint());
		}
		for (KeyBinding binding : PANE_BINDINGS) {
			rows.add(PANE_PREFIX + binding.hint());
		}
		for (KeyBinding binding : LOG_BINDINGS) {
			rows.add(LOG_PREFIX + binding.hint());
		}
		for (KeyBinding binding : HELP_BINDINGS) {
			rows.add(HELP_PREFIX + binding.hint());
		}
		return List.copyOf(rows);
	}

	/**
	 * How an action's keys are written on screen by {@code table}, or {@code ""} when it
	 * binds none.
	 *
	 * <p>
	 * The one way for prose elsewhere to name a key. {@link HelpPane}'s "these keys
	 * scroll" line is built from this rather than typed, because a sentence naming
	 * {@code j} is exactly as able to go stale as a hint bar naming it — and staleness in
	 * a sentence is harder to see. <b>The table is a parameter rather than a default</b>
	 * (GH#476): a sentence on the {@code ?} pane naming the list's spelling of a key
	 * would be the wrong-table mistake committed in prose, where no bar-against-table
	 * check can see it.
	 */
	public static String label(List<KeyBinding> table, KeyAction action) {
		for (KeyBinding binding : table) {
			if (binding.action() == action) {
				return binding.label();
			}
		}
		return "";
	}

	/** Just the visible rows, for a test that wants the table rather than the string. */
	public static List<KeyBinding> visible() {
		return visible(BINDINGS);
	}

	static List<KeyBinding> visible(List<KeyBinding> table) {
		return table.stream().filter(KeyBinding::visible).toList();
	}

}

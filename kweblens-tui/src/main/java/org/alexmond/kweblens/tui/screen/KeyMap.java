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
 * <li>{@link #helpRows()} — the {@code ?} pane. <b>Every</b> row, so "not in the hint
 * bar" never means "not written down anywhere".</li>
 * </ul>
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

	/** What separates two hints on the footer. */
	static final String SEPARATOR = " · ";

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
		for (KeyBinding binding : BINDINGS) {
			if (binding.matches(stroke)) {
				return Optional.of(binding.action());
			}
		}
		return Optional.empty();
	}

	/** The hint bar, derived from the visible rows. */
	public static String hints() {
		StringBuilder line = new StringBuilder(96);
		for (KeyBinding binding : BINDINGS) {
			if (binding.visible()) {
				if (line.length() > 0) {
					line.append(SEPARATOR);
				}
				line.append(binding.hint());
			}
		}
		return line.toString();
	}

	/** The help pane, derived from every row — hidden ones included. */
	public static List<String> helpRows() {
		List<String> rows = new ArrayList<>(BINDINGS.size());
		for (KeyBinding binding : BINDINGS) {
			rows.add(binding.hint());
		}
		return List.copyOf(rows);
	}

	/** Just the visible rows, for a test that wants the table rather than the string. */
	public static List<KeyBinding> visible() {
		return BINDINGS.stream().filter(KeyBinding::visible).toList();
	}

}

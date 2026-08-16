package org.alexmond.kweblens.tui.screen;

import java.util.List;

/**
 * One row of the binding table: the keys, what they do, and how they are written on
 * screen.
 *
 * <p>
 * <b>The label is not decoration.</b> It is what the hint bar and the help pane print,
 * and it is checked against {@link #strokes()} by a test — a row whose label says
 * {@code ctrl-d} while its strokes say {@code d} would be a help screen that lies, which
 * is the exact failure this whole arrangement exists to make impossible.
 *
 * @param action what the key does
 * @param label how the key is written, e.g. {@code j/↓}, {@code ctrl-d}, {@code 0-9}
 * @param description what it does, in the operator's words, lower case and short enough
 * for a hint bar
 * @param strokes every key press that triggers it
 * @param visible whether it appears in the hint bar. Invisible bindings are still in the
 * help pane — <b>invisible means "no room on one line", never "undocumented"</b>.
 */
public record KeyBinding(KeyAction action, String label, String description, List<KeyStroke> strokes, boolean visible) {

	public KeyBinding {
		strokes = List.copyOf(strokes);
	}

	/** A binding the hint bar shows. */
	public static KeyBinding shown(KeyAction action, String label, String description, KeyStroke... strokes) {
		return new KeyBinding(action, label, description, List.of(strokes), true);
	}

	/** A binding only the help pane shows. */
	public static KeyBinding hidden(KeyAction action, String label, String description, KeyStroke... strokes) {
		return new KeyBinding(action, label, description, List.of(strokes), false);
	}

	/** How this row reads on screen: {@code <label> description}. */
	public String hint() {
		return this.label + " " + this.description;
	}

	/** Does {@code stroke} trigger this binding? */
	public boolean matches(KeyStroke stroke) {
		return this.strokes.contains(stroke);
	}

}

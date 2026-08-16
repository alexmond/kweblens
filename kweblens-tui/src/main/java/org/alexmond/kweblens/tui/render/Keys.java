package org.alexmond.kweblens.tui.render;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

import org.alexmond.kweblens.tui.screen.KeyStroke;

/**
 * The one place a TamboUI {@code KeyEvent} becomes a {@link KeyStroke}.
 *
 * <p>
 * Everything downstream — the binding table, the prompt, the view stack — is then
 * testable with a plain value and no terminal, which is the same split
 * {@code ResourceModel} keeps and the reason the help bar can be asserted at all.
 *
 * <h2>Characters are checked first, and it is not arbitrary</h2>
 *
 * {@code KeyEvent} carries semantic helpers ({@code isDown}, {@code isQuit}) that consult
 * TamboUI's own {@code Bindings}, and those bindings can map a plain letter onto a
 * semantic key — {@code j} is exactly the kind of letter a vim-flavoured binding set maps
 * to "down". If the semantic helpers were consulted first, {@code j} would arrive here as
 * {@link KeyStroke.Kind#DOWN} and the binding table's own row for {@code j} would be dead
 * code that still looked correct. So: if the code is {@link KeyCode#CHAR}, it is a
 * character, and this app's table decides what it means.
 */
final class Keys {

	private Keys() {
	}

	/** {@code event} as a stroke, or null for a key this app has no vocabulary for. */
	static KeyStroke of(KeyEvent event) {
		if (event.isCtrlC()) {
			return KeyStroke.ctrl('c');
		}
		if (event.code() == KeyCode.CHAR) {
			char character = event.character();
			return (event.hasCtrl()) ? KeyStroke.ctrl(Character.toLowerCase(character)) : KeyStroke.of(character);
		}
		KeyStroke.Kind kind = kindOf(event.code());
		return (kind != null) ? KeyStroke.key(kind) : null;
	}

	private static KeyStroke.Kind kindOf(KeyCode code) {
		return switch (code) {
			case ENTER -> KeyStroke.Kind.ENTER;
			case ESCAPE -> KeyStroke.Kind.ESCAPE;
			case BACKSPACE -> KeyStroke.Kind.BACKSPACE;
			case TAB -> KeyStroke.Kind.TAB;
			case UP -> KeyStroke.Kind.UP;
			case DOWN -> KeyStroke.Kind.DOWN;
			case PAGE_UP -> KeyStroke.Kind.PAGE_UP;
			case PAGE_DOWN -> KeyStroke.Kind.PAGE_DOWN;
			case HOME -> KeyStroke.Kind.HOME;
			case END -> KeyStroke.Kind.END;
			default -> null;
		};
	}

}

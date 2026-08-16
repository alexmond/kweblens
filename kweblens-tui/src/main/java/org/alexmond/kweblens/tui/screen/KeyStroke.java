package org.alexmond.kweblens.tui.screen;

/**
 * One key press, as the model sees it — <b>no TamboUI type</b>.
 *
 * <p>
 * The renderer translates a {@code dev.tamboui.tui.event.KeyEvent} into one of these and
 * everything after that is testable without a terminal, the same split
 * {@link ResourceModel} keeps. That is not tidiness: the binding table below is the
 * single source of what every key does, and a table that could only be exercised through
 * a terminal would be a table nothing asserts.
 *
 * @param kind which key — {@link Kind#CHARACTER} when it is a printable one
 * @param character the character, for {@link Kind#CHARACTER}; otherwise {@code '\0'}
 * @param ctrl whether ctrl was held
 */
public record KeyStroke(Kind kind, char character, boolean ctrl) {

	/** A printable character with no modifier. */
	public static KeyStroke of(char character) {
		return new KeyStroke(Kind.CHARACTER, character, false);
	}

	/** A printable character with ctrl held. */
	public static KeyStroke ctrl(char character) {
		return new KeyStroke(Kind.CHARACTER, character, true);
	}

	/** A named key — arrows, enter, escape and the rest. */
	public static KeyStroke key(Kind kind) {
		return new KeyStroke(kind, '\0', false);
	}

	/** Whether this is a printable character that can go into a prompt. */
	public boolean printable() {
		return this.kind == Kind.CHARACTER && !this.ctrl && this.character >= ' ';
	}

	/** The keys a terminal can deliver that this app has any use for. */
	public enum Kind {

		/** A printable character; see {@link KeyStroke#character()}. */
		CHARACTER,

		/** Cursor up. */
		UP,

		/** Cursor down. */
		DOWN,

		/** Page up. */
		PAGE_UP,

		/** Page down. */
		PAGE_DOWN,

		/** Home. */
		HOME,

		/** End. */
		END,

		/** Enter / return. */
		ENTER,

		/** Escape. */
		ESCAPE,

		/** Backspace. */
		BACKSPACE,

		/** Tab. */
		TAB

	}

}

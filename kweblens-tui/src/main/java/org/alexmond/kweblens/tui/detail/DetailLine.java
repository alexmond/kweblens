package org.alexmond.kweblens.tui.detail;

/**
 * One line of the detail pane, already reduced to text.
 *
 * <p>
 * Text and a tone, never a widget — the same split {@code ResourceRow} keeps for the
 * table, and for the same reason: the interesting part is what the line <em>says</em>,
 * and a test that went through TamboUI's {@code Line} could only count lines. Every
 * sentence the pane makes about a relation is asserted as a string.
 *
 * @param text the line as an operator reads it
 * @param tone what it is, so the renderer can style it — never what colour it is
 */
public record DetailLine(String text, DetailLine.Tone tone) {

	/** A plain line. */
	public static DetailLine text(String text) {
		return new DetailLine(text, Tone.TEXT);
	}

	/** A line of a section's table, or a column heading. */
	public static DetailLine of(String text, Tone tone) {
		return new DetailLine(text, tone);
	}

	/** What a line is. */
	public enum Tone {

		/** The verdict and the object it is about — the pane's first line. */
		HEADLINE,

		/** A top-level section: RELATIONS, EVENTS, YAML. */
		SECTION,

		/** One relation's name and count. */
		SUBSECTION,

		/** A table's column headings. */
		HEADING,

		/**
		 * Something the pane has to say about a section rather than something in it: a
		 * relation that was truncated, refused or failed. <b>Never drawn instead of
		 * nothing</b> — that is the whole reason this tone exists.
		 */
		NOTICE,

		/** Ordinary content. */
		TEXT,

		/** A line of the object's YAML, drawn unindented so it can be read as YAML. */
		YAML

	}

}

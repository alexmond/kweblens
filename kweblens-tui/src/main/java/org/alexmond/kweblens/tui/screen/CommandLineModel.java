package org.alexmond.kweblens.tui.screen;

import java.util.List;

/**
 * The prompt: what has been typed, what is being suggested, and which prompt it is.
 *
 * <p>
 * Two prompts share this because they differ in one thing — {@code :} names a kind and
 * can therefore complete, {@code /} is a filter query and cannot. Everything else (a
 * buffer, a backspace, escape abandons, enter runs) is identical, and two classes would
 * be two places to fix the same off-by-one.
 *
 * <h2>Completion comes from the index, not from a list</h2>
 *
 * The completer is a function handed in at construction, and the only implementation asks
 * {@code KindIndex}, which was built from the cluster's own discovery. That is the
 * difference the ticket is about: a CRD installed on this cluster completes here without
 * anybody adding a line to a file, and there is no fixed list to substitute — this class
 * has no way to name a kind by itself.
 *
 * <h2>No TamboUI, no cluster</h2>
 *
 * A keystroke goes in and a string comes out, so every rule below is asserted without a
 * terminal.
 */
public class CommandLineModel {

	/** How many candidates the prompt is willing to show under the line. */
	static final int CANDIDATES = 6;

	private final Completer completer;

	/**
	 * What has been typed. A plain {@link String} rather than a {@link StringBuilder}: a
	 * prompt is a handful of characters and lives as long as the screen does, so the
	 * mutable buffer would be a field that only ever grows.
	 */
	private String buffer = "";

	private Mode mode = Mode.OFF;

	public CommandLineModel(Completer completer) {
		this.completer = completer;
	}

	/** Open a prompt with {@code initial} already in it. Returns true — it repaints. */
	public boolean open(Mode prompt, String initial) {
		this.mode = prompt;
		this.buffer = (initial != null) ? initial : "";
		return true;
	}

	/** Put the prompt away, buffer and all. */
	public void close() {
		this.mode = Mode.OFF;
		this.buffer = "";
	}

	/** Whether a prompt is taking the keyboard. */
	public boolean open() {
		return this.mode != Mode.OFF;
	}

	public Mode mode() {
		return this.mode;
	}

	/** What has been typed. */
	public String text() {
		return this.buffer;
	}

	/** Replace what has been typed — how walking history refills the prompt. */
	public void text(String replacement) {
		this.buffer = (replacement != null) ? replacement : "";
	}

	/** Add one character. */
	public boolean type(char character) {
		this.buffer += character;
		return true;
	}

	/** Remove the last character; false when there was nothing to remove. */
	public boolean backspace() {
		if (this.buffer.isEmpty()) {
			return false;
		}
		this.buffer = this.buffer.substring(0, this.buffer.length() - 1);
		return true;
	}

	/**
	 * Take the inline suggestion — tab. Only the kind token completes, and only while it
	 * is still the whole line: once a namespace or a filter has been typed the first
	 * token is settled and completing it would rewrite something behind the cursor.
	 */
	public boolean complete() {
		if (this.mode != Mode.COMMAND) {
			return false;
		}
		String suggestion = suggestion();
		if (suggestion.isEmpty()) {
			return false;
		}
		text(suggestion);
		return true;
	}

	/**
	 * The inline completion of the kind token, or {@code ""} when there is none — k9s's
	 * {@code :pods} growing into a real kind as you type.
	 */
	public String suggestion() {
		if (this.mode != Mode.COMMAND) {
			return "";
		}
		String typed = text();
		if (typed.isBlank() || typed.indexOf(' ') >= 0) {
			return "";
		}
		List<String> candidates = candidates();
		for (String candidate : candidates) {
			if (!candidate.equals(typed)) {
				return candidate;
			}
		}
		return "";
	}

	/** The candidates under the line: what the cluster offers for what has been typed. */
	public List<String> candidates() {
		if (this.mode != Mode.COMMAND) {
			return List.of();
		}
		String typed = text();
		if (typed.indexOf(' ') >= 0) {
			return List.of();
		}
		return this.completer.candidates(typed, CANDIDATES);
	}

	/**
	 * The prompt as it is drawn: its sigil, the text, and the completion greyed after it.
	 */
	public String rendered() {
		if (this.mode == Mode.OFF) {
			return "";
		}
		String typed = text();
		String suggestion = suggestion();
		String tail = (suggestion.startsWith(typed)) ? suggestion.substring(typed.length()) : "";
		return this.mode.sigil() + typed + tail;
	}

	/** Which prompt is open. */
	public enum Mode {

		/** No prompt; the keyboard belongs to the table. */
		OFF(""),

		/** {@code :} — name a kind. */
		COMMAND(":"),

		/** {@code /} — narrow the rows. */
		FILTER("/"),

		/**
		 * {@code /} inside the detail pane — find a line. Same sigil as {@link #FILTER}
		 * because it is the same key and the same gesture; a different <em>mode</em>
		 * because a pane has no rows to narrow, and {@code submit()} has to know which of
		 * the two the enter belongs to.
		 */
		SEARCH("/");

		private final String sigil;

		Mode(String sigil) {
			this.sigil = sigil;
		}

		/** The character the prompt is drawn with. */
		public String sigil() {
			return this.sigil;
		}

	}

	/**
	 * Where candidates come from. One implementation, over the cluster's discovered kinds
	 * — the seam exists so this class can be tested with a handful of words rather than
	 * an API server.
	 */
	@FunctionalInterface
	public interface Completer {

		/** At most {@code limit} aliases continuing {@code prefix}, alphabetically. */
		List<String> candidates(String prefix, int limit);

	}

}

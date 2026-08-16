package org.alexmond.kweblens.tui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The commands that have been run, walked with {@code [} and {@code ]}, with {@code -}
 * for the one before this one.
 *
 * <h2>Temporal, and the stack is spatial</h2>
 *
 * This is the half of k9s's navigation that is easiest to collapse into the other one and
 * should not be. {@link ViewStack} is <b>where you are</b>: entering a Deployment pushes
 * a level and {@code esc} pops it, so the stack answers "how do I get back out". This is
 * <b>what you have asked for</b>: {@code :pods}, {@code :deploy}, {@code :svc}, in the
 * order they were typed, so {@code [} answers "what did I type before". Neither is
 * derivable from the other — a drill-down pushes the stack and records no command, and
 * walking history moves no stack level. {@code CommandHistoryTest} asserts exactly that,
 * because two navigations that are quietly one navigation is how {@code [} ends up
 * popping a view.
 *
 * <h2>The cursor</h2>
 *
 * {@link #previous()} walks backwards from the end and {@link #next()} forwards. Running
 * a command puts the cursor back at the end — the same rule a shell keeps, and the reason
 * {@code [ [ ]} lands where you expect rather than somewhere that depends on what you did
 * three commands ago.
 */
public class CommandHistory {

	/** How many commands are kept. Older ones fall off the front. */
	static final int LIMIT = 50;

	private final List<String> entries = new ArrayList<>();

	/**
	 * Where {@code [} / {@code ]} are pointing. Equal to {@code entries.size()} when not
	 * browsing, which is the "past the end" position a fresh prompt starts at.
	 */
	private int cursor;

	/**
	 * Remember a command that was actually run.
	 *
	 * <p>
	 * A repeat of the command already at the end is not appended: {@code :pods} twice is
	 * one thing to walk back through, and a history full of the same word is a history
	 * you cannot use. It still resets the cursor, because you have just run something.
	 */
	public void record(String command) {
		String trimmed = (command != null) ? command.strip() : "";
		if (trimmed.isEmpty()) {
			return;
		}
		if (this.entries.isEmpty() || !this.entries.get(this.entries.size() - 1).equals(trimmed)) {
			this.entries.add(trimmed);
			if (this.entries.size() > LIMIT) {
				this.entries.remove(0);
			}
		}
		this.cursor = this.entries.size();
	}

	/** The command before the cursor, moving the cursor onto it. {@code [}. */
	public Optional<String> previous() {
		if (this.cursor <= 0) {
			return Optional.empty();
		}
		this.cursor--;
		return Optional.of(this.entries.get(this.cursor));
	}

	/**
	 * The command after the cursor, moving the cursor onto it. {@code ]}. Walking off the
	 * end yields empty and leaves the cursor past the end, which is where a fresh prompt
	 * belongs.
	 */
	public Optional<String> next() {
		if (this.cursor >= this.entries.size() - 1) {
			this.cursor = this.entries.size();
			return Optional.empty();
		}
		this.cursor++;
		return Optional.of(this.entries.get(this.cursor));
	}

	/**
	 * The command run before the current one — k9s's {@code -}, and it toggles.
	 *
	 * <p>
	 * Pressing it twice returns you to where you were, because it <em>records</em> what
	 * it hands back: the entry it returns becomes the newest, so the previous newest is
	 * now the one before. That is the behaviour of a "last view" key, and it is why this
	 * is not simply {@link #previous()}.
	 */
	public Optional<String> last() {
		if (this.entries.size() < 2) {
			return Optional.empty();
		}
		String previous = this.entries.get(this.entries.size() - 2);
		record(previous);
		return Optional.of(previous);
	}

	/** Every command remembered, oldest first. */
	public List<String> entries() {
		return List.copyOf(this.entries);
	}

	/** How many commands are remembered. */
	public int size() {
		return this.entries.size();
	}

	/**
	 * Where {@code [} / {@code ]} are pointing, as an index into {@link #entries()};
	 * {@code size()} means "not browsing". Exists so a test can prove that pushing and
	 * popping views leaves it alone.
	 */
	public int cursor() {
		return this.cursor;
	}

}

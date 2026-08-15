package org.alexmond.kweblens.tui;

/**
 * Whether this build of the TUI can change the cluster — shown, not assumed.
 *
 * <p>
 * k9s puts {@code [R]} or {@code [RW]} in its header for exactly this reason: an operator
 * looking at a terminal full of cluster objects has no other way to tell whether the next
 * keystroke can delete one. v1 of {@code kweblens-tui} is {@link #READ_ONLY} — there is
 * no delete, scale, restart, cordon, drain, edit or apply anywhere in the module, and
 * {@link org.alexmond.kweblens.tui.data.ClusterDataSource} has no method that could
 * perform one.
 *
 * <p>
 * {@link #READ_WRITE} exists here with nothing producing it on purpose. A badge with only
 * one possible value is decoration; a badge with two is a claim, and it is the claim that
 * makes the reader trust it when it says {@code [R]}. It also fixes where the answer will
 * be changed: adding writes means changing {@link #current()} and the guard behind it,
 * not inventing a third vocabulary somewhere else. Writes, when they come, arrive through
 * kweblens's suggest → approve → apply → audit discipline — not k9s's typed-phrase
 * dialog.
 */
public enum TuiPosture {

	/** No write of any kind is possible in this build. */
	READ_ONLY("[R]", "read-only"),

	/** Writes are possible. Nothing returns this yet. */
	READ_WRITE("[RW]", "read-write");

	private final String badge;

	private final String description;

	TuiPosture(String badge, String description) {
		this.badge = badge;
		this.description = description;
	}

	/** The short header marker, e.g. {@code [R]}. */
	public String badge() {
		return this.badge;
	}

	/** The same thing in words, for a status line or a {@code --help} epilogue. */
	public String description() {
		return this.description;
	}

	/**
	 * The posture of this build. Always {@link #READ_ONLY} in v1, and it is a method
	 * rather than a constant so that the one place it stops being true is a diff a
	 * reviewer sees.
	 */
	public static TuiPosture current() {
		return READ_ONLY;
	}

}

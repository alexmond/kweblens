package org.alexmond.kweblens.tui.render;

/**
 * Whether there is anywhere to draw, and the words for when there is not.
 *
 * <h2>Why a tty is not the same question as an area</h2>
 *
 * {@link TuiScreen} already refuses a stdout that is not a terminal. A terminal that
 * <em>is</em> a tty and reports <b>0 rows × 0 columns</b> passes that check and then
 * draws nothing: the renderer is handed a zero area, every widget clips to it, and the
 * log stays empty because root is {@code WARN}. From the outside that is
 * indistinguishable from a hang, and it cost GH#426 a cycle.
 *
 * <p>
 * It is not a hypothetical shape. A bare {@code pty.fork()} leaves the pty's window size
 * unset and nobody fills it in, which is the obvious way to script a terminal app.
 * Measured against the shipped exec jar on 2026-08-17: <b>38 bytes</b> written at 0×0
 * (the mode query, the alternate screen, hide cursor) and an empty log, against <b>1 376
 * bytes</b> and a full table after one {@code TIOCSWINSZ} to 44×132 — one variable, on
 * the same pipe pair.
 *
 * <h2>Zero is not the only degenerate case</h2>
 *
 * The predicate is {@code width <= 0 || height <= 0}, not {@code width == 0 && height ==
 * 0}: a terminal that reports 132 columns and 0 rows has exactly as much room to draw in,
 * and the sentence has to be able to say so. That is also why the numbers are printed
 * rather than the words "zero by zero".
 */
final class DrawableArea {

	private DrawableArea() {
	}

	/** Whether an area of {@code width} × {@code height} has room for a single cell. */
	static boolean empty(int width, int height) {
		return width <= 0 || height <= 0;
	}

	/** The measurement, in the order a terminal is usually described. */
	static String describe(int width, int height) {
		return width + "×" + height + " (columns × rows)";
	}

	/**
	 * The refusal: name the measurement, then name the fix — the same shape as the
	 * no-terminal refusal it sits beside.
	 */
	static String refusal(int width, int height) {
		return "No room to draw — this terminal reports " + describe(width, height)
				+ ", measured where the renderer reads it. A pty opened without a window size reports exactly "
				+ "this: set one (TIOCSWINSZ), resize the terminal, or use --once for a plain listing.";
	}

}

package org.alexmond.kweblens.tui.render;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.Renderer;
import lombok.extern.slf4j.Slf4j;

/**
 * Says, in the one place a message can still be seen, that the screen has stopped having
 * anywhere to draw.
 *
 * <h2>Why this is not the startup check again</h2>
 *
 * {@link TuiScreen} refuses a terminal that reports no area <em>before</em> the screen
 * goes up, and prints the reason on the terminal. A degenerate size can also arrive
 * later, from a {@code SIGWINCH} — and by then that route is closed: the alternate screen
 * is up, it is 0 cells wide, and {@code TerminalOutputGuard} owns {@code System.out}. So
 * the notice goes to the log file, at {@code WARN}, which is exactly the level this
 * module's root logger passes. <b>An empty {@code kweblens-tui.log} is what a healthy run
 * leaves</b>, so a line in it is the signal.
 *
 * <h2>And it does not quit</h2>
 *
 * A terminal that stops reporting an area usually starts again — measured on a bare pty:
 * one {@code TIOCSWINSZ} back to 44×132 and the same process drew a full table. Tearing a
 * live session down for a transient would cost the operator a session to save them a
 * blank moment. So this observes and reports; the screen redraws by itself.
 *
 * <h2>Where the size comes from</h2>
 *
 * {@link Frame#area()}, i.e. what the renderer was actually handed — never {@code $LINES}
 * / {@code $COLUMNS}, and not a second read of the backend either. This is also the only
 * way a resize is observable at all here: {@code TuiRunner.run} consumes
 * {@code ResizeEvent} itself and it never reaches an {@code EventHandler} (see
 * {@link ResourceScreen}).
 *
 * <p>
 * One {@code int} pair compared per frame, and nothing allocated unless the answer
 * changed — a per-frame cost has to stay a per-frame cost (GH#364).
 */
@Slf4j
final class DrawableAreaWatch implements Renderer {

	private final Renderer delegate;

	/** Render thread only, which is the one thread {@link #render} runs on. */
	private boolean blank;

	DrawableAreaWatch(Renderer delegate) {
		this.delegate = delegate;
	}

	@Override
	public void render(Frame frame) {
		Rect area = frame.area();
		observe(area.width(), area.height());
		this.delegate.render(frame);
	}

	private void observe(int width, int height) {
		boolean nowhere = DrawableArea.empty(width, height);
		if (nowhere == this.blank) {
			return;
		}
		this.blank = nowhere;
		if (nowhere) {
			log.warn(
					"The terminal now reports {}, so the screen has nowhere to draw and is blank. "
							+ "Nothing is wrong with the cluster; resize the terminal and the screen redraws itself.",
					DrawableArea.describe(width, height));
		}
		else {
			log.warn("The terminal reports {} again; the screen is drawing.", DrawableArea.describe(width, height));
		}
	}

	/** Whether the last frame had nowhere to draw. */
	boolean blank() {
		return this.blank;
	}

}

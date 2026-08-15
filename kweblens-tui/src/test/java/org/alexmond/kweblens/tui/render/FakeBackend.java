package org.alexmond.kweblens.tui.render;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Position;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Backend;

/**
 * A terminal that is not a terminal: a scripted keyboard and a size the test chooses.
 *
 * <p>
 * This is what lets the <em>real</em> TamboUI event loop — the real
 * {@code TuiRunner.run}, the real input reader thread, the real {@code pollEvent}
 * priority rule — run inside a JUnit test with no pty. That matters because the failure
 * #364 exists to prevent is a property of that loop, not of our code: a redraw posted per
 * event is a {@code UiRunnable}, which {@code pollEvent} treats as FIFO with keystrokes.
 * A test against our own fake loop could not see it.
 *
 * <p>
 * <b>Not 80×24.</b> Every test drives this at a size it asked for, because 80×24 is what
 * a size probe that never actually asked returns, and it cannot tell "the layout worked"
 * from "the layout was never recomputed".
 */
public class FakeBackend implements Backend {

	private final BlockingQueue<Integer> input = new LinkedBlockingQueue<>();

	private final AtomicReference<Size> size;

	private final AtomicInteger draws = new AtomicInteger();

	private final AtomicInteger sizeReads = new AtomicInteger();

	private volatile Runnable onResize = () -> {
	};

	public FakeBackend(int width, int height) {
		this.size = new AtomicReference<>(new Size(width, height));
	}

	/**
	 * Type into the terminal. One code point per call, as a byte stream would deliver.
	 */
	public void type(char c) {
		this.input.add((int) c);
	}

	/**
	 * Change the terminal size and fire the backend's resize callback, exactly as a
	 * {@code SIGWINCH} would. TamboUI's scheduler turns that callback into a
	 * {@code ResizeEvent}; {@code Terminal.draw} re-reads {@link #size()} on every frame,
	 * which is what actually re-lays-out.
	 */
	public void resizeTo(int width, int height) {
		this.size.set(new Size(width, height));
		this.onResize.run();
	}

	/** How many times a non-empty diff was pushed at the terminal. */
	public int draws() {
		return this.draws.get();
	}

	/** How many times the size was asked for — the probe's own positive control. */
	public int sizeReads() {
		return this.sizeReads.get();
	}

	@Override
	public void draw(DiffResult diff) {
		this.draws.incrementAndGet();
	}

	@Override
	public void flush() {
	}

	@Override
	public void clear() {
	}

	@Override
	public Size size() {
		this.sizeReads.incrementAndGet();
		return this.size.get();
	}

	@Override
	public void showCursor() {
	}

	@Override
	public void hideCursor() {
	}

	@Override
	public Position getCursorPosition() {
		return Position.ORIGIN;
	}

	@Override
	public void setCursorPosition(Position position) {
	}

	@Override
	public void enterAlternateScreen() {
	}

	@Override
	public void leaveAlternateScreen() {
	}

	@Override
	public void enableRawMode() {
	}

	@Override
	public void disableRawMode() {
	}

	@Override
	public void onResize(Runnable callback) {
		this.onResize = callback;
	}

	@Override
	public int read(int timeoutMillis) throws IOException {
		try {
			Integer next = this.input.poll(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
			// -2 is TamboUI's "timed out, no input" — see EventParser.readEvent.
			return (next != null) ? next : -2;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException(ex);
		}
	}

	@Override
	public int peek(int timeoutMillis) {
		Integer next = this.input.peek();
		return (next != null) ? next : -2;
	}

	@Override
	public void close() {
	}

}

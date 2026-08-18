package org.alexmond.kweblens.tui.log;

import java.util.ArrayList;
import java.util.List;

/**
 * The lines the pane is holding: a fixed number of them, oldest dropped, indexed from the
 * oldest still held.
 *
 * <h2>Why a ring and not a list</h2>
 *
 * A pod logging indefinitely must not grow the terminal's heap, which is done-when 4 of
 * GH#369 — and "trim an {@code ArrayList} from the front" is O(n) per line, paid on every
 * line forever, which is the same shape of cost {@code RowWindow} exists to keep off the
 * frame. A ring is O(1) to append and O(1) to index, so both halves of the pane — the
 * append and the windowed read — are flat in how long the pod has been running.
 *
 * <p>
 * Indexing matters as much as the bound: the renderer may build widgets only for the
 * visible slice (#364 measured 10 000 rows at 0.68 ms windowed against 120.8 ms naive,
 * paid again on every tick), and a slice needs random access to its first line. A deque
 * would give the bound and not that.
 *
 * <h2>What "index" means, and why it moves</h2>
 *
 * Index 0 is the <em>oldest line still held</em>, not the first line the container ever
 * emitted. So when the ring is full every append shifts every index down by one, and a
 * cursor parked on a line has to be moved with it or the reader's place drifts by itself.
 * {@link #discarded()} is what makes that correctable rather than invisible.
 *
 * <p>
 * Not thread-safe, and not required to be: it is written and read on the render thread
 * only. {@link LogBuffer} is the seam that crosses threads.
 */
public class LogRing {

	/**
	 * How many lines the pane keeps. k9s's default, and the same reasoning: enough that a
	 * reader scrolling back through a crash finds the start of it, few enough that the
	 * bound is a bound.
	 */
	public static final int DEFAULT_CAPACITY = 5_000;

	private final String[] lines;

	/** Index in {@link #lines} of the oldest line held. */
	private int head;

	private int size;

	private long discarded;

	public LogRing() {
		this(DEFAULT_CAPACITY);
	}

	public LogRing(int capacity) {
		this.lines = new String[Math.max(1, capacity)];
	}

	/** Append one line, dropping the oldest when the ring is full. */
	public void add(String line) {
		if (line == null) {
			return;
		}
		if (this.size < this.lines.length) {
			this.lines[(this.head + this.size) % this.lines.length] = line;
			this.size++;
			return;
		}
		this.lines[this.head] = line;
		this.head = (this.head + 1) % this.lines.length;
		this.discarded++;
	}

	/** Append several, in order. */
	public void addAll(List<String> batch) {
		for (String line : batch) {
			add(line);
		}
	}

	/** How many lines are held. Never more than {@link #capacity()}. */
	public int size() {
		return this.size;
	}

	/** The bound. */
	public int capacity() {
		return this.lines.length;
	}

	/**
	 * How many lines have been dropped off the front, ever — the number a cursor has to
	 * be moved by to stay on the line it was on, and the number that says the bound is
	 * doing something.
	 */
	public long discarded() {
		return this.discarded;
	}

	/** One line, counting from the oldest held. */
	public String get(int index) {
		if (index < 0 || index >= this.size) {
			throw new IndexOutOfBoundsException("no line at " + index + "; " + this.size + " held");
		}
		return this.lines[(this.head + index) % this.lines.length];
	}

	/**
	 * The lines in {@code [from, to)}, clamped to what is held — a copy, and deliberately
	 * so: the caller is a frame asking for a viewport's worth (tens of lines), not for
	 * the ring.
	 */
	public List<String> slice(int from, int to) {
		int first = Math.max(0, from);
		int last = Math.min(this.size, to);
		if (first >= last) {
			return List.of();
		}
		List<String> window = new ArrayList<>(last - first);
		for (int i = first; i < last; i++) {
			window.add(get(i));
		}
		return window;
	}

}

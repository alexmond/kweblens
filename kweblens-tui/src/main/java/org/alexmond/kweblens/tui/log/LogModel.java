package org.alexmond.kweblens.tui.log;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.alexmond.kweblens.tui.screen.RowWindow;

/**
 * The log pane's document, its cursor, and whether any of it is still arriving — <b>no
 * TamboUI type</b>, exactly as {@code DetailModel} keeps none for the detail pane and
 * {@code ResourceModel} keeps none for the table.
 *
 * <h2>Buffered and flushed on a period, never repainted per line</h2>
 *
 * {@link #append} is called from the reader thread and only reaches {@link LogBuffer};
 * {@link #flush()} is called from the render thread once per tick and is the only thing
 * that moves a line into the document. So a pod that emits a thousand lines between two
 * ticks costs one repaint, not a thousand — and in a terminal that is not a frame-rate
 * question but a keyboard one, because a redraw posted per line is FIFO with keystrokes
 * (see {@link LogBuffer}).
 *
 * <h2>Windowed, because a log is the longest list in the product</h2>
 *
 * A frame builds widgets for {@link #window(int)} only. #364 measured the table at 10 000
 * rows: 0.68 ms windowed against 120.8 ms naive, and the property that matters is that
 * windowed is <em>flat</em> in document size. A log buffer is 5 000 lines by design and a
 * terminal repaints on a tick forever, so the naive cost would be paid ten times a second
 * for as long as the pane is open.
 *
 * <h2>Following, and the cursor that has to move with the bound</h2>
 *
 * The pane follows the tail while the cursor is on the last line, which is what makes
 * {@code G} "resume following" without a second piece of state to disagree with the
 * cursor. When it is <em>not</em> following, an append that pushes a line off the front
 * of the ring shifts every index down by one, so the cursor is moved by the same amount —
 * without that, a reader who scrolled back to a stack trace watches it drift upward on
 * its own while they read it.
 *
 * <h2>A pane that has stopped being live must say so</h2>
 *
 * The same rule as the table's (GH#413): a log stream ends when the container exits, when
 * a proxy times out, or when the API server restarts, and a pane that is not told goes on
 * drawing a photograph with no indication that nothing more is coming. {@link #ended} is
 * how the reader thread reports it and {@link #status()} leads with it.
 */
public final class LogModel {

	/** How many lines of history a follow asks the API server for on open. k9s's tail. */
	public static final int TAIL_LINES = 100;

	private final String namespace;

	private final String pod;

	/** The container being read, or {@code ""} for the pod's default one. */
	private final String container;

	/** Every container of the pod, so the pane can say "2 of 3" and cycle. */
	private final List<String> containers;

	private final boolean previous;

	private final boolean timestamps;

	private final LogBuffer buffer;

	private final LogRing ring;

	/**
	 * Why nothing more is arriving, or null while it still is. Written by the reader
	 * thread, read by the render thread.
	 */
	private final AtomicReference<String> ended = new AtomicReference<>();

	private int selected;

	private int offset;

	/**
	 * Whether the cursor is pinned to the tail. True on open, false the moment the reader
	 * scrolls up, true again on {@code G}.
	 */
	private boolean following = true;

	private LogModel(String namespace, String pod, String container, List<String> containers, boolean previous,
			boolean timestamps, LogBuffer buffer, LogRing ring) {
		this.namespace = namespace;
		this.pod = pod;
		this.container = (container != null) ? container : "";
		this.containers = List.copyOf(containers);
		this.previous = previous;
		this.timestamps = timestamps;
		this.buffer = buffer;
		this.ring = ring;
	}

	/** A live follow, empty until the reader thread starts appending. */
	public static LogModel following(String namespace, String pod, String container, List<String> containers,
			boolean timestamps) {
		return new LogModel(namespace, pod, container, containers, false, timestamps, new LogBuffer(), new LogRing());
	}

	/**
	 * A previous run: a snapshot, seeded and finished. It is <b>not</b> a follow that
	 * happens to have ended — there is no stream, nothing will arrive, and the title says
	 * so rather than the footer having to explain a NOT LIVE that was never live.
	 * @param text the terminated instance's log, or {@code ""} when the reason says there
	 * is none
	 * @param reason why there is nothing to show, or null when there is
	 */
	public static LogModel previous(String namespace, String pod, String container, List<String> containers,
			String text, String reason) {
		LogModel model = new LogModel(namespace, pod, container, containers, true, false, new LogBuffer(),
				new LogRing());
		if (reason != null) {
			// The one place an empty document is allowed, and it is not empty: a pane of
			// no lines would assert that the previous run logged nothing, which is a
			// finding. See PreviousLog.
			model.ring.add(reason);
		}
		else {
			model.ring.addAll(List.of(text.split("\n", -1)));
		}
		model.selectTo(0);
		return model;
	}

	/**
	 * Absorb one line. <b>Reader thread</b>, and it reaches the buffer and nothing else —
	 * no document mutation, no cursor move, no repaint.
	 */
	public void append(String line) {
		this.buffer.offer(line);
	}

	/**
	 * Say that nothing more will arrive, and why. <b>Reader thread.</b> First reason
	 * wins: a stream that fails and then reports end-of-input has failed, and the second
	 * sentence would overwrite the informative one.
	 */
	public void ended(String reason) {
		this.ended.compareAndSet(null, reason);
	}

	/**
	 * Move everything buffered since the last tick into the document, as one batch.
	 * <b>Render thread.</b>
	 * @return whether anything changed and a repaint is therefore owed
	 */
	public boolean flush() {
		List<String> batch = this.buffer.drain();
		if (batch.isEmpty()) {
			return false;
		}
		long before = this.ring.discarded();
		this.ring.addAll(batch);
		long shifted = this.ring.discarded() - before;
		if (this.following) {
			this.selected = Math.max(0, this.ring.size() - 1);
		}
		else if (shifted > 0) {
			// The lines the reader is looking at moved down by exactly this many. Not
			// doing it is a document that scrolls itself while somebody reads it.
			this.selected = Math.max(0, this.selected - (int) Math.min(shifted, this.selected));
		}
		return true;
	}

	/**
	 * Move the cursor, clamped. Returns whether it moved, i.e. whether a repaint is owed.
	 */
	public boolean moveSelection(int delta) {
		return selectTo(this.selected + delta);
	}

	/**
	 * Put the cursor on an absolute line, clamped — and set following from where it
	 * lands, so {@code G} resumes the tail and {@code k} leaves it without a second flag
	 * to disagree with the cursor.
	 */
	public boolean selectTo(int index) {
		int next = clamp(index);
		boolean wasFollowing = this.following;
		this.following = (this.ring.size() == 0) || (next >= this.ring.size() - 1);
		if (next == this.selected) {
			return wasFollowing != this.following;
		}
		this.selected = next;
		return true;
	}

	private int clamp(int index) {
		if (this.ring.size() == 0) {
			return 0;
		}
		return Math.max(0, Math.min(index, this.ring.size() - 1));
	}

	/**
	 * The lines a frame of {@code height} rows may build widgets for, scrolling the
	 * minimum needed to keep the cursor on screen. Mutates the offset for the same reason
	 * {@code DetailModel.window} does: scrolling <em>is</em> the decision about what is
	 * visible, and computing it in the renderer while storing it elsewhere is how the two
	 * disagree.
	 */
	public RowWindow window(int height) {
		if (height <= 0 || this.ring.size() == 0) {
			this.offset = 0;
			return RowWindow.EMPTY;
		}
		if (this.selected < this.offset) {
			this.offset = this.selected;
		}
		else if (this.selected >= this.offset + height) {
			this.offset = this.selected - height + 1;
		}
		int maxOffset = Math.max(0, this.ring.size() - height);
		this.offset = Math.max(0, Math.min(this.offset, maxOffset));
		int size = Math.min(height, this.ring.size() - this.offset);
		return new RowWindow(this.offset, size, this.selected - this.offset);
	}

	/** The lines in {@code window}. */
	public List<String> visible(RowWindow window) {
		return this.ring.slice(window.first(), window.end());
	}

	/** How many lines are held. Bounded by {@link LogRing#capacity()}. */
	public int size() {
		return this.ring.size();
	}

	/** Where the cursor is, zero-based. */
	public int selectedIndex() {
		return this.selected;
	}

	/** Whether the cursor is pinned to the tail. */
	public boolean following() {
		return this.following;
	}

	/**
	 * Whether lines are still expected. False for a snapshot and for a stream that ended.
	 */
	public boolean live() {
		return !this.previous && this.ended.get() == null;
	}

	/** Why nothing more is arriving, or {@code ""} while it still is. */
	public String endedReason() {
		String reason = this.ended.get();
		return (reason != null) ? reason : "";
	}

	/** Whether this is a previous run rather than a follow. */
	public boolean isPrevious() {
		return this.previous;
	}

	/** Whether the follow carries Kubernetes' timestamps. */
	public boolean hasTimestamps() {
		return this.timestamps;
	}

	/** The container being read, or {@code ""} for the pod's default one. */
	public String container() {
		return this.container;
	}

	/** Every container of the pod. */
	public List<String> containers() {
		return this.containers;
	}

	public String namespace() {
		return this.namespace;
	}

	public String pod() {
		return this.pod;
	}

	/** The lines waiting for the next tick — what the flush test reads. */
	public int buffered() {
		return this.buffer.buffered();
	}

	/** How many lines have ever arrived. */
	public long received() {
		return this.buffer.received();
	}

	/**
	 * How many were dropped before they could be shown, because the pod outran the tick.
	 */
	public long droppedByBuffer() {
		return this.buffer.dropped();
	}

	/** How many have fallen off the front of the document, because it is bounded. */
	public long discarded() {
		return this.ring.discarded();
	}

	/**
	 * The frame title: what is being read, from which container, and — for a previous run
	 * — that it is one. <b>"previous run" is in the title and not the footer</b>, because
	 * a reader who mistakes a terminated instance's log for the live one draws exactly
	 * the wrong conclusion about a crashloop.
	 */
	public String title() {
		StringBuilder line = new StringBuilder(96);
		line.append((this.previous) ? "previous run (snapshot)" : "logs");
		line.append("   │   ").append(this.namespace).append('/').append(this.pod);
		line.append("   │   ").append(containerLabel());
		if (this.timestamps) {
			line.append("   │   timestamps");
		}
		return line.toString();
	}

	private String containerLabel() {
		String name = (!this.container.isEmpty()) ? this.container : "default container";
		int position = this.containers.indexOf(this.container);
		if (this.containers.size() > 1 && position >= 0) {
			return name + " (" + (position + 1) + " of " + this.containers.size() + ")";
		}
		return name;
	}

	/**
	 * The bottom line: <b>anything that has stopped being true first</b>, then where the
	 * cursor is.
	 *
	 * <p>
	 * The order is the header's rule (GH#413) applied to this pane. A line count is a
	 * claim about a log that is still arriving; once it has stopped, it is a claim about
	 * a moment that has passed, and a notice appended at the end is also the first thing
	 * a narrow terminal drops.
	 */
	public String status() {
		StringBuilder line = new StringBuilder(128);
		String reason = endedReason();
		if (!this.previous && !reason.isEmpty()) {
			line.append("NOT LIVE — ").append(reason).append("   │   ");
		}
		line.append((this.ring.size() == 0) ? 0 : this.selected + 1).append('/').append(this.ring.size());
		if (this.ring.discarded() > 0) {
			line.append(" (oldest ")
				.append(this.ring.discarded())
				.append(" dropped, buffer holds ")
				.append(this.ring.capacity())
				.append(')');
		}
		if (this.buffer.dropped() > 0) {
			line.append("   │   ").append(this.buffer.dropped()).append(" lines arrived faster than the screen ticks");
		}
		if (this.live()) {
			line.append("   │   ").append((this.following) ? "following" : "paused (G follows again)");
		}
		return line.toString();
	}

}

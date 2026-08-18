package org.alexmond.kweblens.tui.log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Lines a container has emitted since the last flush — <b>the log pane's half of this
 * project's coalescing rule.</b>
 *
 * <h2>Why a buffer at all</h2>
 *
 * The rule is the same one {@code WatchCoalescer} states for a list, and in a terminal it
 * is not a frame-rate question. A redraw posted per line is a TamboUI {@code UiRunnable},
 * and {@code pollEvent} treats those as <b>FIFO with keystrokes</b> — the GH#361 spike
 * measured a per-event control at 223 renders in four seconds that <em>never processed
 * the keystroke at all</em>, so the app would not quit. A chatty pod emits far more lines
 * per second than a namespace emits watch events, which makes this the surface where that
 * failure is easiest to reach. {@link #offer} therefore touches a deque and returns;
 * nothing here posts anything anywhere.
 *
 * <p>
 * k9s reaches the same design from the other end — it is the one place k9s is
 * event-driven, and it appends to a buffer and notifies the view when the buffer
 * overflows or a 50 ms timer fires. Here the period is the screen's own tick, because a
 * second timer would be a second answer to "when may the terminal repaint" and the tick
 * is already the one the keyboard budget was computed against (see {@code TickRate}).
 *
 * <h2>Why the buffer is bounded too</h2>
 *
 * Not only the document. A pod that logs faster than the screen ticks would otherwise
 * grow this deque without limit <em>between two ticks</em>, so bounding the ring alone
 * would leave the heap unbounded on exactly the input the bound exists for. The oldest
 * pending line is dropped and counted: a line thrown away between arriving and being
 * shown is a fact about the run, and {@link #dropped()} is how a reader can be told
 * rather than left to wonder.
 *
 * <h2>Threading</h2>
 *
 * {@link #offer} runs on the reader thread {@code LogFollower} owns; {@link #drain} runs
 * on the render thread. The lock is what makes that safe and it is the only thing either
 * side takes.
 */
public class LogBuffer {

	/**
	 * How many lines may wait for a tick. Generous next to a 100 ms tick — a pod would
	 * have to emit 20 000 lines per second to reach it — and finite, which is the point.
	 */
	public static final int DEFAULT_CAPACITY = 2_000;

	private final ReentrantLock lock = new ReentrantLock();

	private final Deque<String> pending = new ArrayDeque<>();

	private final int capacity;

	private final AtomicLong received = new AtomicLong();

	private final AtomicLong dropped = new AtomicLong();

	public LogBuffer() {
		this(DEFAULT_CAPACITY);
	}

	public LogBuffer(int capacity) {
		this.capacity = Math.max(1, capacity);
	}

	/**
	 * Absorb one line. Called from the reader thread, and it must stay cheap: no
	 * projection, no repaint, no UI work posted anywhere.
	 */
	public void offer(String line) {
		if (line == null) {
			return;
		}
		this.lock.lock();
		try {
			this.received.incrementAndGet();
			this.pending.addLast(line);
			while (this.pending.size() > this.capacity) {
				this.pending.removeFirst();
				this.dropped.incrementAndGet();
			}
		}
		finally {
			this.lock.unlock();
		}
	}

	/**
	 * Everything buffered since the last drain, oldest first, and empty when nothing
	 * arrived — which is the common case and costs one deque check.
	 */
	public List<String> drain() {
		this.lock.lock();
		try {
			if (this.pending.isEmpty()) {
				return List.of();
			}
			List<String> batch = new ArrayList<>(this.pending);
			this.pending.clear();
			return batch;
		}
		finally {
			this.lock.unlock();
		}
	}

	/** How many lines are waiting for a tick right now. */
	public int buffered() {
		this.lock.lock();
		try {
			return this.pending.size();
		}
		finally {
			this.lock.unlock();
		}
	}

	/** How many lines have ever been offered. */
	public long received() {
		return this.received.get();
	}

	/**
	 * How many were dropped before they were ever shown, because the pod outran the tick.
	 * Counted rather than assumed: a bound that never bites and a bound that is not there
	 * look identical from the outside.
	 */
	public long dropped() {
		return this.dropped.get();
	}

	/** The bound, so a caller can say what it is rather than repeat the number. */
	public int capacity() {
		return this.capacity;
	}

}

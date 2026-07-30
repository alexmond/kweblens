package org.alexmond.kweblens.web.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.alexmond.kweblens.log.LogSource;

/**
 * Decides which log sources need a reader attached, as the set changes underneath a live
 * stream.
 *
 * <p>
 * This exists as its own class because it is the part with the interesting rules and the
 * part worth testing: everything around it is threads, blocking reads and SSE plumbing.
 * Here there is no clock and no cluster — just "given what the workload resolves to now,
 * and what is already being followed, what should start?" It IS reached from several
 * threads (the refresh loop asks; each reader reports back on its own thread), so every
 * method takes the lock; the critical sections are map lookups and cannot contend
 * meaningfully.
 *
 * <p>
 * The rules it encodes:
 * <ul>
 * <li><b>Never attach twice.</b> Identity is the whole {@link LogSource}, uid included,
 * so a recreated pod is correctly a different source while a pod that merely stopped
 * producing output is not.</li>
 * <li><b>Retry, because a new pod is not immediately readable.</b> During a rollout the
 * pod exists seconds before its container can be read, so the first attach usually fails
 * and must be retried rather than treated as broken.</li>
 * <li><b>Bounded.</b> Retries stop after {@link #MAX_ATTEMPTS}, so a pod that never
 * starts — or a container that has terminated for good — cannot make an open view retry
 * forever.</li>
 * <li><b>Quiet about failures that are expected to resolve.</b> A source that joined
 * mid-stream gets a grace period before its failure is reported, because reporting every
 * new replica as broken for the two seconds before it starts is noise during exactly the
 * event this feature exists for.</li>
 * </ul>
 */
final class SourceTracker {

	/**
	 * Attach attempts per source before giving up. Covers a slow image pull at the
	 * refresh cadence without letting a permanently unstartable pod, or a container whose
	 * log has closed for good, retry for as long as the view stays open.
	 */
	static final int MAX_ATTEMPTS = 20;

	/**
	 * Failed attempts a late-joining source may accumulate before its error is reported.
	 * Zero for the sources present at connect time: those the user asked for explicitly,
	 * and a prompt error beats a silent empty pane.
	 */
	static final int GRACE_ATTEMPTS = 2;

	private final Map<LogSource, Entry> entries = new LinkedHashMap<>();

	private final Lock lock = new ReentrantLock();

	private boolean started;

	/**
	 * The sources that should have a reader started now — new ones, plus previously
	 * attached ones whose reader has since ended and which the workload still resolves
	 * to.
	 *
	 * <p>
	 * Re-attaching an ended reader is what makes the stream self-healing: an API-server
	 * hiccup that closes one watch would otherwise silently stop following that pod for
	 * the rest of the session, with no visible symptom beyond output that quietly stops.
	 */
	List<LogSource> needingAttach(List<LogSource> resolved) {
		this.lock.lock();
		try {
			List<LogSource> out = new ArrayList<>();
			for (LogSource source : resolved) {
				Entry entry = this.entries.get(source);
				if (entry == null) {
					// Sources seen before the first refresh are the ones the user asked
					// for;
					// anything appearing later joined a stream already in flight.
					entry = new Entry(this.started);
					this.entries.put(source, entry);
				}
				if (!entry.attached && entry.attempts < MAX_ATTEMPTS) {
					entry.attempts++;
					out.add(source);
				}
			}
			this.started = true;
			return out;
		}
		finally {
			this.lock.unlock();
		}
	}

	/**
	 * Whether this source still owes the client its tail snapshot.
	 *
	 * <p>
	 * Separate from {@link #snapshotDone} so the snapshot is only marked taken once it
	 * has actually succeeded: a pod that was not readable yet must still get its history
	 * when a later attempt works, and that history — the container's own startup output —
	 * is the most useful part of a replica created by a rollout.
	 */
	boolean needsSnapshot(LogSource source) {
		this.lock.lock();
		try {
			Entry entry = this.entries.get(source);
			return entry != null && !entry.snapshotted;
		}
		finally {
			this.lock.unlock();
		}
	}

	/** The tail has been sent; never send it again, or the user sees it twice. */
	void snapshotDone(LogSource source) {
		this.lock.lock();
		try {
			Entry entry = this.entries.get(source);
			if (entry != null) {
				entry.snapshotted = true;
			}
		}
		finally {
			this.lock.unlock();
		}
	}

	/**
	 * A reader is now running. Returns true when this source had previously reported a
	 * failure, so the caller can tell the client it recovered rather than leaving a stale
	 * error on a source that is streaming again.
	 */
	boolean attached(LogSource source) {
		this.lock.lock();
		try {
			Entry entry = this.entries.get(source);
			if (entry == null) {
				return false;
			}
			entry.attached = true;
			boolean wasReported = entry.reported;
			entry.reported = false;
			return wasReported;
		}
		finally {
			this.lock.unlock();
		}
	}

	/** A reader ended — cleanly or not. It becomes eligible to re-attach. */
	void detached(LogSource source) {
		this.lock.lock();
		try {
			Entry entry = this.entries.get(source);
			if (entry != null) {
				entry.attached = false;
			}
		}
		finally {
			this.lock.unlock();
		}
	}

	/**
	 * Attaching failed. Returns true when the failure should be shown to the user — once
	 * per run of failures, and only after the grace period for a source that joined
	 * mid-stream.
	 */
	boolean failed(LogSource source) {
		this.lock.lock();
		try {
			Entry entry = this.entries.get(source);
			if (entry == null) {
				return false;
			}
			entry.attached = false;
			if (entry.reported || entry.attempts <= (entry.lateJoiner ? GRACE_ATTEMPTS : 0)) {
				return false;
			}
			entry.reported = true;
			return true;
		}
		finally {
			this.lock.unlock();
		}
	}

	// Nested types last (Checkstyle InnerTypeLast).

	private static final class Entry {

		private final boolean lateJoiner;

		private int attempts;

		private boolean attached;

		private boolean snapshotted;

		private boolean reported;

		Entry(boolean lateJoiner) {
			this.lateJoiner = lateJoiner;
		}

	}

}

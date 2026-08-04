package org.alexmond.kweblens.web.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import io.fabric8.kubernetes.client.dsl.LogWatch;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.alexmond.kweblens.log.LogLine;
import org.alexmond.kweblens.log.LogRefusal;
import org.alexmond.kweblens.log.LogService;
import org.alexmond.kweblens.log.LogSource;

/**
 * Follows many pod/container logs at once and multiplexes them into a single SSE response
 * — the {@code stern} model, so a whole Deployment's output can be correlated in one
 * view.
 *
 * <p>
 * Two problems are solved by the same piece of machinery. {@link SseEmitter} is
 * <b>not</b> safe for concurrent {@code send()} from several threads, and independent log
 * streams arrive interleaved by network timing rather than by when lines were written. So
 * every reader thread only ever <em>enqueues</em>, and a single dispatcher thread drains
 * the queue in small time windows, sorts each window by the Kubernetes-supplied
 * timestamp, and is the sole caller of {@code send()} for log lines. Out-of-band events
 * (source changes, per-source errors) are sent from other threads, so {@link #send} takes
 * a lock — enqueuing those would put them behind the reordering window and let an error
 * arrive after output it explains.
 *
 * <p>
 * Ordering is therefore <b>best-effort within a window</b>, not globally correct: a line
 * delayed by more than {@link #WINDOW_MS} still arrives late, and lines without a
 * timestamp keep arrival order. This is the same trade-off {@code stern} makes, and the
 * endpoint reports it to the client rather than implying total ordering.
 *
 * <p>
 * <b>The source set is live.</b> It is re-resolved every {@link #REFRESH_MS} rather than
 * fixed at connect, because a rollout replaces every pod behind a workload: without this
 * the old replicas go quiet and the new ones never join, precisely when the logs matter
 * most. Polling is chosen over watching pods deliberately — a new pod is not readable the
 * moment it appears, so a watch would still need the retry loop that polling already is,
 * and would additionally own watch re-establishment. See {@link SourceTracker} for the
 * attach rules.
 */
@Slf4j
final class MultiLogStream {

	/**
	 * How long the dispatcher accumulates lines before sorting and flushing a batch. Long
	 * enough to reorder across sources under normal jitter, short enough to still feel
	 * live.
	 */
	private static final long WINDOW_MS = 120;

	/**
	 * How often the source set is re-resolved. A compromise: fast enough that a new
	 * replica's logs join within a few seconds of it starting, slow enough that an open
	 * view is not a meaningful load on the API server.
	 */
	private static final long REFRESH_MS = 4000;

	/** Batch cap, so one very chatty source cannot starve the flush loop. */
	private static final int MAX_BATCH = 2000;

	private final LogService logs;

	private final SseEmitter emitter;

	private final String clusterId;

	private final ExecutorService executor;

	private final Supplier<List<LogSource>> resolve;

	private final int maxSources;

	private final SourceTracker tracker = new SourceTracker();

	private final BlockingQueue<LogLine> queue = new LinkedBlockingQueue<>();

	private final List<LogWatch> watches = new CopyOnWriteArrayList<>();

	private final AtomicBoolean closed = new AtomicBoolean();

	private final Lock sendLock = new ReentrantLock();

	/**
	 * The last set announced to the client, so a refresh only re-announces on a change.
	 */
	private final AtomicReference<List<String>> announced = new AtomicReference<>(List.of());

	MultiLogStream(LogService logs, SseEmitter emitter, String clusterId, ExecutorService executor,
			Supplier<List<LogSource>> resolve, int maxSources) {
		this.logs = logs;
		this.emitter = emitter;
		this.clusterId = clusterId;
		this.executor = executor;
		this.resolve = resolve;
		this.maxSources = maxSources;
	}

	/**
	 * Resolve and start following, then keep the source set current until the client goes
	 * away.
	 */
	void start(int tailLines) {
		// The first batch snapshots on THIS thread, before anything is streaming. History
		// has
		// to precede live output, and this is the only moment where that is free: once
		// readers are running, a source's tail could otherwise land after another
		// source's
		// fresh lines. Sources that join later snapshot on their own thread, where the
		// same
		// ordering holds per source because their watch is not open yet.
		refresh(tailLines, true);
		this.executor.execute(this::dispatch);
		this.executor.execute(() -> refreshLoop(tailLines));
	}

	/**
	 * Close every watch and complete the response. Idempotent, and deliberately
	 * <b>re-runnable</b> rather than "runs once": a source whose watch was still being
	 * opened when the client left registers itself after the first sweep, and an
	 * early-return guard would leave exactly that watch — the one nobody is reading —
	 * open for the life of the process. Closing an already-empty list costs nothing.
	 */
	void close() {
		this.closed.set(true);
		for (LogWatch watch : this.watches) {
			// logs.release, not watch.close(): the latter does not stop a watchLog()
			// follow, so every reader below would stay parked on a connection this
			// method claims to have closed. See LogService.release.
			this.logs.release(watch);
		}
		this.watches.clear();
	}

	private void refreshLoop(int tailLines) {
		try {
			while (!this.closed.get()) {
				TimeUnit.MILLISECONDS.sleep(REFRESH_MS);
				if (!this.closed.get()) {
					refresh(tailLines, false);
				}
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Re-resolve the workload's sources and start readers for anything not already being
	 * followed.
	 *
	 * <p>
	 * A resolve failure is logged and skipped rather than ending the stream: the workload
	 * may be momentarily unreadable (or briefly absent mid-rollout), and tearing down
	 * every healthy reader over one failed list would lose the output the user is
	 * watching.
	 */
	private void refresh(int tailLines, boolean snapshotInline) {
		List<LogSource> resolved;
		try {
			resolved = this.resolve.get();
		}
		catch (RuntimeException ex) {
			log.debug("Refreshing log sources failed, keeping the current set: {}", ex.getMessage());
			return;
		}
		int totalFound = resolved.size();
		boolean truncated = totalFound > this.maxSources;
		List<LogSource> capped = truncated ? resolved.subList(0, this.maxSources) : resolved;
		announce(capped, truncated, totalFound);
		for (LogSource source : this.tracker.needingAttach(capped)) {
			if (snapshotInline) {
				snapshotOnce(source, tailLines);
			}
			this.executor.execute(() -> attach(source, tailLines));
		}
	}

	/**
	 * Tell the client the set, but only when it has actually changed — the client
	 * rebuilds its legend on this event, and re-sending an identical list every few
	 * seconds would churn it for nothing.
	 */
	private void announce(List<LogSource> sources, boolean truncated, int totalFound) {
		List<String> ids = sources.stream().map(LogSource::id).toList();
		if (ids.equals(this.announced.getAndSet(ids))) {
			return;
		}
		send("sources",
				Map.of("sources", ids, "truncated", truncated, "totalFound", totalFound, "ordering", "best-effort"));
	}

	/**
	 * Open one source's watch and read it to exhaustion. Every exit path hands the source
	 * back to the tracker, so a reader that ends can be re-attached by a later refresh.
	 */
	private void attach(LogSource source, int tailLines) {
		// Before the watch, not after: the watch starts at the present, so taking the
		// tail
		// first is what keeps a source's history ahead of its live output.
		snapshotOnce(source, tailLines);
		LogWatch watch;
		try {
			watch = this.logs.watchWithTimestamps(this.clusterId, source.namespace(), source.pod(), source.container());
		}
		catch (RuntimeException ex) {
			// Expected for a pod that exists but whose container has not started; the
			// tracker decides whether this failure is worth telling the user about.
			if (this.tracker.failed(source)) {
				sendSourceError(source, ex);
			}
			return;
		}
		this.watches.add(watch);
		if (this.closed.get()) {
			// The client left while this watch was opening, so close() has already
			// walked the list and will not walk it again on its own. Without this the
			// read below would block on a quiet source forever, holding the one log
			// connection nobody can ever cancel.
			close();
			return;
		}
		if (this.tracker.attached(source)) {
			send("source-recovered", Map.of("source", source.id()));
		}
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(watch.getOutput(), StandardCharsets.UTF_8))) {
			String raw;
			boolean first = true;
			while ((raw = reader.readLine()) != null && !this.closed.get()) {
				// A refused watch does not throw: the API server's error body arrives as
				// the
				// first thing on the stream. Enqueuing it would attribute a Kubernetes
				// error
				// object to the user's application — and it happens for every pod a
				// rollout
				// creates, since a container is briefly unreadable after it appears.
				if (first && LogRefusal.isRefusal(raw)) {
					if (this.tracker.failed(source)) {
						sendSourceError(source, LogRefusal.message(raw));
					}
					return;
				}
				first = false;
				this.queue.offer(parse(source, raw));
			}
		}
		catch (IOException | RuntimeException ex) {
			if (!this.closed.get()) {
				log.debug("Log stream for {} ended: {}", source.id(), ex.getMessage());
			}
		}
		finally {
			// The reader owns the release on every exit route it has; close() owns it for
			// the routes it does not (a client that left while this was parked).
			// Releasing
			// twice is harmless, releasing never is the leak.
			this.logs.release(watch);
			this.watches.remove(watch);
			this.tracker.detached(source);
		}
	}

	/**
	 * The pre-follow snapshot, sent once per source the first time it is attached.
	 *
	 * <p>
	 * It matters most for sources that join mid-stream: the live watch starts from the
	 * present, so without this a replica created during a rollout would show nothing of
	 * its own startup — which is the part that says whether the new version came up.
	 */
	private void snapshotOnce(LogSource source, int tailLines) {
		if (tailLines <= 0 || !this.tracker.needsSnapshot(source)) {
			return;
		}
		try {
			String log = this.logs.tailWithTimestamps(this.clusterId, source.namespace(), source.pod(),
					source.container(), tailLines);
			if (log == null || log.isEmpty()) {
				// An empty log is a snapshot that succeeded and found nothing; marking it
				// done stops every later re-attach from asking again.
				this.tracker.snapshotDone(source);
				return;
			}
			List<LogLine> parsed = new ArrayList<>();
			for (String raw : log.split("\n")) {
				if (!raw.isEmpty()) {
					parsed.add(parse(source, raw));
				}
			}
			parsed.sort(byTimestamp());
			parsed.forEach((line) -> send("line", line));
			this.tracker.snapshotDone(source);
		}
		catch (RuntimeException ex) {
			// Not reported here: a pod that is not readable yet fails this too, and the
			// watch
			// attach immediately after decides — through the tracker — whether the user
			// should be told. Reporting from both would double up on every new replica.
			log.debug("Tail snapshot for {} failed: {}", source.id(), ex.getMessage());
		}
	}

	/**
	 * The sole sender of live lines. Waits for the first line, gathers whatever else
	 * arrives inside the window, sorts that batch by timestamp, and flushes it.
	 */
	private void dispatch() {
		try {
			while (!this.closed.get()) {
				LogLine first = this.queue.poll(1, TimeUnit.SECONDS);
				if (first == null) {
					continue;
				}
				List<LogLine> batch = new ArrayList<>();
				batch.add(first);
				long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WINDOW_MS);
				while (batch.size() < MAX_BATCH) {
					long remaining = deadline - System.nanoTime();
					if (remaining <= 0) {
						break;
					}
					LogLine next = this.queue.poll(remaining, TimeUnit.NANOSECONDS);
					if (next == null) {
						break;
					}
					batch.add(next);
				}
				batch.sort(byTimestamp());
				for (LogLine line : batch) {
					send("line", line);
				}
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Split the {@code usingTimestamps()} prefix off a line. Kubernetes prefixes each
	 * line with an RFC3339 instant and a space; the instant becomes the sort key and is
	 * removed from the text so clients render clean output. An unparsable prefix means
	 * the line is kept verbatim with a null timestamp rather than being mangled.
	 */
	private LogLine parse(LogSource source, String raw) {
		int space = raw.indexOf(' ');
		Instant timestamp = (space > 0) ? parseInstant(raw.substring(0, space)) : null;
		return (timestamp != null) ? new LogLine(source.id(), timestamp, raw.substring(space + 1))
				: new LogLine(source.id(), null, raw);
	}

	/**
	 * Null (rather than throwing) when the token is not an instant — see {@link #parse}.
	 */
	private Instant parseInstant(String token) {
		try {
			return Instant.parse(token);
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

	/**
	 * Nulls last, so untimestamped lines keep arrival position instead of jumping to the
	 * top.
	 */
	private Comparator<LogLine> byTimestamp() {
		return Comparator.comparing(LogLine::timestamp, Comparator.nullsLast(Comparator.naturalOrder()));
	}

	private void sendSourceError(LogSource source, RuntimeException ex) {
		sendSourceError(source, String.valueOf(ex.getMessage()));
	}

	private void sendSourceError(LogSource source, String message) {
		log.debug("Cannot follow {}: {}", source.id(), message);
		send("source-error", Map.of("source", source.id(), "message", message));
	}

	/**
	 * Guarded because readers, the dispatcher and the refresh loop all send: lines come
	 * from the dispatcher alone, but source changes, errors and recoveries do not, and
	 * concurrent {@code SseEmitter.send()} corrupts the response.
	 */
	private void send(String name, Object payload) {
		if (this.closed.get()) {
			return;
		}
		this.sendLock.lock();
		try {
			this.emitter.send(SseEmitter.event().name(name).data(payload, MediaType.APPLICATION_JSON));
		}
		catch (IOException | IllegalStateException ex) {
			log.debug("Multi-log SSE send failed ({}); closing", ex.getMessage());
			close();
			SseKeepAlive.completeQuietly(this.emitter, ex);
		}
		finally {
			this.sendLock.unlock();
		}
	}

}

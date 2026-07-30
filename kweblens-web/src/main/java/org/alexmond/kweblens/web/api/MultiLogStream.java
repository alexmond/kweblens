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

import io.fabric8.kubernetes.client.dsl.LogWatch;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.alexmond.kweblens.log.LogLine;
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
 * timestamp, and is the sole caller of {@code send()}.
 *
 * <p>
 * Ordering is therefore <b>best-effort within a window</b>, not globally correct: a line
 * delayed by more than {@link #WINDOW_MS} still arrives late, and lines without a
 * timestamp keep arrival order. This is the same trade-off {@code stern} makes, and the
 * endpoint reports it to the client rather than implying total ordering.
 */
@Slf4j
final class MultiLogStream {

	/**
	 * How long the dispatcher accumulates lines before sorting and flushing a batch. Long
	 * enough to reorder across sources under normal jitter, short enough to still feel
	 * live.
	 */
	private static final long WINDOW_MS = 120;

	/** Batch cap, so one very chatty source cannot starve the flush loop. */
	private static final int MAX_BATCH = 2000;

	private final LogService logs;

	private final SseEmitter emitter;

	private final String clusterId;

	private final ExecutorService executor;

	private final BlockingQueue<LogLine> queue = new LinkedBlockingQueue<>();

	private final List<LogWatch> watches = new CopyOnWriteArrayList<>();

	private final AtomicBoolean closed = new AtomicBoolean();

	MultiLogStream(LogService logs, SseEmitter emitter, String clusterId, ExecutorService executor) {
		this.logs = logs;
		this.emitter = emitter;
		this.clusterId = clusterId;
		this.executor = executor;
	}

	/**
	 * Start following every source: announce them, send each one's tail snapshot, then
	 * run one reader per source plus the single dispatcher.
	 */
	void start(List<LogSource> sources, int tailLines, boolean truncated, int totalFound) {
		send("sources", Map.of("sources", sources.stream().map(LogSource::id).toList(), "truncated", truncated,
				"totalFound", totalFound, "ordering", "best-effort"));
		for (LogSource source : sources) {
			snapshot(source, tailLines);
		}
		for (LogSource source : sources) {
			this.executor.execute(() -> follow(source));
		}
		this.executor.execute(this::dispatch);
	}

	/**
	 * Close every watch and complete the response. Idempotent — several paths can call
	 * it.
	 */
	void close() {
		if (!this.closed.compareAndSet(false, true)) {
			return;
		}
		for (LogWatch watch : this.watches) {
			try {
				watch.close();
			}
			catch (RuntimeException ex) {
				log.debug("Closing log watch failed: {}", ex.getMessage());
			}
		}
		this.watches.clear();
	}

	/**
	 * The pre-follow snapshot. Sent directly (not queued) so history always precedes live
	 * output, and per-source so one unreadable container degrades to an error event
	 * instead of failing the whole view.
	 */
	private void snapshot(LogSource source, int tailLines) {
		if (tailLines <= 0) {
			return;
		}
		try {
			String log = this.logs.tailWithTimestamps(this.clusterId, source.namespace(), source.pod(),
					source.container(), tailLines);
			if (log == null || log.isEmpty()) {
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
		}
		catch (RuntimeException ex) {
			sendSourceError(source, ex);
		}
	}

	/** One blocking reader per source; every line is enqueued, never sent from here. */
	private void follow(LogSource source) {
		LogWatch watch;
		try {
			watch = this.logs.watchWithTimestamps(this.clusterId, source.namespace(), source.pod(), source.container());
		}
		catch (RuntimeException ex) {
			sendSourceError(source, ex);
			return;
		}
		this.watches.add(watch);
		try (watch;
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(watch.getOutput(), StandardCharsets.UTF_8))) {
			String raw;
			while ((raw = reader.readLine()) != null && !this.closed.get()) {
				this.queue.offer(parse(source, raw));
			}
		}
		catch (IOException | RuntimeException ex) {
			if (!this.closed.get()) {
				log.debug("Log stream for {} ended: {}", source.id(), ex.getMessage());
			}
		}
	}

	/**
	 * The sole sender. Waits for the first line, gathers whatever else arrives inside the
	 * window, sorts that batch by timestamp, and flushes it.
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
		log.debug("Cannot follow {}: {}", source.id(), ex.getMessage());
		send("source-error", Map.of("source", source.id(), "message", String.valueOf(ex.getMessage())));
	}

	private void send(String name, Object payload) {
		if (this.closed.get()) {
			return;
		}
		try {
			this.emitter.send(SseEmitter.event().name(name).data(payload, MediaType.APPLICATION_JSON));
		}
		catch (IOException | IllegalStateException ex) {
			log.debug("Multi-log SSE send failed ({}); closing", ex.getMessage());
			close();
			this.emitter.completeWithError(ex);
		}
	}

}

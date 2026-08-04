package org.alexmond.kweblens.web.api;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import lombok.extern.slf4j.Slf4j;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * A periodic SSE comment on a stream, so that a subscriber who has gone away is noticed.
 *
 * <p>
 * This exists because of a measured leak, not a theoretical one. An {@link SseEmitter}
 * learns that its client is gone only from a <b>failed write</b>; nothing polls the
 * socket. The resource-list watch writes only when the watched kind produces an event, so
 * on a quiet kind a departed subscriber is never noticed and the API-server watch behind
 * it stays open. Measured against a live cluster: one client watching {@code pods}
 * disconnected, and the JVM still held that watch <b>five minutes later</b>; the same
 * test against {@code events} — a kind that ticks — released it in ~35 s, which is the
 * same mechanism seen from the other side. Walking twenty kinds in one tab, closing each
 * list before opening the next, left <b>22 open API-server watches for one live
 * subscriber</b>, 18 of them still open three minutes on.
 *
 * <p>
 * So the fan-out that matters was never "one watch per subscriber" — it was one watch per
 * list view <em>ever opened</em>. A heartbeat turns it back into the first, which is the
 * number the ceiling in {@code docs/design/watch-fanout.md} is written against.
 *
 * <p>
 * A comment (<code>:keepalive</code>) rather than an event: {@code EventSource} discards
 * comment lines, so no client changes and no risk of a stray frame reaching a table.
 */
@Slf4j
final class SseKeepAlive {

	/**
	 * A small shared daemon pool. These tasks do one tiny write each, so a thread per
	 * stream would cost far more than the streams do — but not ONE thread either.
	 *
	 * <p>
	 * {@link SseEmitter#send} can block: it writes through to the response, and a client
	 * that has stopped reading fills the socket buffer. On a single thread that stalls
	 * every other stream's probe, which would let the very leak this class exists to
	 * close reopen for everyone else while one subscriber is slow. Two threads do not
	 * make that impossible, they make it need two simultaneously-stuck clients — which,
	 * for the single operator ADR-001 describes, is the right place to stop paying.
	 */
	private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2, (runnable) -> {
		Thread thread = new Thread(runnable, "sse-keepalive");
		thread.setDaemon(true);
		return thread;
	});

	/**
	 * How often to probe. Short enough that a closed tab does not hold an API-server
	 * watch for minutes, long enough to be invisible: with one operator and a handful of
	 * tabs this is a few bytes a minute.
	 */
	static final Duration PERIOD = Duration.ofSeconds(15);

	private SseKeepAlive() {
	}

	/**
	 * Probe {@code emitter} until it completes. Attach this <b>after</b> the emitter's
	 * completion callbacks are registered — the probe completes the emitter when the
	 * write fails, and that is what releases the watch.
	 */
	static void attach(SseEmitter emitter) {
		attach(emitter, PERIOD);
	}

	/** As {@link #attach(SseEmitter)}, with the probe interval given — for tests. */
	static void attach(SseEmitter emitter, Duration period) {
		AtomicReference<ScheduledFuture<?>> handle = new AtomicReference<>();
		// The probe cancels ITSELF on the first failure rather than waiting for the
		// container to run the completion callback below. Those callbacks are the normal
		// path, but they are the container's to fire, and a probe that outlived its
		// stream would sit on the shared scheduler for the life of the process.
		handle.set(SCHEDULER.scheduleWithFixedDelay(() -> {
			if (!probe(emitter)) {
				cancel(handle);
			}
		}, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS));
		Runnable cancel = () -> cancel(handle);
		emitter.onCompletion(cancel);
		emitter.onTimeout(cancel);
		emitter.onError((ex) -> cancel.run());
	}

	/**
	 * Write one comment to the stream.
	 * @return true while the stream is still alive
	 */
	private static boolean probe(SseEmitter emitter) {
		try {
			emitter.send(SseEmitter.event().comment("keepalive"));
			return true;
		}
		catch (IOException | IllegalStateException ex) {
			// The subscriber is gone (or the stream is already finished). Completing is
			// what runs the onCompletion hook that closes the underlying watch.
			log.debug("SSE keepalive failed ({}); completing the stream", ex.getMessage());
			completeQuietly(emitter, ex);
			return false;
		}
	}

	/**
	 * End a stream whose write has just failed, from a thread that is not the
	 * container's.
	 *
	 * <p>
	 * The naked {@code completeWithError} is not safe there. Tomcat rejects any use of
	 * the {@code AsyncContext} once it has run {@code AsyncListener.onError()} — "a
	 * non-container (application) thread attempted to use the AsyncContext after an error
	 * had occurred" — and every SSE stream here completes from a watch callback, a log
	 * reader or the keepalive scheduler. Losing that race threw <b>out of</b> the log
	 * reader thread, so a disconnect from a chatty pod printed an uncaught
	 * {@code Exception in thread "log-sse-…"} to stderr. It released the watch anyway
	 * (the try-with-resources had already closed it), which is why it survived: a noisy
	 * stack trace on a path nobody was reading.
	 *
	 * <p>
	 * The container has already torn the response down in that case, so there is nothing
	 * to do but say so at trace level.
	 */
	static void completeQuietly(SseEmitter emitter, Throwable cause) {
		try {
			emitter.completeWithError(cause);
		}
		catch (IllegalStateException ex) {
			log.trace("SSE stream was already finished by the container: {}", ex.getMessage());
		}
	}

	private static void cancel(AtomicReference<ScheduledFuture<?>> handle) {
		ScheduledFuture<?> future = handle.get();
		if (future != null) {
			future.cancel(false);
		}
	}

}

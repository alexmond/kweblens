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
	 * One shared daemon scheduler. These tasks do one small write each; a thread per
	 * stream would cost more than the streams do.
	 */
	private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor((runnable) -> {
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
			complete(emitter, ex);
			return false;
		}
	}

	private static void complete(SseEmitter emitter, Exception cause) {
		try {
			emitter.completeWithError(cause);
		}
		catch (IllegalStateException ex) {
			// Already completed by the send path; nothing left to do.
			log.trace("SSE stream was already complete: {}", ex.getMessage());
		}
	}

	private static void cancel(AtomicReference<ScheduledFuture<?>> handle) {
		ScheduledFuture<?> future = handle.get();
		if (future != null) {
			future.cancel(false);
		}
	}

}

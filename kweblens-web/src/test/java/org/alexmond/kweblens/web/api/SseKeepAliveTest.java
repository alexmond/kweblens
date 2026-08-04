package org.alexmond.kweblens.web.api;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The keepalive that makes a departed SSE subscriber discoverable.
 *
 * <p>
 * Measured on a live cluster, a resource-list watch outlived its subscriber by more than
 * five minutes on a quiet kind, because an {@link SseEmitter} learns of a disconnect only
 * from a failed write and a quiet kind never writes. The three things that have to hold
 * are pinned here: the probe fires without an event, a failed probe <b>completes</b> the
 * emitter (which is what runs the {@code onCompletion} hook that closes the watch), and
 * completion stops the probing.
 */
class SseKeepAliveTest {

	@Test
	void probesEvenWhenTheStreamIsSilent() throws Exception {
		CountDownLatch probed = new CountDownLatch(3);
		SseEmitter emitter = new SseEmitter(0L) {
			@Override
			public void send(SseEventBuilder builder) throws IOException {
				probed.countDown();
				// Stop after the third, so this test leaves nothing running on the
				// shared scheduler for the rest of the suite.
				if (probed.getCount() == 0) {
					throw new IOException("done");
				}
			}

			@Override
			public void completeWithError(Throwable ex) {
			}
		};

		SseKeepAlive.attach(emitter, Duration.ofMillis(20));

		assertThat(probed.await(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void aFailedProbeCompletesTheStreamSoTheWatchIsClosed() throws Exception {
		AtomicBoolean completed = new AtomicBoolean();
		CountDownLatch done = new CountDownLatch(1);
		SseEmitter emitter = new SseEmitter(0L) {
			@Override
			public void send(SseEventBuilder builder) throws IOException {
				throw new IOException("Broken pipe");
			}

			@Override
			public void completeWithError(Throwable ex) {
				completed.set(true);
				done.countDown();
			}
		};

		SseKeepAlive.attach(emitter, Duration.ofMillis(20));

		assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(completed).isTrue();
	}

	@Test
	void aDeadStreamIsProbedExactlyOnce() throws Exception {
		AtomicInteger probes = new AtomicInteger();
		SseEmitter emitter = new SseEmitter(0L) {
			@Override
			public void send(SseEventBuilder builder) throws IOException {
				probes.incrementAndGet();
				throw new IOException("Broken pipe");
			}

			@Override
			public void completeWithError(Throwable ex) {
			}
		};

		SseKeepAlive.attach(emitter, Duration.ofMillis(20));
		Thread.sleep(400);

		// The schedule cancels itself on the first failure, without waiting for the
		// container to run the completion callback: otherwise a probe for a stream that
		// ended would sit on the shared scheduler for the life of the process.
		assertThat(probes).hasValue(1);
	}

}

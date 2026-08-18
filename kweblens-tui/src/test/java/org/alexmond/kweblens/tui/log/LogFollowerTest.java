package org.alexmond.kweblens.tui.log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.tui.data.LogStream;
import org.alexmond.kweblens.tui.screen.Eventually;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The release, at the one object that performs it.</b>
 *
 * <p>
 * GH#369's first done-when is that opening and closing a log pane twenty times leaves
 * zero open connections, and the screen test proves that end to end. This proves the
 * piece it rests on: {@link LogFollower#close()} calls {@link LogStream#close()} — which
 * is {@code LogService.release}, the only call that actually tears a {@code watchLog()}
 * follow down — exactly once, however it is reached.
 *
 * <p>
 * The other half is negative and just as load-bearing: <b>the reader thread must not
 * close the stream</b>. Closing a {@code BufferedReader} would close the underlying
 * {@code InputStream}, which does free the connection — and would therefore make a
 * leaking close look like a working one, while leaving the {@code LogWatch}'s executor
 * running. {@link #theReaderNeverClosesTheStreamItself()} is the control for that, and it
 * fails the moment a try-with-resources appears in the read loop.
 */
class LogFollowerTest {

	private static LogModel model() {
		return LogModel.following("ns", "web-0", "app", List.of("app"), false);
	}

	private static final Duration REAPING = Duration.ofSeconds(5);

	@Test
	void closingGoesThroughTheStreamsOwnClose_whichIsRelease() {
		RecordingStream stream = new RecordingStream("a\nb\n");
		LogModel model = model();
		LogFollower follower = new LogFollower(stream, model).start();
		Eventually.await(() -> model.buffered() == 2, "both lines to be read");

		follower.close();

		assertThat(stream.closes.get())
			.as("release is the only call that stops a watchLog() follow; the pane must make it")
			.isEqualTo(1);
		assertThat(follower.closed()).isTrue();
		assertThat(follower.awaitReader(REAPING)).as("and the reader thread does not outlive the stream").isTrue();
	}

	/**
	 * Every path that ends a follow lands on {@code close()} — {@code esc}, a container
	 * switch, a timestamps toggle, the session going away — so it has to be safe to reach
	 * more than once. A second release is not merely harmless, it must be a no-op: the
	 * handle it would release may by then belong to a follow somebody else opened.
	 */
	@Test
	void closingTwiceReleasesOnce() {
		RecordingStream stream = new RecordingStream("");
		LogFollower follower = new LogFollower(stream, model()).start();

		follower.close();
		follower.close();
		follower.close();

		assertThat(stream.closes.get()).isEqualTo(1);
	}

	/**
	 * <b>The negative control.</b> A reader that closed its own stream would tear the
	 * connection down as a side effect, which is exactly what hid this bug for as long as
	 * it hid: a chatty pod releases anyway, so the working path was never
	 * {@code close()}. The stream is finite here, so the reader runs to end-of-input and
	 * stops — and it must still not have closed anything.
	 */
	@Test
	void theReaderNeverClosesTheStreamItself() {
		RecordingStream stream = new RecordingStream("one\ntwo\nthree\n");
		LogModel model = model();
		LogFollower follower = new LogFollower(stream, model).start();

		assertThat(follower.awaitReader(REAPING)).as("the reader reached end of input").isTrue();
		model.flush();

		assertThat(model.visible(model.window(10))).containsExactly("one", "two", "three");
		assertThat(stream.closes.get())
			.as("a reader that closed the stream would free the connection WITHOUT release, "
					+ "leaving the LogWatch's executor running and making a leaking close look correct")
			.isZero();
		assertThat(model.endedReason()).isEqualTo("the container's log stream ended");
	}

	/**
	 * A close the pane asked for must not be reported as a failure. fabric8 and a closed
	 * socket both answer a read with an exception, so without the guard every {@code esc}
	 * would leave "the log stream failed" on a model — the same rule {@code CoreWatch}
	 * keeps for a watch it closed itself.
	 */
	@Test
	void aCloseWeAskedForIsNotReportedAsAFailure() {
		BlockingStream stream = new BlockingStream();
		LogModel model = model();
		LogFollower follower = new LogFollower(stream, model).start();
		Eventually.await(stream::reading, "the reader to be parked on the stream");

		follower.close();

		assertThat(follower.awaitReader(REAPING)).isTrue();
		assertThat(model.endedReason()).as("nothing failed — the operator pressed esc").isEmpty();
		assertThat(model.live()).isTrue();
	}

	/**
	 * A stream that breaks under the reader is reported, because nobody asked for that.
	 */
	@Test
	void aStreamThatBreaksIsReportedInWords() {
		LogStream stream = new LogStream() {
			@Override
			public InputStream stream() {
				return new InputStream() {
					@Override
					public int read() throws IOException {
						throw new IOException("connection reset by peer");
					}
				};
			}

			@Override
			public void close() {
			}
		};
		LogModel model = model();

		new LogFollower(stream, model).start().awaitReader(REAPING);

		assertThat(model.endedReason()).isEqualTo("the log stream failed: connection reset by peer");
		assertThat(model.live()).isFalse();
	}

	/**
	 * A release that throws is swallowed and turned into words. Every caller is a cleanup
	 * path; throwing from one would either replace a real failure with this one or skip
	 * the rest of the teardown — on the path whose whole job is to release a connection.
	 */
	@Test
	void aReleaseThatThrowsDoesNotEscapeTheCleanupPath() {
		LogStream stream = new LogStream() {
			@Override
			public InputStream stream() {
				return InputStream.nullInputStream();
			}

			@Override
			public void close() {
				throw new IllegalStateException("already gone");
			}
		};
		LogModel model = model();

		new LogFollower(stream, model).close();

		assertThat(model.endedReason()).contains("could not be released cleanly").contains("already gone");
	}

	/** A stream whose bytes are a fixed string, counting its own closes. */
	private static final class RecordingStream implements LogStream {

		private final InputStream bytes;

		private final AtomicInteger closes = new AtomicInteger();

		private RecordingStream(String text) {
			this.bytes = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
		}

		@Override
		public InputStream stream() {
			return this.bytes;
		}

		@Override
		public void close() {
			this.closes.incrementAndGet();
		}

	}

	/**
	 * A quiet pod: the read parks and only a close ends it. This is the shape the whole
	 * ticket is about — a chatty pod releases by accident and never exposes the bug.
	 */
	private static final class BlockingStream implements LogStream {

		private static final long POLL_MILLIS = 5;

		private volatile boolean closed;

		private volatile boolean reading;

		@Override
		public InputStream stream() {
			return new InputStream() {
				@Override
				public int read() throws IOException {
					BlockingStream.this.reading = true;
					while (!BlockingStream.this.closed) {
						try {
							Thread.sleep(POLL_MILLIS);
						}
						catch (InterruptedException ex) {
							Thread.currentThread().interrupt();
							throw new IOException("interrupted", ex);
						}
					}
					throw new IOException("stream closed");
				}
			};
		}

		private boolean reading() {
			return this.reading;
		}

		@Override
		public void close() {
			this.closed = true;
		}

	}

}

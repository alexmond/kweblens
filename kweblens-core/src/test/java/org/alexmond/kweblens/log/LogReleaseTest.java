package org.alexmond.kweblens.log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import io.fabric8.kubernetes.client.dsl.LogWatch;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * {@link LogService#release} — stopping a log follow for real.
 *
 * <p>
 * The thing under test is one line of behaviour that cost a measured leak: the
 * <b>stream</b> has to be closed, not only the watch. fabric8's
 * {@code LogWatchCallback.close()} is {@code asyncBody.thenAccept(AsyncBody::cancel)},
 * and on the {@code watchLog()} flavour — the one that hands back an
 * {@link LogWatch#getOutput() InputStream}, which is the one this project uses — that
 * future is never completed, so the cancel never runs and the connection to the API
 * server stays open with its reader parked.
 *
 * <p>
 * A fake watch rather than the CRUD mock, because the mock's log endpoint hands back a
 * finished stream and the bug is entirely about a stream that never ends. What has to be
 * pinned is the <em>order and the fact</em> of the two closes, and that is exactly what a
 * fake can witness.
 */
class LogReleaseTest {

	private final LogService service = new LogService(new ClusterRegistry());

	@Test
	void releaseClosesTheStreamAndNotJustTheWatch() {
		FakeLogWatch watch = new FakeLogWatch(new ByteArrayInputStream("a line\n".getBytes()));

		this.service.release(watch);

		// The stream is the load-bearing half: closing it is what signals the channel's
		// condition and cancels the body. A test that only asserted watchClosed would
		// have
		// passed against the leaking version.
		assertThat(watch.streamClosed).isTrue();
		assertThat(watch.watchClosed).isTrue();
	}

	@Test
	void releaseSurvivesAWatchThatFailsToClose() {
		// Everything that calls release is a cleanup path — a completion callback, a
		// finally block. Throwing from there would replace a real failure with this one,
		// or skip the rest of the teardown.
		FakeLogWatch watch = new FakeLogWatch(new InputStream() {
			@Override
			public int read() {
				return -1;
			}

			@Override
			public void close() throws IOException {
				throw new IOException("already gone");
			}
		});
		watch.failOnClose = true;

		assertThatNoException().isThrownBy(() -> this.service.release(watch));
	}

	@Test
	void releaseIgnoresAWatchThatWasNeverOpened() {
		// The caller does not always have one: a watch whose request failed, or a second
		// release from a finally block after the first already ran.
		assertThatNoException().isThrownBy(() -> this.service.release(null));
		assertThatNoException().isThrownBy(() -> this.service.release(new FakeLogWatch(null)));
	}

	private static final class FakeLogWatch implements LogWatch {

		private final InputStream output;

		private boolean streamClosed;

		private boolean watchClosed;

		private boolean failOnClose;

		private FakeLogWatch(InputStream output) {
			this.output = (output != null) ? new ClosingStream(output, () -> this.streamClosed = true) : null;
		}

		@Override
		public InputStream getOutput() {
			return this.output;
		}

		@Override
		public CompletionStage<Throwable> onClose() {
			return new CompletableFuture<>();
		}

		@Override
		public void close() {
			this.watchClosed = true;
			if (this.failOnClose) {
				throw new IllegalStateException("watch already closed");
			}
		}

	}

	/** Records the close, then delegates — so a throwing delegate is still observed. */
	private static final class ClosingStream extends InputStream {

		private final InputStream delegate;

		private final Runnable onClose;

		private final AtomicBoolean closed = new AtomicBoolean();

		private ClosingStream(InputStream delegate, Runnable onClose) {
			this.delegate = delegate;
			this.onClose = onClose;
		}

		@Override
		public int read() throws IOException {
			return this.delegate.read();
		}

		@Override
		public void close() throws IOException {
			if (this.closed.compareAndSet(false, true)) {
				this.onClose.run();
			}
			this.delegate.close();
		}

	}

}

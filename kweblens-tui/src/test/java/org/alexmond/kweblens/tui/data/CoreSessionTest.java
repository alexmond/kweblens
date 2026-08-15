package org.alexmond.kweblens.tui.data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.log.LogService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two things a view holds open on a container, and how they let go.
 *
 * <p>
 * The interesting one is the log. {@code LogWatch.close()} does <b>not</b> stop the
 * flavour of follow kweblens uses — it is {@code asyncBody.thenAccept(AsyncBody::cancel)}
 * on a future {@code watchLog()} never completes — so closing the watch alone leaves the
 * connection to the API server open and the reader parked. Only a <em>quiet</em> pod
 * exposes that, and only against a live cluster, so no build can catch it by observing a
 * cluster. What a build <em>can</em> catch is the thing that makes the difference:
 * {@code LogService.release} closes {@code getOutput()} first, and this asserts
 * {@link CoreLogStream} goes through it rather than calling {@code close()} itself.
 */
class CoreSessionTest {

	@Test
	void closingALogStreamClosesTheStreamNotJustTheWatch() {
		RecordingLogWatch watch = new RecordingLogWatch();
		LogStream stream = new CoreLogStream(new LogService(null), watch);

		assertThat(stream.stream()).isSameAs(watch.output);
		stream.close();

		assertThat(watch.output.closed.get())
			.as("the output stream must be closed — that is what cancels the body; "
					+ "closing only the watch sets a flag and leaves the connection open")
			.isTrue();
		assertThat(watch.closed).as("and the watch is closed too, so its executor shuts down").isTrue();
	}

	@Test
	void anExecSessionExposesStdinResizeAndClose() {
		RecordingExecWatch watch = new RecordingExecWatch();
		ExecSession session = new CoreExecSession(watch);

		assertThat(session.stdin()).isSameAs(watch.input);
		session.resize(148, 40);
		session.close();

		assertThat(watch.columns).as("a pane's size reaches the container only through this API call").isEqualTo(148);
		assertThat(watch.rows).isEqualTo(40);
		assertThat(watch.closed).isTrue();
	}

	private static final class ClosingStream extends InputStream {

		private final AtomicBoolean closed = new AtomicBoolean();

		@Override
		public int read() {
			return -1;
		}

		@Override
		public void close() throws IOException {
			this.closed.set(true);
			super.close();
		}

	}

	private static final class RecordingLogWatch implements LogWatch {

		private final ClosingStream output = new ClosingStream();

		private boolean closed;

		@Override
		public InputStream getOutput() {
			return this.output;
		}

		@Override
		public CompletionStage<Throwable> onClose() {
			return CompletableFuture.completedStage(null);
		}

		@Override
		public void close() {
			this.closed = true;
		}

	}

	private static final class RecordingExecWatch implements ExecWatch {

		private final OutputStream input = new ByteArrayOutputStream();

		private int columns;

		private int rows;

		private boolean closed;

		@Override
		public OutputStream getInput() {
			return this.input;
		}

		@Override
		public InputStream getOutput() {
			return InputStream.nullInputStream();
		}

		@Override
		public InputStream getError() {
			return new ByteArrayInputStream(new byte[0]);
		}

		@Override
		public InputStream getErrorChannel() {
			return InputStream.nullInputStream();
		}

		@Override
		public void close() {
			this.closed = true;
		}

		@Override
		public void resize(int cols, int lines) {
			this.columns = cols;
			this.rows = lines;
		}

		@Override
		public CompletableFuture<Integer> exitCode() {
			return CompletableFuture.completedFuture(0);
		}

	}

}

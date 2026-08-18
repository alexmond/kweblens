package org.alexmond.kweblens.tui.log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.alexmond.kweblens.tui.data.LogStream;

/**
 * One log follow in progress: the thread reading it, and <b>the one call that releases
 * it</b>.
 *
 * <h2>Why this class exists at all</h2>
 *
 * GH#369's first done-when: opening and closing a log pane twenty times against a
 * <em>quiet</em> pod must leave zero open connections. It is worth stating why that is
 * not automatic. fabric8's {@code LogWatch.close()} does not stop the flavour of follow
 * kweblens uses — it is {@code asyncBody.thenAccept(AsyncBody::cancel)} on a future
 * {@code watchLog()} never completes, so it sets a flag and returns while the HTTP
 * request to the API server stays open and the reader stays parked.
 * {@code LogService.release} is the call that closes the stream first and therefore
 * actually tears the follow down, and {@link LogStream#close()} is that call and nothing
 * else.
 *
 * <p>
 * It survived for as long as it did because a <em>chatty</em> pod releases anyway: the
 * failed downstream write throws out of the read loop and the try-with-resources closes
 * the reader, which is what tears the connection down. So the working path was never
 * {@code close()}, and a test that opens a busy pod's logs and closes them passes while
 * leaking.
 *
 * <h2>Which is why the reader does not close anything</h2>
 *
 * The read loop below wraps the stream in a {@link BufferedReader} and <b>deliberately
 * never closes it</b>, and there is no try-with-resources in this file. Closing the
 * reader would close the underlying {@link InputStream} — which does tear the connection
 * down, and is exactly the accident that hides the bug: the {@code LogWatch} itself would
 * never be closed, so its executor would be left running, and every future reading of
 * this code would see a close that "obviously works". There is one release path,
 * {@link #close()}, and it goes through {@link LogStream#close()}.
 *
 * <h2>Close does not wait for the reader</h2>
 *
 * {@link #close()} runs on the render thread, inside a key press. Joining the reader
 * there would put a network read between {@code esc} and the next frame. It is not
 * needed: the release is what frees the connection, the thread is a daemon parked on a
 * stream that has just been closed, and a straggler that manages one more {@code append}
 * appends into a {@link LogModel} the pane has already replaced — an orphan nothing
 * draws. {@link #awaitReader} exists for the tests that want to prove the thread actually
 * ends.
 */
public final class LogFollower implements AutoCloseable {

	private final LogStream stream;

	private final LogModel model;

	private final Thread reader;

	private final AtomicBoolean closed = new AtomicBoolean();

	public LogFollower(LogStream stream, LogModel model) {
		this.stream = stream;
		this.model = model;
		this.reader = new Thread(this::read, "kweblens-tui-log-" + model.pod());
		this.reader.setDaemon(true);
	}

	/** Start reading. Separate from the constructor so the owner can install it first. */
	public LogFollower start() {
		this.reader.start();
		return this;
	}

	/**
	 * The read loop. Lines go straight into the buffer — no projection, no repaint, no UI
	 * work posted anywhere, for the reason on {@link LogBuffer}.
	 */
	private void read() {
		BufferedReader lines = new BufferedReader(new InputStreamReader(this.stream.stream(), StandardCharsets.UTF_8));
		try {
			String line = lines.readLine();
			while (line != null) {
				this.model.append(line);
				line = lines.readLine();
			}
			this.model.ended("the container's log stream ended");
		}
		catch (IOException | RuntimeException ex) {
			if (!this.closed.get()) {
				// A close we asked for arrives here as an IOException on a closed stream,
				// and reporting it would put "the log stream failed" on a pane the
				// operator has just left. Same rule as CoreWatch's suppressed self-close.
				this.model.ended("the log stream failed: " + reasonOf(ex));
			}
		}
	}

	private static String reasonOf(Exception ex) {
		return (ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getSimpleName();
	}

	/**
	 * Stop following, for real — and exactly once, however many times this is called. The
	 * pane closes on {@code esc}, on a container switch, on a timestamps toggle and on
	 * the session going away, and every one of those paths lands here.
	 *
	 * <p>
	 * <b>It never throws.</b> Every caller is a cleanup path, and an exception from one
	 * would either replace a real failure with this one or skip the rest of the teardown
	 * — which, on a path whose whole job is to release a connection, is the bug it is
	 * closing.
	 */
	@Override
	public void close() {
		if (!this.closed.compareAndSet(false, true)) {
			return;
		}
		try {
			this.stream.close();
		}
		catch (RuntimeException ex) {
			this.model.ended("the log stream could not be released cleanly: " + reasonOf(ex));
		}
	}

	/** Whether {@link #close()} has run. */
	public boolean closed() {
		return this.closed.get();
	}

	/**
	 * Wait for the reader thread to end. <b>Tests only</b> — nothing on the render thread
	 * may wait on a network read.
	 * @return whether it ended within {@code limit}
	 */
	public boolean awaitReader(Duration limit) {
		try {
			this.reader.join(limit.toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		return !this.reader.isAlive();
	}

}

package org.alexmond.kweblens.tui.data;

import java.io.OutputStream;

/**
 * An interactive shell running in a container.
 *
 * <p>
 * Output goes to the {@link OutputStream} handed to
 * {@link ClusterDataSource#exec(PodTarget, OutputStream)}; this is the other three halves
 * of the session — keystrokes in, the remote terminal's size, and the end of it.
 *
 * <p>
 * <b>{@link #resize} is a real API call, and it is not optional.</b> The pane's size
 * lives in the local terminal; the program inside the container learns it only from the
 * Kubernetes exec API being told. A local {@code TIOCSWINSZ} does not reach a pod, so
 * without this call {@code stty size} inside the container answers with whatever it was
 * born with and every full-screen program there draws at the wrong size (#370).
 */
public interface ExecSession extends AutoCloseable {

	/**
	 * The container's stdin. Keystrokes should be encoded by
	 * {@code ScreenTerminal.pipe()} rather than by hand — it translates arrows for
	 * whichever cursor-key mode the remote program has set, and hand-rolled encoding is
	 * the bug you find later, in {@code vi}.
	 */
	OutputStream stdin();

	/** Tell the container's terminal how big the pane now is. */
	void resize(int columns, int rows);

	/** End the session and release the connection. */
	@Override
	void close();

}

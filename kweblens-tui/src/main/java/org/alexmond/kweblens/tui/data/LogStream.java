package org.alexmond.kweblens.tui.data;

import java.io.InputStream;

/**
 * A log follow in progress: the bytes, and the one call that really stops it.
 *
 * <p>
 * <b>Why this is an interface and not a bare {@code InputStream}.</b> fabric8's
 * {@code LogWatch.close()} does <em>not</em> stop the flavour of follow kweblens uses —
 * it is implemented as {@code asyncBody.thenAccept(AsyncBody::cancel)} on a future that
 * {@code watchLog()} never completes, so it sets a flag and returns while the connection
 * to the API server stays open and the reader stays parked. Only a <em>quiet</em> pod
 * exposes this, and only against a live cluster, which is exactly the shape of bug that
 * ships. {@code LogService.release} is the call that closes the stream first and
 * therefore actually tears the follow down.
 *
 * <p>
 * Handing the caller a raw stream would put the choice between those two in every view
 * that ever follows a log (#369). This type removes the choice: {@link #close()} is
 * release, and there is no other way to end the follow.
 */
public interface LogStream extends AutoCloseable {

	/** The live bytes. Read it on a thread that is not the one drawing the screen. */
	InputStream stream();

	/** Stop following, for real — releases the connection, not just a flag. */
	@Override
	void close();

}

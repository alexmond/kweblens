package org.alexmond.kweblens.resource;

import io.fabric8.kubernetes.client.WatcherException;

/**
 * How {@link ResourceService#watchRaw} tells a caller that the stream stopped delivering.
 *
 * <h2>Why this exists at all</h2>
 *
 * fabric8 reconnects a watch by itself, so the callbacks below fire only when it has
 * given up: a {@code 410 Gone} from the API server, an exhausted reconnect limit, an
 * undeserialisable event, or the caller's own {@code Watch.close()}. Until GH#413 the
 * watcher dropped both, which is fine for a surface that learns about the disconnect some
 * other way — {@code SseKeepAlive} completes the emitter for the web layer — and is wrong
 * for one that does not. A terminal whose watch has died and which is not told simply
 * goes on drawing the last state it saw, with a row count that reads as current.
 *
 * <h2>The two endings are separate methods on purpose</h2>
 *
 * fabric8 calls exactly one of {@code Watcher.onClose()} and
 * {@code Watcher.onClose(WatcherException)} — {@code AbstractWatchManager} guards both
 * with the same {@code forceClosed} flag — and, measured against 7.3.1, the no-argument
 * one is reached only from {@code Watch.close()}, i.e. from the caller closing its own
 * watch. A single callback with a nullable cause would collapse "we asked for this" and
 * "it broke" into one signal that every caller then has to re-derive from a null check.
 */
public interface WatchEndListener {

	/**
	 * The stream ended without an error. Against fabric8 7.3.1 this means the watch was
	 * closed locally, so a caller that closes its own watch will see this and should not
	 * report it as a loss.
	 */
	void completed();

	/**
	 * The stream died and fabric8 will not reconnect it. Nothing further arrives on this
	 * watch; a caller that wants to keep seeing changes has to open a new one.
	 * @param cause what fabric8 gave up on
	 */
	void failed(WatcherException cause);

}

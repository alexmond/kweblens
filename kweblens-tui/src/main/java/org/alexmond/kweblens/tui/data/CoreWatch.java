package org.alexmond.kweblens.tui.data;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.WatcherException;

import org.alexmond.kweblens.resource.WatchEndListener;

/**
 * One open watch: the handle the screen closes, and the thing fabric8 tells when the
 * stream stops.
 *
 * <h2>A close we asked for is not a loss</h2>
 *
 * Measured against fabric8 7.3.1, {@code Watch.close()} runs {@code closeEvent()}, which
 * calls the watcher's no-argument {@code onClose()}. So the ending the TUI causes and the
 * ending it needs to hear about arrive on the same code path, and only this class knows
 * which of the two happened. {@link #close()} raises the flag <em>before</em> it closes
 * the watch, so the callback — which fabric8 dispatches on its own serial executor —
 * finds it already set.
 *
 * <p>
 * Getting that wrong is not cosmetic. Quitting the screen would report "watch lost" on
 * the way out, and, worse, the reconnect closes the previous handle first: without the
 * flag every recovery would immediately report itself as a fresh failure and the screen
 * would reconnect forever.
 *
 * <h2>The handle arrives after the watcher does</h2>
 *
 * {@code op.watch(watcher)} takes the watcher and returns the {@link Watch}, so for a
 * moment this object is subscribed with nothing to close. {@link #attach} closes that
 * gap; a {@link #close()} that lands first still raises the flag, and the watch is closed
 * as soon as it is attached rather than left open on a screen that has gone.
 */
final class CoreWatch implements Subscription, WatchEndListener {

	private final Consumer<WatchEnd> onEnd;

	private final AtomicBoolean closedByUs = new AtomicBoolean();

	private final AtomicBoolean reported = new AtomicBoolean();

	private final AtomicReference<Watch> watch = new AtomicReference<>();

	CoreWatch(Consumer<WatchEnd> onEnd) {
		this.onEnd = onEnd;
	}

	/** Take ownership of the handle fabric8 returned. */
	void attach(Watch open) {
		this.watch.set(open);
		if (this.closedByUs.get()) {
			open.close();
		}
	}

	@Override
	public void completed() {
		ended(WatchEnd.completed());
	}

	@Override
	public void failed(WatcherException cause) {
		ended(WatchEnd.failed(cause));
	}

	/**
	 * Report an ending once, and only if the TUI did not cause it. fabric8 already guards
	 * against firing both callbacks, but the guard here is this class's own: a handle
	 * that has been closed must never speak again, whatever the library does.
	 */
	private void ended(WatchEnd end) {
		if (this.closedByUs.get() || !this.reported.compareAndSet(false, true)) {
			return;
		}
		this.onEnd.accept(end);
	}

	@Override
	public void close() {
		this.closedByUs.set(true);
		Watch open = this.watch.get();
		if (open != null) {
			open.close();
		}
	}

}

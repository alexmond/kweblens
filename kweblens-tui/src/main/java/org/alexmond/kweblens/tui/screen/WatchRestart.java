package org.alexmond.kweblens.tui.screen;

import java.util.List;

/**
 * How the screen re-establishes a watch that died: close the old handle, subscribe again,
 * and re-list.
 *
 * <h2>Why it returns rows instead of writing them anywhere</h2>
 *
 * {@link #reconnect} runs on {@link WatchSupervisor}'s recovery thread, and
 * {@link ResourceModel} is deliberately not thread-safe — "every mutation runs on the
 * render thread" is the model's own contract. So the reconnect does the two things that
 * block on a network (subscribe, list) off the render thread and hands back the finished
 * rows; the supervisor installs them on the next tick, on the render thread, with one
 * {@code replaceAll}.
 *
 * <p>
 * <b>Rows, not objects.</b> The pages are projected as they arrive and the objects are
 * dropped, exactly as the initial load does; what accumulates is the whole kind's rows,
 * which is what the model was about to hold anyway. A re-list that streamed straight into
 * the model could not be a replacement — and a replacement is the point, because the row
 * that has to disappear is the one deleted while the screen was blind.
 *
 * <h2>Re-list, because there is no resourceVersion to resume from</h2>
 *
 * The TUI does not track {@code resourceVersion}, so there is no honest "carry on from
 * where we stopped". A fresh list is the only answer that is true afterwards, and it also
 * closes the narrower gap GH#413 names: an object deleted between the first list and the
 * first subscribe would otherwise sit on screen until something else touched it.
 */
@FunctionalInterface
public interface WatchRestart {

	/**
	 * Subscribe, then list. Called on the recovery thread, never concurrently with
	 * itself.
	 * @param lease the identity of the <em>new</em> subscription: where it reports its
	 * own ending, and the generation its events must be stamped with so that the watch it
	 * replaces cannot put a row back after the re-list has corrected it (GH#417)
	 * @return every row of the kind, for the render thread to install
	 * @throws RuntimeException if the cluster refused; the supervisor backs off and
	 * retries
	 */
	List<ResourceRow> reconnect(WatchLease lease);

}

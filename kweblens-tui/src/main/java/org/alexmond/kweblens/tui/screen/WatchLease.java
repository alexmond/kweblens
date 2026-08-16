package org.alexmond.kweblens.tui.screen;

import java.util.function.Consumer;

import org.alexmond.kweblens.tui.data.WatchEnd;

/**
 * One subscription's identity: the generation it was minted with, and the listener its
 * ending must be reported to.
 *
 * <h2>Why the two travel together</h2>
 *
 * {@link WatchSupervisor} has stamped a generation per subscription since GH#413, so that
 * a handle failing while it is being replaced cannot register as the failure of its
 * replacement. GH#417 is the same confusion one step earlier, on the <em>events</em>:
 * nothing distinguished an event delivered by a superseded watch from one delivered by
 * its replacement, so an event minted before the loss could be applied on top of the row
 * the post-recovery list had just corrected.
 *
 * <p>
 * The counter answers that too — <b>but only if it is captured when the subscription is
 * opened, never read when an event arrives</b>. The counter names the newest
 * subscription, not the one that delivered this event, and the two differ for exactly the
 * window the defect lives in: the generation is minted on the render thread when the
 * attempt starts, while the dying watch is still open and still delivering. An event
 * stamped at arrival would therefore be stamped with its successor's identity — the
 * precise confusion the counter exists to prevent. {@link WatchSupervisor#ended} captures
 * it for the same reason; this record is what lets {@link WatchCoalescer#sink} capture it
 * as well.
 *
 * @param generation which subscription this is, in mint order
 * @param onEnd where this subscription — and only this one — reports that it has stopped
 */
public record WatchLease(long generation, Consumer<WatchEnd> onEnd) {
}

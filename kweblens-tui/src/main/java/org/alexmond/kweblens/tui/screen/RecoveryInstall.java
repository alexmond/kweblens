package org.alexmond.kweblens.tui.screen;

import java.util.List;

/**
 * How a finished reconnect reaches the screen: the other end of {@link WatchRestart}.
 *
 * <h2>Why the supervisor does not simply call the model</h2>
 *
 * It used to, and installing a re-list is two things rather than one. The rows replace
 * the model — that is GH#413's replacement, the only way the row deleted while the screen
 * was blind ever leaves — and the subscription that took that list is <b>from that moment
 * allowed to speak</b>, because everything it delivered while the list was on the network
 * has been waiting for exactly this (GH#420). Doing only the first wipes the second:
 * {@code replaceAll} is a replacement, so an event newer than the list that had already
 * been flushed onto the old rows is gone, with nothing coming to correct it.
 *
 * <p>
 * Keeping the two together behind one call is what makes the ordering unmissable. The
 * generation travels with the rows because the hold is per-subscription: releasing it on
 * the word of a list belonging to a subscription that has since been replaced would let
 * the replacement's events out ahead of the replacement's own list, which is the same
 * defect one turn later.
 *
 * <h2>Threading</h2>
 *
 * Called on the <b>render thread</b>, from inside {@code WatchSupervisor.tick()}, because
 * {@link ResourceModel} is single-threaded by contract. The reconnect itself — the two
 * calls that block on a network — has already happened on the recovery thread and handed
 * back rows.
 */
@FunctionalInterface
public interface RecoveryInstall {

	/**
	 * Put a reconnect's rows on screen and let its subscription's buffered events follow.
	 * @param generation the subscription that took this list
	 * @param rows every row of the kind, as the re-list found it
	 */
	void install(long generation, List<ResourceRow> rows);

}

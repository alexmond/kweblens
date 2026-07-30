package org.alexmond.kweblens.health;

import java.util.List;

/**
 * The health summary for one kind — the small payload that replaces shipping every object
 * to the browser.
 *
 * <p>
 * Carries both the tallies and the <b>named</b> objects needing attention, because those
 * are two halves of one question. Sending only counts would force the client to fetch the
 * collection anyway to say which objects are unhealthy, which is exactly the transfer
 * this exists to avoid.
 *
 * @param needsAttention bounded list of what is wrong, each with a reason
 * @param truncated true when {@code needsAttention} was capped, so the UI can say so
 * rather than implying it is the complete list
 * @param error set when this kind could not be listed at all — reported instead of a
 * zero, because "nothing is wrong" and "I could not check" must not look the same on a
 * health screen
 */
public record KindHealth(String id, String label, String kind, int total, int ok, int attention, int suspended,
		List<UnhealthyItem> needsAttention, boolean truncated, String error) {

	public static KindHealth failed(String id, String label, String kind, String error) {
		return new KindHealth(id, label, kind, 0, 0, 0, 0, List.of(), false, error);
	}

	/** One object needing attention, and why. */
	public record UnhealthyItem(String kind, String namespace, String name, String reason) {
	}

}

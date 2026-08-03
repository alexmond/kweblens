package org.alexmond.kweblens.web.search;

import java.util.List;

/**
 * What a global search found, and — just as importantly — what it did not look at.
 *
 * <p>
 * {@code total} is counted over <b>every</b> match in the searched kinds, not over the
 * rows returned, so "showing 20 of 137" is true rather than "20 of the 20 I kept". That
 * is the same correction GH#157 made to the overviews, which had capped silently.
 *
 * <p>
 * {@code unsearchedKinds} is the harder half and the reason this record exists rather
 * than a bare list: search covers a bounded set of kinds (see {@link SearchKinds}), so a
 * CRD-backed object is unfindable. The response says which kinds were skipped so the
 * reader can tell "it is not there" from "I did not look".
 *
 * @param query the query as searched (trimmed)
 * @param namespace the namespace filter in force, or null for cluster-wide
 * @param hits the best matches, already ranked, capped at the requested limit
 * @param total every match across the searched kinds, before the cap
 * @param truncated whether {@code hits} is shorter than {@code total}
 * @param searchedKinds labels of the kinds that were listed
 * @param unsearchedKinds labels of the kinds in this cluster's nav that were not
 * @param skippedKinds searched kinds whose listing failed, with the reason
 * @param tookMs wall-clock time the search took, server-side
 */
public record SearchResult(String query, String namespace, List<SearchHit> hits, int total, boolean truncated,
		List<String> searchedKinds, List<String> unsearchedKinds, List<SkippedKind> skippedKinds, long tookMs) {

	/**
	 * An empty result — a blank query, or a cluster whose nav resolves no searchable
	 * kind.
	 */
	static SearchResult empty(String query, String namespace) {
		return new SearchResult(query, namespace, List.of(), 0, false, List.of(), List.of(), List.of(), 0L);
	}

	/**
	 * A kind that was in the searched set but could not be listed — most often RBAC
	 * refusing it. Reported rather than swallowed: an empty result for a kind the caller
	 * cannot read is a claim about the cluster that happens to be false.
	 *
	 * @param kind the kind's label
	 * @param reason the failure, as reported by the client
	 */
	public record SkippedKind(String kind, String reason) {
	}

}

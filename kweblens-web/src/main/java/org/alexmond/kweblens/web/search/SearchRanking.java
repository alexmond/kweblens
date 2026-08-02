package org.alexmond.kweblens.web.search;

import java.util.Locale;

/**
 * How well a query matches an object name, and how much the object's kind is worth.
 *
 * <p>
 * The three tiers mirror the SPA's {@code commandPalette.ts} {@code score()} so the
 * palette ranks objects and navigation targets on one scale — prefix beats a word
 * boundary beats "somewhere in the middle", and within a tier a shorter name wins because
 * an exact-ish match is what was meant.
 *
 * <p>
 * One deliberate difference: the client's weakest tier is a <em>subsequence</em>, which
 * is right for the ~120 nav labels it ranks and wrong here. Over a cluster's object names
 * {@code "abc"} as a subsequence matches almost everything ({@code a}…{@code b}…{@code c}
 * appears in most generated names), so the weakest tier is a plain <b>substring</b>. A
 * search that returns everything is the same as one that returns nothing.
 */
public final class SearchRanking {

	/**
	 * The query is a prefix of the name — {@code sonar} finding {@code sonarqube-0}.
	 */
	private static final int PREFIX = 1000;

	/**
	 * The query starts a word inside the name. Object names are hyphen- and
	 * dot-separated, so {@code postgres} should find {@code sonarqube-postgresql-0}.
	 */
	private static final int WORD = 500;

	/** The query appears somewhere inside the name. */
	private static final int SUBSTRING = 100;

	/**
	 * Added for a kind people name directly (Deployment, Service, ConfigMap …) rather
	 * than one the cluster generates for them (Pod, Job).
	 *
	 * <p>
	 * The size is the point: at 250 it is smaller than the 400-point gap between tiers,
	 * so a Pod whose name the query is a <em>prefix</em> of still outranks a Deployment
	 * matched only at a word boundary. Match quality dominates; the kind only breaks ties
	 * within a tier. Ranking kinds strictly would hide the pod you typed the full name of
	 * behind a Deployment that merely contains it.
	 */
	static final int PRIMARY_KIND_BONUS = 250;

	private SearchRanking() {
	}

	/**
	 * How well {@code query} matches {@code name}, or -1 for no match at all.
	 * @param query the (already trimmed, lower-cased) query
	 * @param name the object name
	 * @return a score, higher is better; -1 when the name does not contain the query
	 */
	public static int score(String query, String name) {
		if (query == null || query.isEmpty() || name == null || name.isEmpty()) {
			return -1;
		}
		String candidate = name.toLowerCase(Locale.ROOT);
		int at = candidate.indexOf(query);
		if (at < 0) {
			return -1;
		}
		if (at == 0) {
			return PREFIX - candidate.length();
		}
		return (isWordStart(candidate, at) ? WORD : SUBSTRING) - candidate.length();
	}

	/**
	 * Whether the match at {@code at} begins a word. Kubernetes names are lower-case
	 * alphanumerics separated by {@code -}, {@code .} and (for a namespaced reference)
	 * {@code /}, so those are the only boundaries that exist.
	 */
	private static boolean isWordStart(String candidate, int at) {
		char before = candidate.charAt(at - 1);
		return before == '-' || before == '.' || before == '/';
	}

}

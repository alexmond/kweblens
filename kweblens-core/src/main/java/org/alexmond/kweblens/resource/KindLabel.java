package org.alexmond.kweblens.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The written display label for a CRD-delivered kind — {@code HTTPRoute} rendered as
 * {@code HTTP Routes}, so a custom kind reads like the built-in ones it now sits beside
 * (#433).
 *
 * <p>
 * <b>Why this is derived and not a table.</b> A hand-written map of CRD kind to label
 * would be a second catalog of names that no cluster maintains: it is stale the moment
 * someone installs a CRD nobody here has heard of, and stale silently, because a missing
 * entry looks exactly like a kind that was never installed. The dynamic Custom Resources
 * section exists precisely so the menu is whatever the cluster serves; a label table
 * would put half of it back under editorial control. So the label is computed from what
 * the CRD itself declares, and a kind this file has never seen gets the same treatment as
 * one it has.
 *
 * <p>
 * <b>Pluralisation is READ, never inflected.</b> English plurals are not a suffix rule —
 * {@code Policy}/{@code Policies}, {@code Gateway}/{@code Gateways},
 * {@code GatewayClass}/{@code GatewayClasses}, and {@code VLogs} which is already plural
 * — and a home-grown inflector would be wrong on kinds nobody anticipated, in public, in
 * the menu. But nothing needs inventing: <b>Kubernetes already answered it</b>. A CRD
 * declares {@code spec.names.plural}, the API server serves it as the resource path, and
 * {@link CrdService} has it in hand at the moment it builds the descriptor. So the
 * inflection is lifted off the declared plural: the kind's last word keeps whatever
 * prefix it shares with it and takes the plural's own ending. {@code backendtlspolicies}
 * is what turns {@code Policy} into {@code Policies} here — no rule about {@code y} is
 * written down, and none can go stale.
 *
 * <p>
 * <b>Acronyms keep their run.</b> Splitting on every capital gives {@code H T T P Route},
 * which is worse than the raw kind it replaced. A break is taken between a lower-case (or
 * digit) character and a capital, and at the END of a run of capitals — the position
 * where the following word's lower-case starts. One exception, which the cluster this was
 * written against supplies: a run of exactly two capitals leaves a one-letter first word,
 * and a one-letter word is nearly always a mis-read acronym rather than a word
 * ({@code VLogs} is VictoriaLogs, not "V Logs"), so that break is declined.
 * {@code VLAgent} still splits, because there the run leaves two letters.
 *
 * <p>
 * This is <b>display only</b>. {@link ResourceDescriptor#id()} — the route id a bookmark
 * resolves and the {@code resourceId} the MCP tools take — and
 * {@link ResourceDescriptor#kind()} are untouched.
 */
public final class KindLabel {

	/**
	 * The smallest first word an acronym break may leave behind. See the class note on
	 * {@code VLogs}.
	 */
	private static final int MIN_ACRONYM_HEAD = 2;

	private KindLabel() {
	}

	/**
	 * A label for a custom kind, from the two names the CRD declares.
	 * @param kind {@code spec.names.kind}, e.g. {@code HTTPRoute}
	 * @param plural {@code spec.names.plural}, e.g. {@code httproutes}
	 * @return the display label, e.g. {@code HTTP Routes}; the kind unchanged if either
	 * name is missing or the two cannot be reconciled
	 */
	public static String forCustomResource(String kind, String plural) {
		if (kind == null || kind.isBlank()) {
			return kind;
		}
		List<String> words = words(kind);
		words.set(words.size() - 1, pluralisedLastWord(words, plural));
		return String.join(" ", words);
	}

	/**
	 * The kind split into words at its camel-case humps, acronym runs kept whole.
	 */
	private static List<String> words(String kind) {
		List<String> words = new ArrayList<>();
		int start = 0;
		for (int i = 1; i < kind.length(); i++) {
			if (breaksBefore(kind, i, i - start)) {
				words.add(kind.substring(start, i));
				start = i;
			}
		}
		words.add(kind.substring(start));
		return words;
	}

	/**
	 * Is index {@code i} the start of a new word, given how much of the current one is
	 * already behind it?
	 */
	private static boolean breaksBefore(String kind, int i, int wordLength) {
		char prev = kind.charAt(i - 1);
		char ch = kind.charAt(i);
		char next = (i + 1 < kind.length()) ? kind.charAt(i + 1) : ' ';
		if (isWordBody(prev) && isUpper(ch)) {
			// `PodDisruption` -> `Pod` + `Disruption`; `L2Advertisement` -> `L2` + ...
			return true;
		}
		// The end of an acronym run: `HTTPRoute` -> `HTTP` + `Route`, but only where it
		// leaves a first word worth having — `VLogs` stays whole.
		return isUpper(prev) && isUpper(ch) && isLower(next) && wordLength >= MIN_ACRONYM_HEAD;
	}

	/**
	 * The kind's last word, inflected the way the CRD's own declared plural inflects it.
	 *
	 * <p>
	 * The two names agree on a prefix and part ways at the ending —
	 * {@code backendtlspolic} then {@code y} against {@code ies}. Whatever they share is
	 * kept from the kind (so its capitalisation survives) and whatever the plural adds is
	 * appended. When they share too little to place the split inside the last word — a
	 * plural that is not this kind's plural at all — nothing is inflected and the
	 * singular stands, which is a smaller lie than a guessed ending.
	 */
	private static String pluralisedLastWord(List<String> words, String plural) {
		String last = words.get(words.size() - 1);
		if (plural == null || plural.isBlank()) {
			return last;
		}
		String joined = String.join("", words).toLowerCase(Locale.ROOT);
		String declared = plural.toLowerCase(Locale.ROOT);
		int shared = sharedPrefixLength(joined, declared);
		int lastWordStart = joined.length() - last.length();
		if (shared <= lastWordStart) {
			return last;
		}
		return last.substring(0, shared - lastWordStart) + declared.substring(shared);
	}

	private static int sharedPrefixLength(String a, String b) {
		int n = 0;
		while (n < a.length() && n < b.length() && a.charAt(n) == b.charAt(n)) {
			n++;
		}
		return n;
	}

	private static boolean isUpper(char c) {
		return c >= 'A' && c <= 'Z';
	}

	private static boolean isLower(char c) {
		return c >= 'a' && c <= 'z';
	}

	/**
	 * A character that a following capital breaks away from. Digits count as word body,
	 * not as a break: {@code L2Advertisement} is `L2` and `Advertisement`, never `L` `2`.
	 */
	private static boolean isWordBody(char c) {
		return isLower(c) || (c >= '0' && c <= '9');
	}

}

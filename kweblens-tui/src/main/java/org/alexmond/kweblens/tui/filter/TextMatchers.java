package org.alexmond.kweblens.tui.filter;

import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * How one value is compared: a substring, a compiled regex, or a whole-label equality.
 *
 * <p>
 * Every matcher is built once, at parse time, and then applied per row.
 */
final class TextMatchers {

	/** The character that opts a term into fuzzy matching. */
	private static final String FUZZY = "~";

	private TextMatchers() {
	}

	/**
	 * A {@code /regex/}, a {@code ~fuzzy}, a {@code "quoted string"}, or a bare
	 * substring.
	 *
	 * <p>
	 * The substring comparison is case-<b>insensitive</b> under {@link Locale#ROOT}. The
	 * locale is named rather than defaulted because {@code "I".toLowerCase()} in a
	 * Turkish locale is not {@code "i"}, and a filter box whose answers depend on the
	 * operator's locale is a filter box that disagrees with the browser on the same
	 * cluster.
	 */
	static Predicate<String> text(String raw) {
		Predicate<String> regex = regex(raw);
		if (regex != null) {
			return regex;
		}
		Predicate<String> fuzzy = fuzzy(raw);
		if (fuzzy != null) {
			return fuzzy;
		}
		String needle = unquote(raw).toLowerCase(Locale.ROOT);
		if (needle.isEmpty()) {
			throw new FilterError("Empty search text — remove it, or quote the text you meant");
		}
		return (value) -> value.toLowerCase(Locale.ROOT).contains(needle);
	}

	/**
	 * A {@code status:} value: a {@code /regex/}, or the WHOLE state label, compared
	 * case-insensitively.
	 *
	 * <p>
	 * Exact rather than substring on purpose. {@code status:} exists so that an overview
	 * card's number and the rows it selects are the same set, and a substring match
	 * breaks that silently the first time two states share a stem — {@code Complete}
	 * would take {@code Completed} with it, and every {@code …BackOff} would answer to
	 * {@code Backoff}. The {@code /regex/} form is still there for the genuinely fuzzy
	 * question, so nothing is lost except the way of getting a wrong count without
	 * noticing.
	 *
	 * <p>
	 * The empty case gets its own sentence rather than "Empty search text", because
	 * {@code status:} with nothing after it is usually a half-typed query and the useful
	 * reply names a state.
	 *
	 * <p>
	 * A {@code ~fuzzy} value is <b>refused</b>, and refusing it is the whole point: left
	 * to fall through, {@code status:~run} would compare the whole state label to the
	 * text {@code ~run}, match nothing, and read as "no rows are in that state". A term
	 * whose only property is that its count agrees with an overview card's cannot have a
	 * loose form that quietly disagrees.
	 */
	static Predicate<String> status(String raw) {
		Predicate<String> regex = regex(raw);
		if (regex != null) {
			return regex;
		}
		if (raw.startsWith(FUZZY)) {
			throw new FilterError("Fuzzy matching is not available after “status:” — a state matches whole or "
					+ "not at all. Use status:/…/ for a loose match.");
		}
		String wanted = unquote(raw).toLowerCase(Locale.ROOT);
		if (wanted.isEmpty()) {
			throw new FilterError("Missing state after “status:” — write it as status:Running");
		}
		return (value) -> value.toLowerCase(Locale.ROOT).equals(wanted);
	}

	/**
	 * The compiled matcher for a {@code /regex/} value, or null when the value is not
	 * one.
	 *
	 * <p>
	 * <b>{@code find()}, not {@code matches()}</b> — the TypeScript this is ported from
	 * calls {@code RegExp.test}, which searches rather than anchors. A {@code matches()}
	 * here would quietly turn every unanchored pattern into an anchored one and make
	 * {@code /web/} select nothing.
	 *
	 * <p>
	 * {@link Pattern#UNICODE_CASE} rides along with {@link Pattern#CASE_INSENSITIVE}
	 * because JavaScript's {@code i} flag folds case over the whole of Unicode while
	 * Java's, alone, folds only ASCII.
	 */
	private static Predicate<String> regex(String raw) {
		if (!raw.startsWith("/") || !raw.endsWith("/") || raw.length() < 2) {
			return null;
		}
		String source = raw.substring(1, raw.length() - 1);
		if (source.isEmpty()) {
			throw new FilterError("Empty regex — // needs a pattern between the slashes");
		}
		Pattern pattern = compile(source);
		return (value) -> pattern.matcher(value).find();
	}

	private static Pattern compile(String source) {
		try {
			return Pattern.compile(source, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
		}
		catch (PatternSyntaxException ex) {
			// getDescription() is the engine's reason on its own — "Unclosed group".
			// getMessage() prepends the pattern and appends a caret diagram, which would
			// print the pattern twice in a message that already names it.
			String reason = (ex.getDescription() != null) ? ex.getDescription() : "invalid pattern";
			throw new FilterError("Invalid regex /" + source + "/ — " + reason, ex);
		}
	}

	/**
	 * The compiled matcher for a {@code ~fuzzy} value, or null when the value is not one.
	 *
	 * <p>
	 * The text after the {@code ~} is text: no metacharacters, and a {@code "quoted"}
	 * body for a pattern with a space in it. It is deliberately not a second place a
	 * regex can hide.
	 */
	private static Predicate<String> fuzzy(String raw) {
		if (!raw.startsWith(FUZZY)) {
			return null;
		}
		String pattern = unquote(raw.substring(1)).toLowerCase(Locale.ROOT);
		if (pattern.isEmpty()) {
			throw new FilterError("Missing pattern after “~” — write it as ~wbp");
		}
		int[] wanted = pattern.codePoints().toArray();
		return (value) -> isSubsequence(wanted, value.toLowerCase(Locale.ROOT));
	}

	/**
	 * Is every code point of {@code wanted} present in {@code value}, in that order?
	 *
	 * <p>
	 * The whole of what "fuzzy" means here — a subsequence test, which is the membership
	 * half of what k9s's {@code sahilm/fuzzy} computes. The other half is a score, and
	 * this filter does not rank (see {@link ObjectFilter}). Both sides are already
	 * lower-cased by the caller.
	 *
	 * <p>
	 * Iterated by <b>code point</b> rather than by {@code char} so that a pattern cannot
	 * match half of a surrogate pair from one character and half from another — and, more
	 * to the point, so that {@code objectFilter.ts}'s {@code for (const ch of value)},
	 * which iterates code points too, gives the same answer. One pass over the value,
	 * allocating nothing per row.
	 */
	private static boolean isSubsequence(int[] wanted, String value) {
		int found = 0;
		int at = 0;
		while (at < value.length() && found < wanted.length) {
			int codePoint = value.codePointAt(at);
			if (codePoint == wanted[found]) {
				found++;
			}
			at += Character.charCount(codePoint);
		}
		return found == wanted.length;
	}

	/** {@code "quoted text"} unwrapped, or the value as typed. */
	static String unquote(String raw) {
		boolean quoted = raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2;
		return quoted ? raw.substring(1, raw.length() - 1) : raw;
	}

}

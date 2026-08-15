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

	private TextMatchers() {
	}

	/**
	 * A {@code /regex/}, a {@code "quoted string"}, or a bare substring.
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
	 */
	static Predicate<String> status(String raw) {
		Predicate<String> regex = regex(raw);
		if (regex != null) {
			return regex;
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

	/** {@code "quoted text"} unwrapped, or the value as typed. */
	static String unquote(String raw) {
		boolean quoted = raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2;
		return quoted ? raw.substring(1, raw.length() - 1) : raw;
	}

}

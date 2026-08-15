package org.alexmond.kweblens.tui.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splitting a query into terms — the one piece of knowledge that says where a term ends.
 *
 * <p>
 * It is shared by the parser and by {@link StatusTerm#withStatusTerm(String, String)},
 * because "which characters belong to this term" is exactly what keeps
 * {@code name:"two words"} and {@code env in (dev,stage)} whole while a term beside them
 * is dropped. A second splitter written next to a caller is a copy of that rule, and the
 * copy is what goes stale.
 */
final class FilterTokenizer {

	/**
	 * JavaScript's {@code \s}, spelled out, because Java's is a different set.
	 *
	 * <p>
	 * Java's {@code \s} is only {@code [ \t\n\x0B\f\r]}; JavaScript's also covers the
	 * Unicode space separators and the zero-width no-break space. A query pasted out of a
	 * document carries a non-breaking space more often than anyone expects, and a
	 * tokenizer that did not treat it as whitespace would silently glue two terms into
	 * one term that matches nothing — a wrong answer delivered confidently, which is the
	 * defect class this whole grammar is careful about. Spelled once here and used both
	 * as a character class inside the term patterns and by {@link #isSpace(char)}, so the
	 * two cannot disagree.
	 */
	static final String WS = "\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff";

	/**
	 * {@code in} / {@code notin} as its own token, with the value list attached or not.
	 *
	 * <p>
	 * {@code \A} and {@code \z} rather than {@code ^} and {@code $}: Java's {@code $}
	 * also matches <i>before</i> a final line terminator, JavaScript's (without the
	 * {@code m} flag) does not. Every anchored pattern in this package is written this
	 * way so the two implementations agree on a token with a trailing newline in it.
	 */
	private static final Pattern SET_OPERATOR = Pattern.compile("\\A(in|notin)(\\(.*\\))?\\z", Pattern.DOTALL);

	private FilterTokenizer() {
	}

	/**
	 * JavaScript's {@code \s} test, allocation-free — the code points listed in
	 * {@link #WS}, written numerically because half of them are invisible in a source
	 * file.
	 */
	static boolean isSpace(char c) {
		return c == ' ' || (c >= 0x09 && c <= 0x0D) || c == 0x00A0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200A)
				|| c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000 || c == 0xFEFF;
	}

	/**
	 * Split a query into terms on whitespace, keeping quoted text, {@code /regex/} and
	 * {@code (a,b)} whole.
	 */
	static List<String> tokenize(String query) {
		List<String> tokens = new ArrayList<>();
		int i = 0;
		while (i < query.length()) {
			if (isSpace(query.charAt(i))) {
				i++;
				continue;
			}
			Run token = readToken(query, i);
			tokens.add(token.text());
			i = token.next();
		}
		return tokens;
	}

	/**
	 * Re-join {@code key in (a,b)} — three tokens to the splitter, one requirement to
	 * Kubernetes.
	 *
	 * <p>
	 * Handles {@code key in (a,b)}, {@code key in(a,b)} and {@code key notin (a, b)}. A
	 * stray {@code in} with no value list after it is left alone and ends up as an
	 * ordinary text term, which is what someone typing the English word meant.
	 */
	static List<String> mergeSetOperators(List<String> tokens) {
		List<String> out = new ArrayList<>();
		int i = 0;
		while (i < tokens.size()) {
			Matcher m = SET_OPERATOR.matcher(tokens.get(i));
			String operator = m.matches() ? m.group(1) : null;
			String inline = (operator != null) ? m.group(2) : null;
			String group = (inline != null) ? inline : follower(tokens, i);
			if (operator != null && !out.isEmpty() && group != null) {
				out.set(out.size() - 1, out.get(out.size() - 1) + ' ' + operator + ' ' + group);
				i += (inline != null) ? 1 : 2;
				continue;
			}
			out.add(tokens.get(i));
			i++;
		}
		return out;
	}

	/** The next token when it opens a value list, or null. */
	private static String follower(List<String> tokens, int i) {
		String next = (i + 1 < tokens.size()) ? tokens.get(i + 1) : null;
		return (next != null && next.startsWith("(")) ? next : null;
	}

	/** One term: everything up to the next top-level whitespace. */
	private static Run readToken(String query, int from) {
		StringBuilder buf = new StringBuilder();
		int i = from;
		while (i < query.length() && !isSpace(query.charAt(i))) {
			Run run = openRun(query, i, buf);
			if (run != null) {
				buf.append(run.text());
				i = run.next();
			}
			else {
				buf.append(query.charAt(i));
				i++;
			}
		}
		return new Run(buf.toString(), i);
	}

	/**
	 * The delimited run starting here, or null when this character is an ordinary one.
	 *
	 * <p>
	 * <b>A {@code /} opens a regex only at the start of a term, straight after a
	 * {@code -}, or straight after a {@code prefix:}.</b> Anywhere else it is an ordinary
	 * character, so searching for {@code docker.io/nginx} does not silently become a
	 * regex — and, worse, does not become an <i>unterminated</i> one that refuses the
	 * whole query.
	 */
	private static Run openRun(String query, int at, CharSequence buf) {
		char c = query.charAt(at);
		if (c == '"') {
			return readDelimited(query, at, '"', "quote");
		}
		if (c == '(') {
			return readGroup(query, at);
		}
		boolean opensRegex = buf.isEmpty() || "-".contentEquals(buf) || buf.charAt(buf.length() - 1) == ':';
		return (c == '/' && opensRegex) ? readDelimited(query, at, '/', "/regex/") : null;
	}

	/**
	 * Read a {@code "quoted"} or {@code /regex/} run, delimiters included; throws when it
	 * never closes.
	 */
	private static Run readDelimited(String query, int from, char delim, String what) {
		int end = query.indexOf(delim, from + 1);
		if (end < 0) {
			throw new FilterError("Unterminated " + what + " — add a closing " + delim);
		}
		return new Run(query.substring(from, end + 1), end + 1);
	}

	/**
	 * Read a {@code ( … )} value list, parens included; whitespace inside it does not end
	 * the token.
	 */
	private static Run readGroup(String query, int from) {
		int end = query.indexOf(')', from + 1);
		if (end < 0) {
			throw new FilterError("Unterminated ( — add a closing )");
		}
		return new Run(query.substring(from, end + 1), end + 1);
	}

	/** A slice of the query and where reading resumes. */
	private record Run(String text, int next) {
	}

}

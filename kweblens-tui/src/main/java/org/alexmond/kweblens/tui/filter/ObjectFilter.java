package org.alexmond.kweblens.tui.filter;

import java.util.ArrayList;
import java.util.List;

/**
 * The list filter language — parser and predicate, no screen.
 *
 * <p>
 * <b>This is a port, not a design.</b> The grammar, every error sentence and every
 * decided behaviour below come from {@code kweblens-ui/src/objectFilter.ts}, and the 62
 * cases in {@code objectFilter.test.ts} are its specification. Two surfaces with two
 * filter languages is a support burden and a documentation lie (#366), so a change to the
 * grammar is a change to <b>both</b> implementations or it is a regression in one of
 * them.
 *
 * <p>
 * <b>The one thing the two cannot make identical is the regex engine</b> — the browser
 * runs V8's and this runs {@code java.util.regex}. That residue was measured, not
 * assumed: {@code RegexDialectDivergenceTest} lists every input on which the two disagree
 * and how the measurement was taken. Everything else in the language agreed exactly over
 * a 154-query corpus.
 *
 * <h2>The grammar</h2>
 *
 * A query is whitespace-separated <b>terms</b>, and every one of them must match (AND) —
 * the same conjunction {@code kubectl -l a=b,c=d} uses. Any term can be negated with a
 * leading {@code -}.
 *
 * <ul>
 * <li><b>bare word</b> — case-insensitive substring over name, namespace and kind.
 * <li><b>{@code "two words"}</b> — the same, for text with a space in it.
 * <li><b>{@code /regex/}</b> — a regex over the same three fields, case-insensitive.
 * Opt-in via the slashes, because a bare {@code .} or {@code *} in a substring search has
 * to keep meaning itself.
 * <li><b>{@code ~fuzzy}</b> — those letters in that order with gaps allowed, over the
 * same three fields. Opt-in via the {@code ~}, for the same reason.
 * <li><b>{@code name:} {@code ns:} {@code namespace:} {@code kind:}</b> — the same text
 * matching against one field only; the value may itself be a {@code /regex/} or a
 * {@code "quoted string"}.
 * <li><b>{@code status:}</b> — the state the SERVER put the object in, matched
 * <b>exactly</b> (case-insensitively).
 * <li><b>label requirements</b> — {@code app=web}, {@code app==web}, {@code app!=db},
 * {@code env in (dev,stage)}, {@code tier notin (frontend)}, and {@code label:app} /
 * {@code -label:app} for presence and absence.
 * </ul>
 *
 * <h2>The two rules that are easy to get wrong</h2>
 *
 * <ol>
 * <li><b>{@code !=} and {@code notin} also match an object with no such key.</b> That is
 * apimachinery's own semantics, not a convenience: the upstream docs say
 * "environment!=production … also selects resources with no environment label". See
 * {@link Atom.Label#matches(FilterRow)}.
 * <li><b>A {@code /} opens a regex only at the start of a term, after a {@code -}, or
 * straight after a {@code prefix:}.</b> Anywhere else it is an ordinary character, so
 * {@code io/nginx} stays a text search. See {@code FilterTokenizer.openRun}.
 * </ol>
 *
 * <h2>A broken pattern is a sentence, not zero rows</h2>
 *
 * Every failure produces a message naming what is wrong, and {@link ParsedFilter#matches}
 * then matches <i>everything</i> — a filter that could not be compiled is not in force,
 * so the list must not pretend it narrowed anything. "No pods match" and "your pattern is
 * broken" are different claims (#306, #316).
 *
 * <h2>Client-side by design</h2>
 *
 * GH#263 refused a server-side {@code limit} on the search path for one reason: a filter
 * applied over a truncated page reports "no matches" for an object that exists. The TUI
 * lists the whole collection a page at a time and filters each page here, so a term that
 * matches nothing really matches nothing. <b>Do not push any of this into a
 * {@code labelSelector} query parameter</b> — half the grammar has no server-side
 * spelling, and a half-pushed filter is two filters.
 *
 * <h2>Where this is knowingly a subset of {@code kubectl -l}</h2>
 *
 * <ul>
 * <li>Kubernetes writes label presence as a bare {@code partition} and absence as
 * {@code !partition}. Here they are {@code label:partition} and {@code -label:partition},
 * because a bare word is a text search and always was.
 * <li>The numeric requirements {@code key>1} / {@code key<1} are not supported. A token
 * shaped like one is an error rather than a silent substring search for {@code key>1}.
 * <li>Field selectors ({@code --field-selector status.phase=Running}) are not a general
 * feature: arbitrary JSON paths are not addressable. {@code status:} is the one field
 * selector that is, and it selects on kweblens' own state vocabulary rather than on
 * {@code status.phase}.
 * </ul>
 *
 * <h2>{@code ~fuzzy} is four decisions (#411)</h2>
 *
 * k9s's {@code -f} is the one filtering thing it has that this did not, and #366
 * conditioned the port on it landing in {@code objectFilter.ts} in the same change. It
 * did. The four answers, which are the work rather than the code:
 *
 * <ol>
 * <li><b>Its spelling is a leading {@code ~}.</b> A bare word is a case-insensitive
 * substring and must stay one, so fuzzy needs its own opt-in character exactly as
 * {@code /regex/} and {@code label:k} do. A {@code ~} rather than a {@code fuzzy:} prefix
 * because it <i>composes</i>: like {@code /regex/} it is a value form, so it works bare
 * and after {@code name:} with no second spelling. It is also a character no name,
 * namespace or kind an operator meets can carry, so {@code ~foo} before this change was a
 * query that could only ever return zero rows — and where a name somehow does carry one,
 * {@code "~foo"} still searches for it literally.
 * <li><b>It matches name, namespace and kind, each on its own.</b> k9s matches over the
 * row's ID, one joined {@code namespace/name} string, so its subsequence can straddle the
 * {@code /}. That difference is chosen: {@code ~} changes only how ONE string is
 * compared, not which strings are looked at, so it stays a variant of the bare word
 * instead of two features wearing one character — and a joined string would admit a hit
 * no column on screen shows.
 * <li><b>It negates and ANDs like everything else</b>, which costs nothing, because the
 * {@code ~} lives inside the atom and negation is applied outside it.
 * <li><b>It is a predicate and does not rank.</b> A ranking would have to become a sort
 * mode, which is a bigger change than the term, and it would override an order the
 * operator chose.
 * </ol>
 *
 * <p>
 * {@code status:} refuses a {@code ~} value in a sentence rather than comparing a whole
 * state label to the text {@code ~run} and selecting nothing; {@code status:/…/} is the
 * loose form that term already has.
 */
public final class ObjectFilter {

	private ObjectFilter() {
	}

	/**
	 * Parse a filter query. <b>Never throws</b>: a bad query comes back as a
	 * {@link ParsedFilter} carrying an error and no terms.
	 *
	 * <p>
	 * Cheap enough to call on every keystroke — it walks the query string, not the object
	 * list, and every regex is compiled here rather than once per row.
	 * @param query what the operator typed; null and blank are both the inert filter
	 */
	public static ParsedFilter parse(String query) {
		String trimmed = (query != null) ? query.strip() : "";
		if (trimmed.isEmpty()) {
			return ParsedFilter.empty();
		}
		try {
			List<String> tokens = FilterTokenizer.mergeSetOperators(FilterTokenizer.tokenize(trimmed));
			List<FilterTerm> terms = new ArrayList<>(tokens.size());
			for (String token : tokens) {
				terms.add(TermParser.parseTerm(token));
			}
			return ParsedFilter.of(terms);
		}
		catch (FilterError ex) {
			return ParsedFilter.failed(ex.getMessage());
		}
	}

}

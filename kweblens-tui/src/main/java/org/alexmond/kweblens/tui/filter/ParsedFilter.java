package org.alexmond.kweblens.tui.filter;

import java.util.List;

/**
 * A parsed query: either terms, or a sentence saying what is wrong with it. Never both.
 *
 * <p>
 * The exclusivity is the point — a query that did not parse yields no terms, so nothing
 * can accidentally apply half a filter.
 */
public final class ParsedFilter {

	private static final ParsedFilter EMPTY = new ParsedFilter(List.of(), null);

	private final List<FilterTerm> terms;

	private final String error;

	private ParsedFilter(List<FilterTerm> terms, String error) {
		this.terms = terms;
		this.error = error;
	}

	static ParsedFilter empty() {
		return EMPTY;
	}

	static ParsedFilter of(List<FilterTerm> terms) {
		return new ParsedFilter(List.copyOf(terms), null);
	}

	static ParsedFilter failed(String error) {
		return new ParsedFilter(List.of(), error);
	}

	/** What is wrong with the query, in a sentence for the operator, or null. */
	public String error() {
		return this.error;
	}

	/**
	 * Did the query fail to parse? If so it is <b>not in force</b> — see
	 * {@link #matches}.
	 */
	public boolean failed() {
		return this.error != null;
	}

	/**
	 * How many terms are in force. Zero for an empty query <b>and</b> for a broken one:
	 * in both cases nothing has been narrowed.
	 */
	public int termCount() {
		return this.terms.size();
	}

	/**
	 * Does this row satisfy every term?
	 *
	 * <p>
	 * <b>A filter that failed to parse matches everything.</b> That is the whole "a
	 * broken pattern is not zero rows" rule in one branch: the alternative is a list that
	 * looks filtered and is hiding rows for a reason the reader can only guess at. "No
	 * pods match" and "your pattern is broken" are different claims, and the one that was
	 * never established is never made.
	 */
	public boolean matches(FilterRow row) {
		if (this.error != null) {
			return true;
		}
		for (FilterTerm term : this.terms) {
			if (!term.matches(row)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The query as far as a header count or an empty state is concerned.
	 *
	 * <p>
	 * Empty when the filter did not parse, because nothing was narrowed: "0 of 137" over
	 * a full table, or "no pods match “app=”", would both be the header contradicting
	 * what is on screen.
	 */
	public String activeQuery(String query) {
		return (this.error == null) ? query : "";
	}

}

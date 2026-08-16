package org.alexmond.kweblens.tui.screen;

import java.util.function.Predicate;

import org.alexmond.kweblens.tui.filter.FilterRow;
import org.alexmond.kweblens.tui.filter.ObjectFilter;
import org.alexmond.kweblens.tui.filter.ParsedFilter;

/**
 * The bridge from a typed query to a predicate over the rows the table holds.
 *
 * <p>
 * <b>There is one filter language in this product and this is not a second one.</b>
 * {@link ObjectFilter} is #366's port of the browser's {@code objectFilter.ts};
 * everything here does is pair a {@link ResourceRow} with the kind it belongs to so the
 * parser's own {@link FilterRow} can be built. A term this class handled itself would be
 * a term the browser does not have.
 *
 * <p>
 * <b>A broken query narrows nothing.</b> {@link ParsedFilter#matches} already matches
 * everything when the query failed to parse, so that rule arrives here for free rather
 * than being re-implemented — "no rows match" and "your pattern is broken" are different
 * claims and only one of them was established.
 */
public final class RowFilters {

	/** Matches every row: what a screen with no filter uses. */
	public static final Predicate<ResourceRow> ALL = (row) -> true;

	private RowFilters() {
	}

	/**
	 * A predicate for {@code query} over rows of {@code kind}.
	 * @param query what the operator typed, or what a drill-down wrote for them
	 * @param kind the kind the rows are, so a {@code kind:} term can match
	 */
	public static Predicate<ResourceRow> of(String query, String kind) {
		if (query == null || query.isBlank()) {
			return ALL;
		}
		return of(ObjectFilter.parse(query), kind);
	}

	/** The same, for a query that has already been parsed. */
	public static Predicate<ResourceRow> of(ParsedFilter filter, String kind) {
		if (filter.termCount() == 0) {
			return ALL;
		}
		return (row) -> filter.matches(new FilterRow(row.name(), row.namespace(), kind, row.labels(), row.state()));
	}

}

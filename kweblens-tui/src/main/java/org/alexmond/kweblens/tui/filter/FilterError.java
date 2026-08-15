package org.alexmond.kweblens.tui.filter;

/**
 * What is wrong with a query, thrown by the parser internals and caught exactly once, in
 * {@link ObjectFilter#parse(String)}.
 *
 * <p>
 * It never escapes this package and it is never shown as a stack trace. Its message is a
 * sentence for the operator — "Unterminated quote — add a closing \"" — because the whole
 * point of the failure path is that a broken pattern says what is broken rather than
 * emptying the table. See {@link ObjectFilter} for why that distinction is load-bearing.
 */
final class FilterError extends RuntimeException {

	FilterError(String message) {
		super(message);
	}

	/**
	 * With the engine failure that produced it. Only {@link #getMessage()} ever reaches
	 * an operator — the cause is kept so a stack trace is not thrown away on the one path
	 * where there is one to keep.
	 */
	FilterError(String message, Throwable cause) {
		super(message, cause);
	}

}

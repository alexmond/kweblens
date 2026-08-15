package org.alexmond.kweblens.tui.filter;

/**
 * One parsed term: an {@link Atom}, and whether a leading {@code -} inverted it.
 *
 * <p>
 * Negation lives here rather than inside each atom so that <b>every</b> term form takes a
 * {@code -} — text, regex, field, status and label alike — without any of them having to
 * remember to support it. That is the difference from k9s, whose {@code !} negates the
 * whole filter.
 */
record FilterTerm(boolean negated, Atom atom) {

	boolean matches(FilterRow row) {
		return this.negated != this.atom.matches(row);
	}

}

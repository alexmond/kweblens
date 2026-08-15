package org.alexmond.kweblens.tui.filter;

import java.util.ArrayList;
import java.util.List;

/**
 * The writer's half of {@code status:} — building a term, and editing one into a query
 * that already has other terms in it.
 *
 * <p>
 * It lives beside the parser on purpose. A state's number on an overview and the rows a
 * {@code status:} term selects are the same set only while the way a state is WRITTEN and
 * the way it is READ agree about quoting, and two files cannot be made to agree about
 * that; one can.
 */
public final class StatusTerm {

	private static final String PREFIX = "status:";

	private StatusTerm() {
	}

	/**
	 * Can this state label be written as a {@code status:} term that selects exactly it?
	 *
	 * <p>
	 * The grammar has no escape character: a {@code "} inside a quoted value ends the
	 * quote, and there is no other way to protect one. A label carrying one is therefore
	 * not expressible, and the answer is to say so — a term built from it would parse as
	 * something else and select the wrong rows, which is the one failure mode this whole
	 * family of links exists to avoid (#336). The caller renders such a state as plain
	 * text rather than something selectable.
	 */
	public static boolean queryable(String label) {
		return label != null && !label.strip().isEmpty() && !label.contains("\"");
	}

	/**
	 * The query that selects exactly the objects in this state.
	 *
	 * <p>
	 * Quoted only when the label contains whitespace, because whitespace is what ends a
	 * term. Everything else in the vocabulary — {@code CrashLoopBackOff},
	 * {@code Ready,SchedulingDisabled} — is one token and reads better as one.
	 */
	public static String query(String label) {
		return hasSpace(label) ? (PREFIX + '"' + label + '"') : (PREFIX + label);
	}

	/**
	 * The query with its positive {@code status:} terms replaced by {@code label} — or,
	 * with null, removed.
	 *
	 * <p>
	 * <b>Why it replaces rather than appends.</b> Terms are ANDed — that is the grammar,
	 * and the one thing it has no spelling for is "either of these".
	 * {@code status:Running status:Failed} selects nothing at all, so an affordance that
	 * appended would answer a second selection with an empty table. Replacing makes the
	 * positive status selection single-valued, which is what the grammar can actually
	 * express.
	 *
	 * <p>
	 * <b>Negated status terms are left alone</b>, because {@code -status:Running} is a
	 * different question — "everything except the healthy ones" — and it ANDs with a
	 * positive term perfectly well. Every other term (names, namespaces, labels, regexes)
	 * is untouched, so the box keeps whatever else was typed.
	 *
	 * <p>
	 * A query that does not even tokenize (an unterminated quote) is returned
	 * <b>unchanged</b>: there is no term structure to edit, and inventing one would
	 * rewrite what the operator is mid-way through typing.
	 */
	public static String withStatusTerm(String query, String label) {
		List<String> tokens;
		try {
			tokens = FilterTokenizer.tokenize((query != null) ? query : "");
		}
		catch (FilterError ex) {
			return query;
		}
		List<String> kept = new ArrayList<>(tokens.size() + 1);
		for (String token : tokens) {
			if (!token.startsWith(PREFIX)) {
				kept.add(token);
			}
		}
		if (label != null) {
			kept.add(query(label));
		}
		return String.join(" ", kept);
	}

	private static boolean hasSpace(String label) {
		for (int i = 0; i < label.length(); i++) {
			if (FilterTokenizer.isSpace(label.charAt(i))) {
				return true;
			}
		}
		return false;
	}

}

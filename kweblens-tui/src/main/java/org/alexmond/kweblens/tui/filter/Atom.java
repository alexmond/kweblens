package org.alexmond.kweblens.tui.filter;

import java.util.List;
import java.util.function.Predicate;

/**
 * One term's question, with its matcher already compiled.
 *
 * <p>
 * Compiled at <b>parse</b> time, not match time: a query is parsed once per keystroke and
 * evaluated once per row, so a regex built here is built once for three thousand rows
 * rather than three thousand times.
 *
 * <p>
 * Four shapes, not three. {@link Status} looks like a fourth field and is not one: it
 * reads the verdict the server computed rather than a property of the manifest, and it
 * compares the <b>whole</b> label rather than a substring of it. Folding it into
 * {@link Field} is exactly how {@code status:Complete} would start selecting
 * {@code Completed} too.
 */
sealed interface Atom permits Atom.Text, Atom.Field, Atom.Status, Atom.Label {

	/** Does this object answer the question this term asks? Negation is applied above. */
	boolean matches(FilterRow row);

	/**
	 * A bare word, a {@code "quoted string"} or a {@code /regex/} over all three fields.
	 */
	record Text(Predicate<String> match) implements Atom {

		@Override
		public boolean matches(FilterRow row) {
			return this.match.test(row.name()) || this.match.test(row.namespace()) || this.match.test(row.kind());
		}

	}

	/** The same text matching, narrowed to one field by a {@code name:}-style prefix. */
	record Field(FilterField field, Predicate<String> match) implements Atom {

		@Override
		public boolean matches(FilterRow row) {
			return this.match.test(this.field.of(row));
		}

	}

	/**
	 * A {@code status:} term.
	 *
	 * <p>
	 * The empty state is <b>guarded rather than passed to the matcher</b>, so no pattern
	 * — not even {@code /^$/} or one that matches everything — can select an object on
	 * the strength of a verdict nobody reached. Absence of a claim is not a claim of
	 * health.
	 */
	record Status(Predicate<String> match) implements Atom {

		@Override
		public boolean matches(FilterRow row) {
			String state = row.state();
			return !state.isEmpty() && this.match.test(state);
		}

	}

	/**
	 * One label requirement, normalised to the three operators apimachinery really has.
	 *
	 * <p>
	 * {@code =} and {@code ==} collapse into {@code in} with a single value, {@code !=}
	 * into {@code notin} with a single value — which is how {@code labels.Requirement} is
	 * written upstream, and what stops the "key absent" rule below being implemented
	 * twice and inconsistently.
	 */
	record Label(String key, LabelOp op, List<String> values) implements Atom {

		/**
		 * {@code labels.Requirement.Matches}, transcribed — including the part people get
		 * wrong: <b>{@code notin} (and therefore {@code !=}) is TRUE when the key is
		 * absent</b>. Kubernetes' own docs say it in words: "environment!=production …
		 * also selects resources with no environment label". A filter that quietly
		 * disagreed would give a different answer from the {@code kubectl} the operator
		 * ran a minute ago.
		 */
		@Override
		public boolean matches(FilterRow row) {
			boolean present = row.labels().containsKey(this.key);
			if (this.op == LabelOp.EXISTS) {
				return present;
			}
			if (!present) {
				return this.op == LabelOp.NOTIN;
			}
			String value = row.labels().get(this.key);
			boolean has = (value != null) && this.values.contains(value);
			return (this.op == LabelOp.IN) == has;
		}

	}

}

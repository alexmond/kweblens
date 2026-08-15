package org.alexmond.kweblens.tui.filter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One token in, one {@link FilterTerm} out.
 *
 * <p>
 * Every pattern here is anchored with {@code \A}/{@code \z} and uses explicit character
 * classes rather than a lazy {@code \S+?}: the same shapes in the TypeScript backtrack
 * super-linearly when written the obvious way, and a filter box is fed a character at a
 * time.
 */
final class TermParser {

	private static final Map<String, FilterField> FIELDS = Map.of("name", FilterField.NAME, "ns", FilterField.NAMESPACE,
			"namespace", FilterField.NAMESPACE, "kind", FilterField.KIND);

	// A label key: Kubernetes' own shape, permissive about the optional `prefix/` part.
	// Split in two because the single `\A x([-x]*x)? \z` spelling backtracks
	// super-linearly.
	private static final Pattern LABEL_KEY = Pattern.compile("\\A[A-Za-z0-9][-A-Za-z0-9_./]*\\z");

	private static final Pattern LABEL_KEY_END = Pattern.compile("[A-Za-z0-9]\\z");

	private static final Pattern FIELD_TERM = Pattern.compile("\\A([A-Za-z]+):(.*)\\z", Pattern.DOTALL);

	private static final Pattern SET_TERM = Pattern.compile("\\A(?:label:)?([^" + FilterTokenizer.WS + "()]+)["
			+ FilterTokenizer.WS + "]+(in|notin)[" + FilterTokenizer.WS + "]*\\((.*)\\)\\z", Pattern.DOTALL);

	private static final Pattern NUMERIC_TERM = Pattern
		.compile("\\A(?:label:)?([^" + FilterTokenizer.WS + "<>]+)([<>])([^" + FilterTokenizer.WS + "<>]+)\\z");

	private static final Pattern CMP_TERM = Pattern
		.compile("\\A(?:label:)?([^" + FilterTokenizer.WS + "=!<>]+)(!=|==|=)(.*)\\z", Pattern.DOTALL);

	private static final Pattern EXISTS_TERM = Pattern.compile("\\Alabel:([^" + FilterTokenizer.WS + "]*)\\z");

	private static final String LABEL_PREFIX = "label:";

	private TermParser() {
	}

	/**
	 * One term: a leading {@code -} inverts whatever follows it, whatever kind of term
	 * that is.
	 */
	static FilterTerm parseTerm(String token) {
		boolean negated = token.startsWith("-");
		String atom = negated ? token.substring(1) : token;
		if (atom.isEmpty()) {
			throw new FilterError("A bare “-” negates nothing — put the term to exclude straight after it");
		}
		return new FilterTerm(negated, parseAtom(atom));
	}

	/**
	 * One term's atom.
	 *
	 * <p>
	 * <b>Order matters.</b> A delimited run is settled before anything looks for an
	 * operator inside it, or the {@code =} in {@code /a=b/} would be read as a label
	 * requirement.
	 */
	private static Atom parseAtom(String atom) {
		if (atom.startsWith("/") || atom.startsWith("\"")) {
			return new Atom.Text(TextMatchers.text(atom));
		}
		if (atom.startsWith("=") || atom.startsWith("!=")) {
			String op = atom.startsWith("!=") ? "!=" : "=";
			throw new FilterError("Missing label key before “" + op + "” — write it as key=value");
		}
		Atom named = fieldTerm(atom);
		if (named != null) {
			return named;
		}
		Matcher set = SET_TERM.matcher(atom);
		if (set.matches()) {
			return setAtom(set);
		}
		Matcher numeric = NUMERIC_TERM.matcher(atom);
		if (numeric.matches()) {
			throw new FilterError(
					"Numeric label requirements (" + numeric.group(2) + ") are not supported — use =, !=, in or notin");
		}
		Matcher cmp = CMP_TERM.matcher(atom);
		if (cmp.matches()) {
			LabelOp op = "!=".equals(cmp.group(2)) ? LabelOp.NOTIN : LabelOp.IN;
			return new Atom.Label(labelKey(cmp.group(1)), op, List.of(cmp.group(3)));
		}
		Matcher exists = EXISTS_TERM.matcher(atom);
		if (exists.matches()) {
			return new Atom.Label(labelKey(exists.group(1)), LabelOp.EXISTS, List.of());
		}
		return new Atom.Text(TextMatchers.text(atom));
	}

	/**
	 * A {@code prefix:value} term's atom, or null when this is not one — either because
	 * there is no prefix at all, or because the prefix is {@code label:}, which is not a
	 * field but the opening of a label requirement and so belongs to the branches after
	 * this one.
	 *
	 * <p>
	 * An unrecognised prefix <b>throws</b>. Left as a substring search it would look for
	 * a colon in a name that cannot contain one — zero rows, and no way to tell that from
	 * a genuinely empty result.
	 */
	private static Atom fieldTerm(String atom) {
		Matcher field = FIELD_TERM.matcher(atom);
		if (!field.matches()) {
			return null;
		}
		String prefix = field.group(1);
		String value = field.group(2);
		FilterField known = FIELDS.get(prefix);
		if (known != null) {
			return new Atom.Field(known, TextMatchers.text(value));
		}
		if ("status".equals(prefix)) {
			return new Atom.Status(TextMatchers.status(value));
		}
		if (!"label".equals(prefix)) {
			throw new FilterError(
					"Unknown field “" + prefix + ":” — the fields are name:, ns:, kind:, status: and label:");
		}
		return null;
	}

	private static Atom setAtom(Matcher m) {
		List<String> values = new ArrayList<>();
		for (String value : Arrays.asList(m.group(3).split(",", -1))) {
			String trimmed = value.strip();
			if (!trimmed.isEmpty()) {
				values.add(trimmed);
			}
		}
		if (values.isEmpty()) {
			throw new FilterError("“" + m.group(1) + " " + m.group(2)
					+ " ()” has no values — list at least one inside the parentheses");
		}
		LabelOp op = "in".equals(m.group(2)) ? LabelOp.IN : LabelOp.NOTIN;
		return new Atom.Label(labelKey(m.group(1)), op, List.copyOf(values));
	}

	private static String labelKey(String raw) {
		String key = raw.startsWith(LABEL_PREFIX) ? raw.substring(LABEL_PREFIX.length()) : raw;
		if (key.isEmpty()) {
			throw new FilterError("Missing label key — write it as key=value");
		}
		if (!LABEL_KEY.matcher(key).matches() || !LABEL_KEY_END.matcher(key).find()) {
			throw new FilterError("“" + key + "” is not a valid label key");
		}
		return key;
	}

}

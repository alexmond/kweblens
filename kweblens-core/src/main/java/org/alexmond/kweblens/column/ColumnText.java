package org.alexmond.kweblens.column;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The four primitives every column value is built out of, and the one place they are
 * allowed to disagree with the SPA.
 *
 * <p>
 * These are ports of {@code columns.ts}'s {@code str}, {@code dash} and {@code kube.ts}'s
 * {@code toNum} / {@code gib} / {@code parseMemBytes}. They are ports rather than
 * re-inventions because the gate on this package is a <b>parity</b> gate: the same object
 * has to produce the same string on both sides, and every divergence has to be one
 * somebody chose. The two that were chosen are recorded here.
 *
 * <h2>Numbers</h2>
 *
 * JavaScript has one number type and prints {@code 3.0} as {@code "3"}. Jackson gives
 * Java an {@code Integer} for {@code 3} and a {@code Double} for {@code 3.0}, and
 * {@code String.valueOf} would print the second as {@code "3.0"} — a difference the
 * cluster never intended and the reader would read as a different value. So an integral
 * floating-point value is printed without its fraction, which is exactly what
 * {@code String(v)} does.
 *
 * <h2>Missing</h2>
 *
 * {@link #MISSING} is an em dash, and it is not the same claim as an empty string: it
 * says <em>we have nothing here</em>, where a blank cell reads as <em>this is empty</em>.
 * That is the rule {@code ListProjection}'s withheld values already follow, and it is why
 * {@link #dash(String)} exists rather than every column remembering to do it.
 */
final class ColumnText {

	/** What a column renders when the object carries no value for it. */
	static final String MISSING = "—";

	private static final double BYTES_PER_GIB = 1024D * 1024D * 1024D;

	/** {@code kube.ts}'s quantity pattern, character for character. */
	private static final Pattern QUANTITY = Pattern.compile("^([0-9.]+)\\s*([A-Za-z]*)$");

	private ColumnText() {
	}

	/**
	 * {@code String(v)} for the loosely-typed values a cluster object carries; an absent
	 * value is the empty string, never {@code "null"}.
	 * @param value the value read out of the object
	 * @return its text
	 */
	static String str(Object value) {
		if (value == null) {
			return "";
		}
		if (value instanceof Double number) {
			return number(number);
		}
		if (value instanceof Float || value instanceof BigDecimal) {
			return number(((Number) value).doubleValue());
		}
		return String.valueOf(value);
	}

	/** {@link #MISSING} for an empty string, the string itself otherwise. */
	static String dash(String text) {
		return text.isEmpty() ? MISSING : text;
	}

	/**
	 * {@link #str(Object)} then {@link #dash(String)} — the commonest column there is.
	 */
	static String text(Object value) {
		return dash(str(value));
	}

	/**
	 * {@code toNum}: the value when it is a number, {@code 0} when it is anything else —
	 * including absent. A count that the API server omits because it is zero and a count
	 * it omits because nothing has been computed are the same word to the SPA, and this
	 * keeps them the same word here.
	 * @param value the value read out of the object
	 * @return its numeric text
	 */
	static String num(Object value) {
		return (value instanceof Number number) ? number(number.doubleValue()) : "0";
	}

	/**
	 * JavaScript truthiness, because the SPA's conditionals are written as {@code v ? a :
	 * b} over values a cluster can send as a boolean, a string or nothing at all. Absent,
	 * {@code false}, {@code 0} and {@code ""} are false; everything else — including the
	 * string {@code "false"}, exactly as in JavaScript — is true.
	 * @param value the value read out of the object
	 * @return whether the SPA would take the first branch
	 */
	static boolean truthy(Object value) {
		if (value == null || Boolean.FALSE.equals(value)) {
			return false;
		}
		if (value instanceof CharSequence text) {
			return text.length() > 0;
		}
		if (value instanceof Number number) {
			return number.doubleValue() != 0D && !Double.isNaN(number.doubleValue());
		}
		return true;
	}

	/** A ratio of two {@link #num(Object)} readings, e.g. {@code 2/3}. */
	static String ratio(Object ready, Object total) {
		return num(ready) + "/" + num(total);
	}

	/**
	 * A Kubernetes memory quantity in bytes, or {@code 0} when it is absent or does not
	 * parse. Mirrors {@code kube.ts}'s {@code parseMemBytes}, units included.
	 * @param quantity the quantity, e.g. {@code 4Gi}
	 * @return the byte count
	 */
	static double memoryBytes(String quantity) {
		if (quantity == null || quantity.isBlank()) {
			return 0;
		}
		Matcher matcher = QUANTITY.matcher(quantity.trim());
		if (!matcher.matches()) {
			return 0;
		}
		try {
			return Double.parseDouble(matcher.group(1)) * unit(matcher.group(2));
		}
		catch (NumberFormatException ex) {
			return 0;
		}
	}

	/** Bytes as gibibytes to one decimal place, e.g. {@code 7.6Gi}. */
	static String gib(double bytes) {
		return String.format(Locale.ROOT, "%.1f", bytes / BYTES_PER_GIB) + "Gi";
	}

	private static double unit(String unit) {
		return switch (unit) {
			case "" -> 1D;
			case "Ki" -> 1024D;
			case "Mi" -> 1024D * 1024D;
			case "Gi" -> BYTES_PER_GIB;
			case "Ti" -> BYTES_PER_GIB * 1024D;
			case "Pi" -> BYTES_PER_GIB * 1024D * 1024D;
			case "k", "K" -> 1e3D;
			case "M" -> 1e6D;
			case "G" -> 1e9D;
			case "T" -> 1e12D;
			default -> 1D;
		};
	}

	private static String number(double value) {
		if (Double.isFinite(value) && value == Math.rint(value) && Math.abs(value) < 1e15D) {
			return String.valueOf((long) value);
		}
		return String.valueOf(value);
	}

}

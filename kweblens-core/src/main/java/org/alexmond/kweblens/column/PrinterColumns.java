package org.alexmond.kweblens.column;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import org.alexmond.kweblens.resource.PrinterColumn;

/**
 * A CRD's {@code additionalPrinterColumns}, evaluated.
 *
 * <p>
 * {@code CrdService.printerColumns} has always returned {@code (name, jsonPath, type)};
 * what was missing was anything server-side that could <em>evaluate</em> the path, so a
 * consumer without a JavaScript engine could show a CRD's own columns. This is that, and
 * <b>it is what makes a CRD's columns appear in the terminal with no code change</b> —
 * the kind is discovered, its columns are fetched, and the paths are walked.
 *
 * <h2>The implemented subset, stated</h2>
 *
 * This matches the SPA's evaluator, which is itself a subset of JSONPath, and nothing
 * more:
 * <ul>
 * <li>a dotted path with an optional leading dot — {@code .status.phase},
 * {@code .spec.replicas};
 * <li>numeric indexing of an array segment — {@code .spec.versions.0.name};
 * <li><b>one</b> equality filter, anywhere in the path —
 * {@code .status.conditions[?(@.type=="Ready")].status} — where the compared value is a
 * quoted string and the comparison is by string identity, so {@code == "5"} does not
 * match a numeric {@code 5}.
 * </ul>
 *
 * <h2>What is not implemented, and what happens instead</h2>
 *
 * Wildcards ({@code [*]}), slices, recursive descent ({@code ..}), functions, arithmetic,
 * a second filter and any non-equality comparison all fall through to being read as
 * literal path segments, find nothing, and render {@link ColumnText#MISSING}. That is the
 * SPA's behaviour too, and it is the right failure: a column that says "nothing here" is
 * readable, where a column that says {@code [object Object]} is not.
 *
 * <p>
 * <b>One deliberate divergence.</b> When a path resolves to an object or an array, the
 * SPA prints JavaScript's {@code String(v)} — {@code [object Object]}, or an array's
 * elements comma-joined without their braces. This renders {@link ColumnText#MISSING}
 * instead. That is not parity and it is not an oversight: neither of those strings tells
 * the reader anything, and one of them is a lie about the shape of the value. The parity
 * corpus therefore carries no such case, and this paragraph is the record of why.
 */
public final class PrinterColumns {

	/** {@code columns.ts}'s filter pattern, translated segment for segment. */
	private static final Pattern FILTER = Pattern
		.compile("^(.*?)\\[\\?\\(@\\.([\\w.]+)\\s*==\\s*[\"']([^\"']+)[\"']\\)\\]\\.?(.*)$");

	private PrinterColumns() {
	}

	/**
	 * Turn a CRD's declared printer columns into columns that can render a row.
	 * @param declared what {@code CrdService.printerColumns} returned
	 * @param clock the moment a {@code date} column measures its age against; supplied
	 * rather than read, so a test of a date column is a test and not a race
	 * @return one column per declared column, in the CRD's order
	 */
	public static List<Column> of(List<PrinterColumn> declared, Clock clock) {
		if (declared == null || declared.isEmpty()) {
			return List.of();
		}
		List<Column> columns = new ArrayList<>(declared.size());
		for (PrinterColumn column : declared) {
			String key = (column.jsonPath() != null && !column.jsonPath().isEmpty()) ? column.jsonPath()
					: column.name();
			columns.add(new Column(key, column.name(),
					(object) -> render(object, column.jsonPath(), column.type(), clock.instant())));
		}
		return List.copyOf(columns);
	}

	/**
	 * The cell text for one declared column.
	 * @param object the object
	 * @param jsonPath the declared path
	 * @param type the declared type; only {@code date} changes the rendering
	 * @param now the moment a {@code date} column is measured against
	 * @return the cell text, {@link ColumnText#MISSING} when the path finds nothing
	 */
	public static String render(GenericKubernetesResource object, String jsonPath, String type, Instant now) {
		Object value = resolve(object, jsonPath);
		if (value == null || value instanceof Map || value instanceof List) {
			return ColumnText.MISSING;
		}
		if ("date".equals(type)) {
			return Ages.of(ColumnText.str(value), now);
		}
		if (value instanceof Boolean flag) {
			return flag ? "True" : "False";
		}
		return ColumnText.str(value);
	}

	private static Object resolve(GenericKubernetesResource object, String jsonPath) {
		String path = (jsonPath != null) ? jsonPath : "";
		if (path.startsWith(".")) {
			path = path.substring(1);
		}
		if (path.isEmpty()) {
			return null;
		}
		Matcher filter = FILTER.matcher(path);
		if (!filter.matches()) {
			return ObjectPath.read(object, path);
		}
		Object candidates = ObjectPath.read(object, filter.group(1));
		if (!(candidates instanceof List<?> entries)) {
			return null;
		}
		for (Object entry : entries) {
			if (filter.group(3).equals(ObjectPath.descend(entry, filter.group(2)))) {
				return ObjectPath.descend(entry, filter.group(4));
			}
		}
		return null;
	}

}

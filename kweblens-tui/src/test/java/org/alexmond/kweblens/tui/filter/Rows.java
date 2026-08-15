package org.alexmond.kweblens.tui.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The fixtures the ported corpus runs against — the same objects, with the same names,
 * namespaces, labels and states, as {@code kweblens-ui/src/objectFilter.test.ts}.
 *
 * <p>
 * Shared so that every file below asserts on the <b>same</b> fleet the TypeScript does. A
 * Java test that only exercises cases someone thought of is a re-implementation, not a
 * port; the value of these cases is that they were written against the other
 * implementation.
 */
final class Rows {

	/** The five-object fleet every text and label case runs against. */
	static final List<FilterRow> FLEET = List.of(
			pod("web-1", "prod", Map.of("app", "web", "tier", "frontend", "env", "prod")),
			pod("web-2", "prod", Map.of("app", "web", "tier", "frontend", "env", "prod")),
			pod("db-0", "prod", Map.of("app", "db", "tier", "backend")),
			pod("web-canary", "staging", Map.of("app", "web", "env", "staging")),
			pod("cache", "kube-system", Map.of()));

	/**
	 * One of each tone, plus the pair whose labels share a stem — the substring trap —
	 * and one row the server reached no verdict about.
	 */
	static final List<FilterRow> STATED = List.of(stated("web-1", "Running"), stated("web-2", "Running"),
			stated("job-done", "Completed"), stated("job-run", "Complete"), stated("sched-1", "Pending"),
			stated("crash-1", "CrashLoopBackOff"), stated("pull-1", "ImagePullBackOff"),
			stated("svc-1", "No endpoints"), pod("unjudged", "prod", Map.of()));

	private Rows() {
	}

	static FilterRow pod(String name, String namespace) {
		return pod(name, namespace, Map.of());
	}

	static FilterRow pod(String name, String namespace, Map<String, String> labels) {
		return new FilterRow(name, namespace, "Pod", labels, "");
	}

	/** A row as the server ships it: the state label is computed, never typed. */
	static FilterRow stated(String name, String state) {
		return new FilterRow(name, "prod", "Pod", Map.of(), state);
	}

	/** Names of the objects a query keeps, in order — the shape every case asserts on. */
	static List<String> kept(String query, List<FilterRow> rows) {
		ParsedFilter filter = ObjectFilter.parse(query);
		List<String> names = new ArrayList<>();
		for (FilterRow row : rows) {
			if (filter.matches(row)) {
				names.add(row.name());
			}
		}
		return names;
	}

}

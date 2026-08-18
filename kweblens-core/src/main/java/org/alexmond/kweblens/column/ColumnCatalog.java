package org.alexmond.kweblens.column;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import org.alexmond.kweblens.resource.ResourceDescriptor;

/**
 * Which kinds have server-computed column values, and what they are.
 *
 * <p>
 * <b>The first tranche is five kinds</b> — Pods, Deployments, Nodes, Services and Events
 * — because those are the five a terminal opens first, and because a migration motivated
 * by a consumer is the condition GH#148 set before any of this could start. It is not all
 * 28 kinds the SPA draws columns for, and the ones that are missing are missing on
 * purpose: adding a kind here is a list literal and a row in the parity corpus, so the
 * next tranche costs what it should rather than what a rewrite would.
 *
 * <h2>Keyed by group and kind, not by the SPA's id</h2>
 *
 * The SPA addresses a kind as {@code deployments}; the TUI resolves one from API
 * discovery, where the id is whatever the discovery path assembled. Both agree on
 * {@code (group, kind)}, because that is what the API server itself publishes, so that is
 * the key. The SPA id is carried alongside so the parity corpus — which is written in the
 * SPA's vocabulary, since the SPA is the thing being matched — can be read from both
 * sides.
 *
 * <h2>What is not here</h2>
 *
 * The {@code Status} column. It is already computed once per list by
 * {@code ObjectStates.forList} (GH#360) and it needs a {@code StatusContext} that a
 * per-object function cannot open. Widths, order and hidden-by-default are not here
 * either: a terminal sizes columns from its own geometry and a browser from its own, and
 * a server that shipped pixel widths would be answering a question it cannot see.
 */
public final class ColumnCatalog {

	/**
	 * What a cell renders when there is no value for it — an em dash, and never an empty
	 * string. "We have nothing here" and "this is empty" are different claims, which is
	 * the same rule {@code ListProjection}'s withheld values follow. Exposed because a
	 * consumer drawing a row shorter than its heading list has to say the same thing.
	 */
	public static final String MISSING_CELL = ColumnText.MISSING;

	private static final Map<String, Kind> BY_ID = index();

	private static final Map<String, Kind> BY_GROUP_KIND = byGroupKind();

	private ColumnCatalog() {
	}

	/**
	 * The columns for the kind a descriptor names, empty when this tranche does not cover
	 * it. An uncovered kind is a table with the framework's own columns and nothing else,
	 * which is what every kind looked like before this.
	 * @param descriptor the kind
	 * @return its columns, never null
	 */
	public static List<Column> forDescriptor(ResourceDescriptor descriptor) {
		if (descriptor == null) {
			return List.of();
		}
		Kind kind = BY_GROUP_KIND.get(key(descriptor.group(), descriptor.kind()));
		return (kind != null) ? kind.columns() : List.of();
	}

	/**
	 * The columns for a SPA resource id such as {@code deployments}.
	 * @param resourceId the id
	 * @return its columns, never null
	 */
	public static List<Column> forResourceId(String resourceId) {
		Kind kind = BY_ID.get(resourceId);
		return (kind != null) ? kind.columns() : List.of();
	}

	/** Every SPA resource id this tranche covers, in the order the tranche lists them. */
	public static List<String> coveredResourceIds() {
		return List.copyOf(BY_ID.keySet());
	}

	/**
	 * The cell text for every column of {@code columns}, in order — the call a consumer
	 * makes once per object and then holds instead of the object.
	 * @param columns the kind's columns
	 * @param object the object
	 * @return one string per column, never null
	 */
	public static List<String> values(List<Column> columns, GenericKubernetesResource object) {
		if (columns.isEmpty()) {
			return List.of();
		}
		List<String> values = new ArrayList<>(columns.size());
		for (Column column : columns) {
			values.add(column.render(object));
		}
		return values;
	}

	private static Map<String, Kind> index() {
		Map<String, Kind> kinds = new LinkedHashMap<>();
		put(kinds, new Kind("pods", "", "Pod", PodColumns.columns()));
		put(kinds, new Kind("deployments", "apps", "Deployment", DeploymentColumns.columns()));
		put(kinds, new Kind("nodes", "", "Node", NodeColumns.columns()));
		put(kinds, new Kind("services", "", "Service", ServiceColumns.columns()));
		put(kinds, new Kind("events", "", "Event", EventColumns.columns()));
		// Not Map.copyOf: it does not keep insertion order, and the tranche's order is
		// what coveredResourceIds() reports and what a reader checks it against.
		return Collections.unmodifiableMap(kinds);
	}

	private static void put(Map<String, Kind> kinds, Kind kind) {
		kinds.put(kind.resourceId(), kind);
	}

	private static Map<String, Kind> byGroupKind() {
		Map<String, Kind> kinds = new LinkedHashMap<>();
		for (Kind kind : BY_ID.values()) {
			kinds.put(key(kind.group(), kind.kind()), kind);
		}
		// Not Map.copyOf: it does not keep insertion order, and the tranche's order is
		// what coveredResourceIds() reports and what a reader checks it against.
		return Collections.unmodifiableMap(kinds);
	}

	private static String key(String group, String kind) {
		return ((group != null) ? group : "") + "/" + kind;
	}

	/**
	 * One covered kind.
	 *
	 * @param resourceId the SPA's id for it
	 * @param group the API group, {@code ""} for the core group
	 * @param kind the Kubernetes kind
	 * @param columns its kind-specific columns, in the order the SPA lists them less the
	 * {@code Status} column
	 */
	private record Kind(String resourceId, String group, String kind, List<Column> columns) {
	}

}

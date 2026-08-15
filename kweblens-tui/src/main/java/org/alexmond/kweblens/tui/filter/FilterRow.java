package org.alexmond.kweblens.tui.filter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMeta;

import org.alexmond.kweblens.health.ObjectState;

/**
 * The five things the filter grammar can ask about one object — and the whole seam
 * between this package and the screen.
 *
 * <p>
 * <b>Why a projection and not the object.</b> A predicate over
 * {@link GenericKubernetesResource} would have to re-answer "what is this object's state"
 * on every keystroke, and the answer does not live on the object: it is computed by
 * {@code ObjectStates} once per page, positionally, alongside the list. Passing the
 * verdict in rather than looking it up here is what keeps one status context per page
 * (#362's rule) instead of one per row per keystroke.
 *
 * <p>
 * <b>Every field is non-null, and an absent one is {@code ""}.</b> The TypeScript this is
 * ported from reads {@code o.metadata?.name ?? ''}; a null here would be a
 * {@link NullPointerException} inside a keystroke handler instead of a row that simply
 * does not match. {@code state} is {@code ""} for a kind kweblens reaches no verdict
 * about — which is <b>not</b> a state, and {@link Atom.Status} refuses to select on it at
 * all rather than letting a permissive pattern claim it.
 *
 * @param name {@code metadata.name}, or {@code ""}
 * @param namespace {@code metadata.namespace}, or {@code ""} for a cluster-scoped object
 * @param kind the object's kind, or {@code ""}
 * @param labels {@code metadata.labels}; empty, never null. A key may map to a null value
 * — a YAML {@code key:} with nothing after it — and that is a present key with no value,
 * exactly as it is in the browser.
 * @param state the label {@code ObjectStates} computed, or {@code ""} when it computed
 * none. The vocabulary is <b>open</b>: cluster values pass straight through it, so
 * nothing here may switch exhaustively over it.
 */
public record FilterRow(String name, String namespace, String kind, Map<String, String> labels, String state) {

	public FilterRow {
		name = orEmpty(name);
		namespace = orEmpty(namespace);
		kind = orEmpty(kind);
		labels = (labels != null) ? Collections.unmodifiableMap(new LinkedHashMap<>(labels)) : Map.of();
		state = orEmpty(state);
	}

	/**
	 * The row for one listed object and the verdict the data source returned for it.
	 *
	 * <p>
	 * The two arguments come from the same call — {@code ClusterDataSource.list} hands
	 * back a page and {@code ClusterDataSource.states} hands back one verdict per element
	 * of it, in order. Pair them by index; a verdict from a different page describes a
	 * different object.
	 * @param object the listed object
	 * @param state its verdict, empty when nothing judged it
	 */
	public static FilterRow of(GenericKubernetesResource object, Optional<ObjectState> state) {
		ObjectMeta metadata = (object != null) ? object.getMetadata() : null;
		String label = (state != null) ? state.map(ObjectState::label).orElse("") : "";
		if (metadata == null) {
			return new FilterRow("", "", kindOf(object), Map.of(), label);
		}
		return new FilterRow(metadata.getName(), metadata.getNamespace(), kindOf(object), metadata.getLabels(), label);
	}

	private static String kindOf(GenericKubernetesResource object) {
		return (object != null) ? orEmpty(object.getKind()) : "";
	}

	private static String orEmpty(String value) {
		return (value != null) ? value : "";
	}

}

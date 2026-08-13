package org.alexmond.kweblens.web.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import org.alexmond.kweblens.health.ObjectState;
import org.alexmond.kweblens.health.StatusContext;
import org.alexmond.kweblens.health.StatusVocabulary;

/**
 * Turns a raw object into a <b>list row</b>: drops the fields no list column renders, and
 * adds the one field the cluster does not have — the object's state in the same
 * vocabulary an overview card counts.
 *
 * <p>
 * <b>Why here and not in the access layer.</b> {@code ResourceService.listRaw} is shared
 * by the health checks, the overviews, {@code RelationService} and the MCP tools, and
 * several of them legitimately need exactly what this removes — the config-usage check
 * compares a ConfigMap's keys against what the pods mount, and a diagnosis is allowed to
 * say a Secret is missing a key. Projecting deeper would break those. So this sits at the
 * web boundary, for the same reason {@code ToolRedaction} sits at the MCP one: the rule
 * belongs to the consumer, not to the data.
 *
 * <p>
 * <b>What goes, and why it is safe.</b> {@code managedFields} is per-manager
 * field-ownership bookkeeping; nothing in the SPA renders it, and the YAML tab fetches
 * its own text through a different endpoint, so removing it here cannot empty that view.
 * It is 37-48% of a real pods or replicasets payload.
 *
 * <p>
 * A ConfigMap's or Secret's {@code data} is 96-99% of those payloads and the list shows
 * only a key <em>count</em> ({@code columns.ts}'s {@code keys}). The keys are therefore
 * <b>kept</b> and only the values dropped, so the count — and any future column that
 * names the keys — still has what it needs. A value becomes JSON {@code null}, which is
 * distinguishable from a genuinely empty value ({@code ""}); the drawer refetches the
 * whole object and renders the real values, and until it arrives the UI shows a dash
 * rather than inventing an empty one.
 *
 * <p>
 * The value strip is deliberately restricted to ConfigMap and Secret rather than applied
 * to any top-level {@code data}: on a custom resource {@code data} can be the whole spec,
 * and a CRD's printer columns are free to render it.
 *
 * <p>
 * This is not a redaction and must not be described as one. Per ADR-001 the operator may
 * read any Secret they can reach, and the drawer still shows every value. What changes is
 * that listing a kind no longer pushes every Secret in the cluster into the browser.
 *
 * <p>
 * <b>What is added.</b> {@code kweblensState} — {@code {"label":…,"tone":…}} — the
 * object's state as {@link StatusVocabulary} computes it, which is the same call the
 * overview card tallies its numbers from. It is the reason a {@code status:} filter in
 * the browser can select <em>exactly</em> the objects a card counted instead of a
 * similar-looking set (GH#336/#337); the alternative, a predicate transcribed into
 * TypeScript, cannot even be written for the kinds whose verdict needs a second
 * collection. A kind with no state gets no field at all rather than a null one, because
 * "not examined" is not a state and must not be selectable as one.
 *
 * <p>
 * <b>The context travels with the call, not with the object</b> (GH#340). Four kinds —
 * Service, PersistentVolumeClaim, ConfigMap, Secret — have a verdict that needs a second
 * collection, and this class must not go and fetch it: a projection runs once per row, so
 * a fetch here would turn one list request into N. The caller opens a
 * {@link StatusContext} once and passes the same one down every row.
 *
 * <p>
 * Here rather than in the two controllers because the {@code objects} list and the
 * {@code objects/watch} stream are separate code paths feeding one table: a field added
 * to only one of them would be erased by the first watch event to arrive, exactly as a
 * strip applied to only one of them would be undone.
 */
final class ListProjection {

	private static final List<String> VALUE_FIELDS = List.of("data", "stringData", "binaryData");

	private static final List<String> BULKY_KINDS = List.of("ConfigMap", "Secret");

	private ListProjection() {
	}

	/**
	 * Project every object in a list. Mutates and returns the same instances: they were
	 * deserialised for this request by {@code listRaw} and are not shared or cached.
	 */
	static List<GenericKubernetesResource> forList(List<GenericKubernetesResource> resources, StatusContext context) {
		if (resources == null) {
			return List.of();
		}
		resources.forEach((resource) -> forList(resource, context));
		return resources;
	}

	/**
	 * Project one object — the watch stream's per-event equivalent of {@link #forList}.
	 */
	static GenericKubernetesResource forList(GenericKubernetesResource resource, StatusContext context) {
		if (resource == null) {
			return null;
		}
		if (resource.getMetadata() != null) {
			resource.getMetadata().setManagedFields(null);
		}
		if (BULKY_KINDS.contains(resource.getKind())) {
			VALUE_FIELDS.forEach((field) -> keysOnly(resource, field));
		}
		ObjectState state = StatusVocabulary.state(resource.getKind(), resource, context);
		if (state != null) {
			resource.getAdditionalProperties().put(StatusVocabulary.FIELD, state);
		}
		return resource;
	}

	@SuppressWarnings("unchecked")
	private static void keysOnly(GenericKubernetesResource resource, String field) {
		Object raw = resource.getAdditionalProperties().get(field);
		if (!(raw instanceof Map<?, ?> map)) {
			return;
		}
		Map<String, Object> stripped = new LinkedHashMap<>();
		((Map<String, Object>) map).keySet().forEach((key) -> stripped.put(key, null));
		resource.getAdditionalProperties().put(field, stripped);
	}

}

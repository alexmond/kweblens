package org.alexmond.kweblens.resource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Secret;

/**
 * The rules every relation obeys, in one place: how a result is bounded, what is stripped
 * before it leaves the server, and how selectors and pod-template labels are read.
 *
 * <p>
 * These are shared rather than repeated because they are the parts that are dangerous to
 * get subtly wrong in one join and right in the others — a relation that forgot to bound
 * its list, or that returned a Secret with its {@code data} attached, would not look
 * broken.
 */
final class RelationSupport {

	/**
	 * Cap on related objects returned per relation. Bounded because the reverse joins
	 * ("what mounts this Secret", "which pods run on this node") have no server-side
	 * selector and must list a collection; a namespace with thousands of pods would
	 * otherwise turn opening a drawer into a large transfer. Truncation is reported
	 * rather than hidden.
	 */
	static final int MAX_ITEMS = 100;

	private RelationSupport() {
	}

	/** Cap a result and report whether it was capped. */
	static Relation bound(List<?> items) {
		List<?> capped = (items.size() <= MAX_ITEMS) ? items : items.subList(0, MAX_ITEMS);
		return Relation.of(List.copyOf(capped.stream().map(RelationSupport::forDisplay).toList()),
				items.size() > MAX_ITEMS);
	}

	/**
	 * Trim a related object down to what a table row may see.
	 *
	 * <p>
	 * {@code managedFields} goes because nothing reads server-side field-management
	 * bookkeeping and it is bulky: measured on a real Service it was <b>35% of the whole
	 * detail payload</b>, and that multiplies by the item cap.
	 *
	 * <p>
	 * A Secret's {@code data}/{@code stringData} go for a different reason. A relation
	 * exists to say <em>which</em> object is involved, never what is in it — "this
	 * ServiceAccount uses the {@code regcred} pull secret" is the whole answer, and the
	 * fields are dropped entirely rather than emptied so the payload cannot be misread as
	 * "the Secret has no keys". No relation returns a Secret today; the guard is here so
	 * that adding one later cannot quietly become a disclosure.
	 */
	static Object forDisplay(Object item) {
		if (item instanceof HasMetadata resource && resource.getMetadata() != null) {
			resource.getMetadata().setManagedFields(null);
		}
		if (item instanceof Secret secret) {
			secret.setData(null);
			secret.setStringData(null);
		}
		if (item instanceof GenericKubernetesResource generic && "Secret".equals(generic.getKind())) {
			generic.getAdditionalProperties().remove("data");
			generic.getAdditionalProperties().remove("stringData");
		}
		return item;
	}

	/**
	 * The object's pod selector. Services carry a flat {@code spec.selector}; workloads
	 * nest it under {@code matchLabels}. Only {@code matchLabels} is supported —
	 * set-based {@code matchExpressions} cannot be passed to {@code withLabels()} without
	 * translating the full grammar, and every built-in workload controller writes
	 * matchLabels.
	 */
	static Map<String, String> selectorOf(GenericKubernetesResource object) {
		Object raw = object.get("spec", "selector");
		if (!(raw instanceof Map<?, ?> map)) {
			return Map.of();
		}
		Object nested = map.get("matchLabels");
		Map<?, ?> selector = (nested instanceof Map<?, ?> inner) ? inner : map;
		return stringMap(selector);
	}

	/**
	 * The labels the pods of this object carry — its own labels for a Pod, its pod
	 * template's for a workload. This is what a selector written by something else (a
	 * PDB, a Service) is matched against, and reading the wrong one is why a workload
	 * appears unprotected when it is not: a Deployment's own labels and its template's
	 * labels are routinely different.
	 */
	static Map<String, String> podLabelsOf(String kind, GenericKubernetesResource object) {
		if ("Pod".equals(kind)) {
			return (object.getMetadata() != null && object.getMetadata().getLabels() != null)
					? object.getMetadata().getLabels() : Map.of();
		}
		Object template = object.get("spec", "template", "metadata", "labels");
		return (template instanceof Map<?, ?> map) ? stringMap(map) : Map.of();
	}

	/**
	 * Whether a selector matches a set of labels. An <b>empty</b> selector matches
	 * everything — that is the API's own rule for a PodDisruptionBudget and getting it
	 * backwards would report "nothing protects this workload" about the one budget that
	 * protects every pod in the namespace.
	 */
	static boolean matches(Map<String, String> selector, Map<String, String> labels) {
		return selector.entrySet().stream().allMatch((e) -> e.getValue().equals(labels.get(e.getKey())));
	}

	/**
	 * The {@code group} half of an {@code apiVersion} ({@code ""} for the core group).
	 */
	static String groupOf(String apiVersion) {
		if (apiVersion == null || apiVersion.isBlank()) {
			return "";
		}
		int slash = apiVersion.indexOf('/');
		return (slash < 0) ? "" : apiVersion.substring(0, slash);
	}

	/** The {@code version} half of an {@code apiVersion}. */
	static String versionOf(String apiVersion) {
		if (apiVersion == null || apiVersion.isBlank()) {
			return "v1";
		}
		int slash = apiVersion.indexOf('/');
		return (slash < 0) ? apiVersion : apiVersion.substring(slash + 1);
	}

	private static Map<String, String> stringMap(Map<?, ?> raw) {
		Map<String, String> out = new LinkedHashMap<>();
		for (var entry : raw.entrySet()) {
			if (entry.getKey() instanceof String key && entry.getValue() instanceof String value) {
				out.put(key, value);
			}
		}
		return out;
	}

}

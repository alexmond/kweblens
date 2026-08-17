package org.alexmond.kweblens.column;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMeta;

/**
 * Reads a dotted path out of a cluster object — {@code status.nodeInfo.kubeletVersion},
 * {@code spec.clusterIP}, {@code metadata.labels}.
 *
 * <p>
 * <b>This is the half of the column work that is not a migration.</b> Thirty-five of the
 * SPA's column renderers do exactly one thing: walk a path and stringify what is at the
 * end of it. There is no logic in those to drift, so they do not need a server-side
 * <em>value</em> — they need an evaluator, once, here. A path is data; a closure per
 * column is code that has to be kept in step.
 *
 * <h2>A missing path is a missing value, not a failure</h2>
 *
 * Every step that cannot be taken yields {@code null}, which the caller renders as
 * {@link ColumnText#MISSING}. A cluster object is a partially-populated document by
 * design — a Pod with no {@code status.containerStatuses} yet is normal — so an absent
 * step is the common case and must not be an exception.
 *
 * <h2>metadata is not in the bag</h2>
 *
 * fabric8 models {@code metadata} as a typed {@link ObjectMeta} and leaves everything
 * else in {@code getAdditionalProperties()}. A path evaluator that only looked in the bag
 * would silently answer {@code null} for every {@code .metadata.*} path — the single
 * commonest thing a CRD's {@code additionalPrinterColumns} asks for. So {@code metadata}
 * is projected on demand, and the projection is deliberately a <b>named subset</b>: the
 * fields a printer column or a table has ever wanted. Anything outside it reads as
 * absent, which is the honest answer for a field this evaluator does not carry, and the
 * subset is one line to extend.
 */
public final class ObjectPath {

	private ObjectPath() {
	}

	/**
	 * The value at {@code path}, or {@code null} when any step of it is absent.
	 * @param object the object, may be null
	 * @param path a dotted path with no leading dot, e.g. {@code status.phase}; an empty
	 * path yields null because a whole object is not a cell value
	 * @return the value, or null
	 */
	public static Object read(GenericKubernetesResource object, String path) {
		if (object == null || path == null || path.isEmpty()) {
			return null;
		}
		int dot = path.indexOf('.');
		String head = (dot < 0) ? path : path.substring(0, dot);
		Object root = root(object, head);
		return (dot < 0) ? root : descend(root, path.substring(dot + 1));
	}

	/**
	 * The value at {@code path} inside an already-extracted value — the second half of a
	 * filtered printer-column path, and how a test reaches a nested map without an object
	 * around it.
	 * @param value the value to descend into, may be null
	 * @param path a dotted path; an empty path yields {@code value} itself, which is what
	 * {@code columns.ts}'s {@code getDotted} does and what a filter with nothing after it
	 * relies on
	 * @return the value, or null
	 */
	public static Object descend(Object value, String path) {
		if (path == null || path.isEmpty()) {
			return value;
		}
		Object current = value;
		for (String part : path.split("\\.", -1)) {
			if (current instanceof Map<?, ?> map) {
				current = map.get(part);
			}
			else if (current instanceof List<?> list) {
				// An array is an object in JavaScript, so `getDotted` indexes one with a
				// numeric segment. Matching that costs four lines and stops a printer
				// column like `.spec.versions.0.name` from being silently blank.
				current = element(list, part);
			}
			else {
				return null;
			}
		}
		return current;
	}

	/**
	 * The value at {@code path} as a list, empty when it is absent or is not one — the
	 * {@code (x as Any[]) ?? []} the SPA writes at the top of every aggregating renderer.
	 * @param object the object
	 * @param path the dotted path
	 * @return the list, never null
	 */
	public static List<?> list(GenericKubernetesResource object, String path) {
		return (read(object, path) instanceof List<?> list) ? list : List.of();
	}

	/**
	 * The value at {@code path} as a map, empty when it is absent or is not one.
	 * @param object the object
	 * @param path the dotted path
	 * @return the map, never null
	 */
	public static Map<?, ?> map(GenericKubernetesResource object, String path) {
		return (read(object, path) instanceof Map<?, ?> map) ? map : Map.of();
	}

	/**
	 * A field of an element of one of those lists, e.g. a container status's
	 * {@code ready}.
	 */
	public static Object field(Object element, String name) {
		return (element instanceof Map<?, ?> map) ? map.get(name) : null;
	}

	private static Object element(List<?> list, String part) {
		try {
			int index = Integer.parseInt(part);
			return (index >= 0 && index < list.size()) ? list.get(index) : null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static Object root(GenericKubernetesResource object, String head) {
		Object direct = object.getAdditionalProperties().get(head);
		if (direct != null) {
			return direct;
		}
		return switch (head) {
			case "metadata" -> metadata(object.getMetadata());
			case "kind" -> object.getKind();
			case "apiVersion" -> object.getApiVersion();
			default -> null;
		};
	}

	/**
	 * The subset of {@link ObjectMeta} this evaluator carries. Extending it is a one-line
	 * change; guessing that it is complete is what makes a column quietly wrong.
	 */
	private static Map<String, Object> metadata(ObjectMeta metadata) {
		if (metadata == null) {
			return Map.of();
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		put(fields, "name", metadata.getName());
		put(fields, "namespace", metadata.getNamespace());
		put(fields, "uid", metadata.getUid());
		put(fields, "resourceVersion", metadata.getResourceVersion());
		put(fields, "generation", metadata.getGeneration());
		put(fields, "creationTimestamp", metadata.getCreationTimestamp());
		put(fields, "deletionTimestamp", metadata.getDeletionTimestamp());
		put(fields, "labels", metadata.getLabels());
		put(fields, "annotations", metadata.getAnnotations());
		return fields;
	}

	private static void put(Map<String, Object> fields, String key, Object value) {
		if (value != null) {
			fields.put(key, value);
		}
	}

}

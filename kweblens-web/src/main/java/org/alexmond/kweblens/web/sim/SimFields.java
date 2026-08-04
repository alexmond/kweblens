package org.alexmond.kweblens.web.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.FieldsV1;
import io.fabric8.kubernetes.api.model.ManagedFieldsEntry;

/**
 * {@code metadata.managedFields} — the field-ownership bookkeeping every
 * server-side-apply cluster writes onto every object, and the largest single thing the
 * simulator used to omit.
 *
 * <p>
 * It is not a detail. Measured on the live cluster it is <b>37-48% of a pod, deployment
 * or replicaset payload</b>, and its absence is most of why a simulated pod measured 739
 * bytes against a real one's 7.8 KB — the 50-500x error that
 * {@code docs/design/scale-measurements.md} had to correct. Any list-payload number taken
 * against objects without it is measuring the rig.
 *
 * <p>
 * The shape is faithful rather than merely bulky: one entry per manager that really
 * touches the kind (kubectl or Helm on apply, the controller manager on rollout, the
 * kubelet on status), each carrying a {@code fieldsV1} tree of {@code f:}-prefixed keys
 * mirroring the fields that manager owns. That matters because the tree's size is
 * proportional to the object's own field count, so a bigger object automatically carries
 * bigger managedFields, exactly as on a cluster.
 */
final class SimFields {

	/** Set-type entries are keyed by a JSON object, e.g. {@code k:{"name":"app"}}. */
	private static final String KEYED = "k:";

	private SimFields() {
	}

	/**
	 * One manager's entry. {@code paths} are pipe-separated field paths
	 * ({@code spec|containers|k:{"name":"app"}|image}); a segment already carrying its
	 * own prefix ({@code k:} or {@code .}) is emitted verbatim, anything else gets
	 * {@code f:}.
	 */
	static ManagedFieldsEntry entry(String manager, String operation, String time, List<String> paths) {
		ManagedFieldsEntry managed = new ManagedFieldsEntry();
		managed.setManager(manager);
		managed.setOperation(operation);
		managed.setApiVersion("v1");
		managed.setTime(time);
		managed.setFieldsType("FieldsV1");
		managed.setFieldsV1(tree(paths));
		return managed;
	}

	/** Same, but owning a subresource — how the kubelet's status writes are recorded. */
	static ManagedFieldsEntry statusEntry(String manager, String time, List<String> paths) {
		ManagedFieldsEntry managed = entry(manager, "Update", time, paths);
		managed.setSubresource("status");
		return managed;
	}

	/** The nested {@code f:} map for a set of paths. */
	static FieldsV1 tree(List<String> paths) {
		Map<String, Object> root = new LinkedHashMap<>();
		for (String path : paths) {
			Map<String, Object> node = root;
			for (String segment : path.split("\\|")) {
				node = child(node, key(segment));
			}
		}
		FieldsV1 fields = new FieldsV1();
		fields.setAdditionalProperties(root);
		return fields;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> child(Map<String, Object> node, String key) {
		Object existing = node.get(key);
		if (existing instanceof Map) {
			return (Map<String, Object>) existing;
		}
		Map<String, Object> created = new LinkedHashMap<>();
		node.put(key, created);
		return created;
	}

	private static String key(String segment) {
		return (segment.startsWith(KEYED) || ".".equals(segment)) ? segment : "f:" + segment;
	}

	/**
	 * The metadata paths every applied object carries, plus one path per label and
	 * annotation key. Real managedFields name each key individually, which is why an
	 * object with many labels has large managedFields — reproducing that is the point.
	 */
	static List<String> metadataPaths(Map<String, String> labels, Map<String, String> annotations) {
		List<String> paths = new ArrayList<>();
		paths.add("metadata|labels|.");
		labels.keySet().forEach((k) -> paths.add("metadata|labels|" + k));
		if (!annotations.isEmpty()) {
			paths.add("metadata|annotations|.");
			annotations.keySet().forEach((k) -> paths.add("metadata|annotations|" + k));
		}
		return paths;
	}

	/**
	 * The container-level paths a real apply records, per container name. Sixteen paths
	 * per container is not padding — it is what {@code kubectl apply} writes for a
	 * container with resources, probes and mounts, and it is why a two-container pod's
	 * managedFields are twice a one-container pod's.
	 */
	static List<String> containerPaths(String root, String container) {
		String base = root + "|containers|" + KEYED + "{\"name\":\"" + container + "\"}";
		return List.of(base + "|.", base + "|image", base + "|imagePullPolicy", base + "|name", base + "|resources|.",
				base + "|resources|limits|.", base + "|resources|limits|cpu", base + "|resources|limits|memory",
				base + "|resources|requests|.", base + "|resources|requests|cpu", base + "|resources|requests|memory",
				base + "|terminationMessagePath", base + "|terminationMessagePolicy", base + "|livenessProbe|.",
				base + "|livenessProbe|httpGet|.", base + "|livenessProbe|httpGet|path",
				base + "|livenessProbe|httpGet|port", base + "|livenessProbe|httpGet|scheme",
				base + "|livenessProbe|failureThreshold", base + "|livenessProbe|periodSeconds",
				base + "|livenessProbe|successThreshold", base + "|livenessProbe|timeoutSeconds",
				base + "|readinessProbe|.", base + "|readinessProbe|httpGet|.", base + "|readinessProbe|httpGet|path",
				base + "|readinessProbe|httpGet|port", base + "|volumeMounts|.", base + "|env|.");
	}

}

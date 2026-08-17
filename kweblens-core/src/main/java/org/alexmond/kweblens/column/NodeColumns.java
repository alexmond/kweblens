package org.alexmond.kweblens.column;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMeta;

/**
 * The Node list's kind-specific columns — fifteen of them, which is a sixth of the whole
 * SPA column table on its own.
 *
 * <p>
 * Nodes are where "compute it once" earns most of its keep, because most of these are not
 * reads at all: a role is a <em>prefix scan of the label map</em>, an address is a
 * <em>find by type</em>, the condition list is a <em>filter and join</em>, and the
 * capacity cell parses a memory quantity. Six different consumers of a Node list is six
 * chances for one of them to decide that a cordoned control-plane node has no roles.
 *
 * <p>
 * Two shapes here look like bugs and are faithful ports:
 * <ul>
 * <li>an address entry that exists but carries no {@code address} renders <b>empty</b>,
 * not {@link ColumnText#MISSING} — the SPA distinguishes "no entry of this type" from "an
 * entry with nothing in it", and only the first is a dash;
 * <li>{@code Schedulable} is the inverse of {@code spec.unschedulable}, which is the
 * field {@code kubectl cordon} flips, so an absent field reads {@code True}.
 * </ul>
 */
final class NodeColumns {

	private static final String ROLE_PREFIX = "node-role.kubernetes.io/";

	private static final String NODE_INFO = "status.nodeInfo.";

	private static final String SEPARATOR = ", ";

	private NodeColumns() {
	}

	static List<Column> columns() {
		List<Column> columns = new ArrayList<>(15);
		columns.add(new Column("roles", "Roles", NodeColumns::roles));
		columns
			.add(new Column("taints", "Taints", (node) -> String.valueOf(ObjectPath.list(node, "spec.taints").size())));
		columns.add(Column.path("version", "Version", NODE_INFO + "kubeletVersion"));
		columns.add(new Column("ip", "Internal IP", (node) -> address(node, "InternalIP")));
		columns.add(new Column("schedulable", "Schedulable", NodeColumns::schedulable));
		columns.add(new Column("conditions", "Conditions", NodeColumns::conditions));
		columns.add(new Column("ext-ip", "External IP", (node) -> address(node, "ExternalIP")));
		columns.add(Column.path("pod-capacity", "Pod Capacity", "status.capacity.pods"));
		columns.add(new Column("capacity", "Capacity", NodeColumns::capacity));
		columns.add(new Column("instance-type", "Instance Type",
				(node) -> label(node, "node.kubernetes.io/instance-type", "beta.kubernetes.io/instance-type")));
		columns.add(new Column("zone", "Zone",
				(node) -> label(node, "topology.kubernetes.io/zone", "failure-domain.beta.kubernetes.io/zone")));
		columns.add(Column.path("os-image", "OS Image", NODE_INFO + "osImage"));
		columns.add(Column.path("kernel", "Kernel", NODE_INFO + "kernelVersion"));
		columns.add(Column.path("runtime", "Container Runtime", NODE_INFO + "containerRuntimeVersion"));
		columns.add(Column.path("arch", "Architecture", NODE_INFO + "architecture"));
		return List.copyOf(columns);
	}

	/** Every {@code node-role.kubernetes.io/<role>} label, stripped of its prefix. */
	private static String roles(GenericKubernetesResource node) {
		List<String> roles = new ArrayList<>();
		for (Map.Entry<String, String> label : labels(node).entrySet()) {
			if (label.getKey().startsWith(ROLE_PREFIX)) {
				String role = label.getKey().substring(ROLE_PREFIX.length());
				if (!role.isEmpty()) {
					roles.add(role);
				}
			}
		}
		return ColumnText.dash(String.join(SEPARATOR, roles));
	}

	/** The first address of {@code type}, or a dash when the node reports none. */
	private static String address(GenericKubernetesResource node, String type) {
		for (Object entry : ObjectPath.list(node, "status.addresses")) {
			if (type.equals(ObjectPath.field(entry, "type"))) {
				return ColumnText.str(ObjectPath.field(entry, "address"));
			}
		}
		return ColumnText.MISSING;
	}

	private static String schedulable(GenericKubernetesResource node) {
		return ColumnText.truthy(ObjectPath.read(node, "spec.unschedulable")) ? "False" : "True";
	}

	/** Every condition currently True — pressure conditions show up here too. */
	private static String conditions(GenericKubernetesResource node) {
		List<String> types = new ArrayList<>();
		for (Object condition : ObjectPath.list(node, "status.conditions")) {
			if ("True".equals(ColumnText.str(ObjectPath.field(condition, "status")))) {
				types.add(ColumnText.str(ObjectPath.field(condition, "type")));
			}
		}
		return ColumnText.dash(String.join(SEPARATOR, types));
	}

	private static String capacity(GenericKubernetesResource node) {
		Map<?, ?> capacity = ObjectPath.map(node, "status.capacity");
		List<String> parts = new ArrayList<>(2);
		String cpu = ColumnText.str(capacity.get("cpu"));
		if (!cpu.isEmpty()) {
			parts.add(cpu + " CPU");
		}
		double memory = ColumnText.memoryBytes(ColumnText.str(capacity.get("memory")));
		if (memory != 0D) {
			parts.add(ColumnText.gib(memory));
		}
		return ColumnText.dash(String.join(SEPARATOR, parts));
	}

	/** The first of {@code names} the node carries a non-empty value for. */
	private static String label(GenericKubernetesResource node, String... names) {
		Map<String, String> labels = labels(node);
		for (String name : names) {
			String value = labels.get(name);
			if (ColumnText.truthy(value)) {
				return value;
			}
		}
		return ColumnText.MISSING;
	}

	private static Map<String, String> labels(GenericKubernetesResource node) {
		ObjectMeta metadata = (node != null) ? node.getMetadata() : null;
		Map<String, String> labels = (metadata != null) ? metadata.getLabels() : null;
		return (labels != null) ? labels : Map.of();
	}

}

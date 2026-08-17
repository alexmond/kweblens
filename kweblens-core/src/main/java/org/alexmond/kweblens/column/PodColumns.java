package org.alexmond.kweblens.column;

import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

/**
 * The Pod list's kind-specific columns.
 *
 * <p>
 * {@code Ready} and {@code Restarts} are both walks of {@code status.containerStatuses},
 * which is the shape that makes this ticket worth doing: two consumers deriving them
 * separately is two chances to disagree about what a restarting sidecar means, and the
 * disagreement is invisible until someone puts the two screens side by side.
 *
 * <p>
 * The {@code Status} column the SPA shows between them is <b>not</b> here. It is the
 * server's verdict already ({@code ObjectStates.forList}, GH#360), it needs a
 * {@code StatusContext} opened once per list rather than once per object, and re-deriving
 * it per row here would undo exactly that.
 */
final class PodColumns {

	private static final String CONTAINER_STATUSES = "status.containerStatuses";

	private PodColumns() {
	}

	static List<Column> columns() {
		return List.of(new Column("ready", "Ready", PodColumns::ready),
				new Column("restarts", "Restarts", PodColumns::restarts), Column.path("node", "Node", "spec.nodeName"));
	}

	/** Containers reporting ready over containers reported at all, e.g. {@code 1/2}. */
	private static String ready(GenericKubernetesResource pod) {
		List<?> statuses = ObjectPath.list(pod, CONTAINER_STATUSES);
		long ready = statuses.stream().filter((status) -> ColumnText.truthy(ObjectPath.field(status, "ready"))).count();
		return ready + "/" + statuses.size();
	}

	/**
	 * Restarts summed across containers. A container that has never restarted omits the
	 * field rather than sending a zero, so a missing count is zero and not a gap.
	 */
	private static String restarts(GenericKubernetesResource pod) {
		double total = 0;
		for (Object status : ObjectPath.list(pod, CONTAINER_STATUSES)) {
			Object count = ObjectPath.field(status, "restartCount");
			if (count instanceof Number number) {
				total += number.doubleValue();
			}
		}
		return ColumnText.num(total);
	}

}

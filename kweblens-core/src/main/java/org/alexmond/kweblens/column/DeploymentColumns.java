package org.alexmond.kweblens.column;

import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

/**
 * The Deployment list's kind-specific columns.
 *
 * <p>
 * All three are counts the API server omits when they are zero, and all three therefore
 * read <b>{@code 0}, not {@code —}</b>. That is a deliberate exception to this package's
 * usual rule and it is the SPA's: {@code toNum} turns an absent number into zero before
 * it is printed. A Deployment with no available replicas has a meaningful zero, and
 * rendering it as "we do not know" would hide the one row an operator is looking for.
 */
final class DeploymentColumns {

	private DeploymentColumns() {
	}

	static List<Column> columns() {
		return List.of(new Column("ready", "Ready", DeploymentColumns::ready),
				new Column("uptodate", "Up-to-date", (object) -> count(object, "status.updatedReplicas")),
				new Column("available", "Available", (object) -> count(object, "status.availableReplicas")));
	}

	private static String ready(GenericKubernetesResource deployment) {
		return ColumnText.ratio(ObjectPath.read(deployment, "status.readyReplicas"),
				ObjectPath.read(deployment, "spec.replicas"));
	}

	private static String count(GenericKubernetesResource deployment, String path) {
		return ColumnText.num(ObjectPath.read(deployment, path));
	}

}

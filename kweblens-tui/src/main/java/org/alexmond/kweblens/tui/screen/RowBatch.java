package org.alexmond.kweblens.tui.screen;

import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

/**
 * Turns a <b>batch</b> of objects into rows — verdicts included.
 *
 * <p>
 * <b>A batch, never one object.</b> {@code ObjectStates.forList} opens the
 * {@code StatusContext} a verdict may need (a Service's Endpoints, a claim's metrics, a
 * ConfigMap's usage scan) <em>once per call</em>. A row-at-a-time seam would open one per
 * row, which is the cost this whole design exists to bound, and it would be invisible
 * until someone opened a kind with 2 000 of them.
 *
 * <p>
 * It is an interface so the coalescer can be tested with no cluster and no core services
 * behind it, and so the one implementation that does call the cluster
 * ({@code CoreRowBatch}) is the only place that has to be right about scope.
 */
@FunctionalInterface
public interface RowBatch {

	/**
	 * Project every object, in order.
	 * @param objects the batch; may be empty, in which case nothing should be opened
	 * @return one row per object, same order
	 */
	List<ResourceRow> project(List<GenericKubernetesResource> objects);

}

package org.alexmond.kweblens.tui.screen;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import org.alexmond.kweblens.column.Column;

/**
 * A {@link RowBatch} that can be pointed at another kind without anything that holds it
 * being rebuilt.
 *
 * <p>
 * {@link WatchCoalescer} takes its projection once, at construction, because a projection
 * that could change under an in-flight flush would be a way for a page of Deployments to
 * be given a Pod's verdicts. A view stack needs exactly that change though — {@code :svc}
 * is a different kind and therefore a different {@code StatusContext}. This is the seam:
 * the coalescer holds one object for its whole life, and the object holds the current
 * projection.
 *
 * <p>
 * <b>Retargeting is ordered with the rebase, not with the flush.</b>
 * {@code ScreenSession.switchTo} points this at the new kind <em>before</em> it opens the
 * new subscription, and opening a subscription rebases the coalescer's buffer — so the
 * events that could still arrive from the old watch are refused by generation before they
 * could reach the new projection. That is GH#417's mechanism doing a second job it
 * already fits.
 */
public class RetargetableRowBatch implements RowBatch {

	private final AtomicReference<RowBatch> delegate;

	public RetargetableRowBatch(RowBatch initial) {
		this.delegate = new AtomicReference<>(initial);
	}

	/** Point at another projection — one kind's worth of verdicts to another's. */
	public void retarget(RowBatch replacement) {
		this.delegate.set(replacement);
	}

	@Override
	public List<ResourceRow> project(List<GenericKubernetesResource> objects) {
		return this.delegate.get().project(objects);
	}

	/**
	 * The current kind's columns. Read on the render thread when the table is laid out,
	 * and written by {@link #retarget} on that same thread — so the headings and the rows
	 * under them always name the same kind.
	 */
	@Override
	public List<Column> columns() {
		return this.delegate.get().columns();
	}

}

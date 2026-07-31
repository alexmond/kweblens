package org.alexmond.kweblens.cluster;

/**
 * Where a registered cluster came from, which decides whether it can be edited or removed
 * at runtime.
 *
 * <p>
 * The distinction matters because runtime edits must not fight the deployment manifest: a
 * cluster declared in {@code kweblens.clusters[*]} or discovered in the ambient
 * kubeconfig is re-created on every boot, so "deleting" it from the browser would appear
 * to work and then silently undo itself on the next restart. Only clusters kweblens
 * itself persisted are editable.
 */
public enum ClusterOrigin {

	/**
	 * Declared outside kweblens — configuration or the ambient kubeconfig. Read-only at
	 * runtime.
	 */
	STATIC,

	/**
	 * Added through the API and persisted by a {@link ClusterStore}. Editable and
	 * removable.
	 */
	RUNTIME

}

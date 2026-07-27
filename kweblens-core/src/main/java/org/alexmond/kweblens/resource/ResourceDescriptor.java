package org.alexmond.kweblens.resource;

/**
 * Coordinates that identify one Kubernetes kind to the generic access path. One
 * descriptor drives listing for that kind — built-in or custom (CRD) — so the whole
 * catalog is data, not code.
 *
 * @param id stable, URL-safe id used in routes and the API (e.g. {@code deployments},
 * {@code traefik.io.ingressroutes})
 * @param label human-readable kind label (e.g. {@code Deployments})
 * @param kind the Kubernetes kind (e.g. {@code Deployment})
 * @param group the API group ({@code ""} for the core group)
 * @param version the API version (e.g. {@code v1})
 * @param plural the resource plural used in the API path (e.g. {@code deployments})
 * @param namespaced whether the kind is namespaced (false = cluster-scoped)
 * @param expandable whether the dashboard can expand a row to show owned child objects
 * (workload kinds expand to their pods); a nav/UI hint, ignored by the access layer
 */
public record ResourceDescriptor(String id, String label, String kind, String group, String version, String plural,
		boolean namespaced, boolean expandable) {

	/** A namespaced kind in the core API group. */
	public static ResourceDescriptor coreNamespaced(String id, String label, String kind, String plural) {
		return new ResourceDescriptor(id, label, kind, "", "v1", plural, true, false);
	}

	/** A cluster-scoped kind in the core API group. */
	public static ResourceDescriptor coreCluster(String id, String label, String kind, String plural) {
		return new ResourceDescriptor(id, label, kind, "", "v1", plural, false, false);
	}

	/** A namespaced kind in a named API group. */
	public static ResourceDescriptor namespaced(String id, String label, String kind, String group, String version,
			String plural) {
		return new ResourceDescriptor(id, label, kind, group, version, plural, true, false);
	}

	/** A cluster-scoped kind in a named API group. */
	public static ResourceDescriptor cluster(String id, String label, String kind, String group, String version,
			String plural) {
		return new ResourceDescriptor(id, label, kind, group, version, plural, false, false);
	}

	/**
	 * A copy marked expandable — the dashboard offers a per-row disclosure of this kind's
	 * owned pods (used for the workload kinds).
	 */
	public ResourceDescriptor asExpandable() {
		return new ResourceDescriptor(id, label, kind, group, version, plural, namespaced, true);
	}

}

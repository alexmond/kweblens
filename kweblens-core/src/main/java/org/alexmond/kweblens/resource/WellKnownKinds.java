package org.alexmond.kweblens.resource;

/**
 * The built-in core kinds that are referenced from more than one place, defined once so
 * their id / label / kind / plural can't drift apart. The nav catalog and the access
 * services (list pods/namespaces, cordon nodes, read events, AI diagnose) all share these
 * instead of re-spelling the same four-argument factory call.
 */
public final class WellKnownKinds {

	private WellKnownKinds() {
	}

	/** Pods — {@code v1} core, namespaced. */
	public static final ResourceDescriptor PODS = ResourceDescriptor.coreNamespaced("pods", "Pods", "Pod", "pods");

	/** Nodes — {@code v1} core, cluster-scoped. */
	public static final ResourceDescriptor NODES = ResourceDescriptor.coreCluster("nodes", "Nodes", "Node", "nodes");

	/** Namespaces — {@code v1} core, cluster-scoped. */
	public static final ResourceDescriptor NAMESPACES = ResourceDescriptor.coreCluster("namespaces", "Namespaces",
			"Namespace", "namespaces");

	/** Events — {@code v1} core, namespaced. */
	public static final ResourceDescriptor EVENTS = ResourceDescriptor.coreNamespaced("events", "Events", "Event",
			"events");

	/** Services — {@code v1} core, namespaced. */
	public static final ResourceDescriptor SERVICES = ResourceDescriptor.coreNamespaced("services", "Services",
			"Service", "services");

	/** PersistentVolumeClaims — {@code v1} core, namespaced. */
	public static final ResourceDescriptor PERSISTENT_VOLUME_CLAIMS = ResourceDescriptor.coreNamespaced(
			"persistentvolumeclaims", "Persistent Volume Claims", "PersistentVolumeClaim", "persistentvolumeclaims");

	/** ConfigMaps — {@code v1} core, namespaced. */
	public static final ResourceDescriptor CONFIG_MAPS = ResourceDescriptor.coreNamespaced("configmaps", "Config Maps",
			"ConfigMap", "configmaps");

	/** Secrets — {@code v1} core, namespaced. */
	public static final ResourceDescriptor SECRETS = ResourceDescriptor.coreNamespaced("secrets", "Secrets", "Secret",
			"secrets");

}

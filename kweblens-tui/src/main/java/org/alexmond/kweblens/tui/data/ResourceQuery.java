package org.alexmond.kweblens.tui.data;

import org.alexmond.kweblens.resource.ResourceDescriptor;

/**
 * What a list, watch or get is about: one kind, in one cluster, at one scope.
 *
 * <p>
 * The three travel together because they must agree — the namespace that produced a list
 * is also the namespace a verdict's {@code StatusContext} has to be opened at, and
 * splitting them into separate arguments is how those two drift apart.
 *
 * @param clusterId the cluster's id (a kubeconfig context name; there is no "default" to
 * assume)
 * @param descriptor the kind
 * @param namespace the scope, or null/blank for every namespace. Ignored for
 * cluster-scoped kinds, exactly as {@code ResourceService} ignores it.
 */
public record ResourceQuery(String clusterId, ResourceDescriptor descriptor, String namespace) {

	/** The kind's Kubernetes kind name, e.g. {@code Pod}. */
	public String kind() {
		return descriptor.kind();
	}

	/** This query re-scoped to another namespace, leaving cluster and kind alone. */
	public ResourceQuery inNamespace(String other) {
		return new ResourceQuery(clusterId, descriptor, other);
	}

}

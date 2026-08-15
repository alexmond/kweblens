package org.alexmond.kweblens.tui.data;

/**
 * One container, addressed the way logs and exec need it.
 *
 * @param clusterId the cluster's id
 * @param namespace the pod's namespace
 * @param pod the pod name
 * @param container the container name; blank or null selects the pod's default container,
 * which is what {@code kubectl} does and what the web layer already relies on
 */
public record PodTarget(String clusterId, String namespace, String pod, String container) {
}

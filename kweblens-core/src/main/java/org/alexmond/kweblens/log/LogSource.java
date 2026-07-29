package org.alexmond.kweblens.log;

/**
 * One log stream's origin — a single container in a single pod.
 *
 * <p>
 * Unlike the single-stream {@code LogService} calls, {@code container} here is always a
 * concrete resolved name and never blank: when several streams are multiplexed into one
 * response every line must be attributable to exactly one source, so "the pod's default
 * container" is resolved to its real name before a source is built.
 */
public record LogSource(String namespace, String pod, String container) {

	/**
	 * Stable identifier used as the SSE event's source tag and as the client's key for
	 * per-source colour, filtering and show/hide. Namespace is included so sources stay
	 * distinct when a workload spans namespaces.
	 */
	public String id() {
		return namespace + "/" + pod + "/" + container;
	}

}

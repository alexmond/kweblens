package org.alexmond.kweblens.log;

/**
 * One log stream's origin — a single container in a single pod.
 *
 * <p>
 * Unlike the single-stream {@code LogService} calls, {@code container} here is always a
 * concrete resolved name and never blank: when several streams are multiplexed into one
 * response every line must be attributable to exactly one source, so "the pod's default
 * container" is resolved to its real name before a source is built.
 *
 * <p>
 * {@code uid} is the pod's UID and is deliberately <b>not</b> part of {@link #id()}. The
 * two identities are different on purpose: {@code id()} is what the user sees and colours
 * by, while the record's own equality — which includes the uid — is what decides whether
 * a log reader is already following something. A StatefulSet rollout recreates
 * {@code web-0} with the same name and a new uid, which must start a fresh reader while
 * keeping the same colour and label in the legend.
 */
public record LogSource(String namespace, String pod, String container, String uid) {

	/** A source with no known uid — for callers that address a pod purely by name. */
	public LogSource(String namespace, String pod, String container) {
		this(namespace, pod, container, null);
	}

	/**
	 * Stable identifier used as the SSE event's source tag and as the client's key for
	 * per-source colour, filtering and show/hide. Namespace is included so sources stay
	 * distinct when a workload spans namespaces.
	 */
	public String id() {
		return this.namespace + "/" + this.pod + "/" + this.container;
	}

}

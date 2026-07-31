package org.alexmond.kweblens.cluster;

/**
 * A runtime-managed cluster as it is persisted: the identity, plus the kubeconfig that
 * proves who kweblens is when it connects.
 *
 * <p>
 * <b>The kubeconfig is a credential.</b> It is never returned by the API, never logged,
 * and — because a record's generated {@code toString()} would otherwise print every
 * component — {@link #toString()} is overridden to redact it. That override is the reason
 * this is not a plain record body: an exception message or a debug log that happened to
 * interpolate a definition would otherwise dump a working cluster credential into the log
 * stream.
 *
 * @param id stable identifier used in URLs, on disk, and as the API key
 * @param name human-readable display name
 * @param context which kubeconfig context to select; null selects its current-context
 * @param kubeconfig the kubeconfig YAML itself
 */
public record ClusterDefinition(String id, String name, String context, String kubeconfig) {

	/** True when a credential is present (never says what it is). */
	public boolean hasKubeconfig() {
		return this.kubeconfig != null && !this.kubeconfig.isBlank();
	}

	/** The same definition with a different credential. */
	public ClusterDefinition withKubeconfig(String replacement) {
		return new ClusterDefinition(this.id, this.name, this.context, replacement);
	}

	@Override
	public String toString() {
		return "ClusterDefinition[id=" + this.id + ", name=" + this.name + ", context=" + this.context + ", kubeconfig="
				+ (hasKubeconfig() ? "<redacted>" : "<none>") + "]";
	}

}

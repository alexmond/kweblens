package org.alexmond.kweblens.tui.log;

/**
 * What the operator has asked to read: one container's log, live or from its previous
 * run, with or without timestamps.
 *
 * <p>
 * <b>Every key the pane binds is this record with one field changed</b> — {@code c} picks
 * another container, {@code t} flips timestamps, {@code p} flips to the terminated
 * instance — and every one of them is served by re-opening. That is why there is one
 * request type and one {@code Navigation.logs} rather than a method per key: from the
 * cluster's side "show me the other container" and "show me this one again with
 * timestamps" are the same operation, and each of them owes the previous follow a
 * release. A second entry point is a second place to forget it.
 *
 * @param namespace the pod's namespace
 * @param pod the pod name
 * @param container the container, or blank for the pod's default one
 * @param previous read the terminated instance rather than following the live one
 * @param timestamps ask the API server to stamp every line
 */
public record LogRequest(String namespace, String pod, String container, boolean previous, boolean timestamps) {

	/** The first open on a pod: default container, live, no timestamps. */
	public static LogRequest of(String namespace, String pod, boolean previous) {
		return new LogRequest(namespace, pod, "", previous, false);
	}

	/** The same reading, of another container. */
	public LogRequest inContainer(String other) {
		return new LogRequest(this.namespace, this.pod, (other != null) ? other : "", this.previous, this.timestamps);
	}

	/** The same container, the other way round on timestamps. */
	public LogRequest withTimestampsToggled() {
		return new LogRequest(this.namespace, this.pod, this.container, this.previous, !this.timestamps);
	}

	/** The same container, the other run. */
	public LogRequest withPreviousToggled() {
		return new LogRequest(this.namespace, this.pod, this.container, !this.previous, this.timestamps);
	}

}

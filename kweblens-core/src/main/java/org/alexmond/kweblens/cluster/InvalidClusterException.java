package org.alexmond.kweblens.cluster;

/**
 * The caller's cluster definition cannot be used — a malformed kubeconfig, an id that is
 * not URL/file safe, a context that is not in the file. Maps to a 400.
 *
 * <p>
 * Thrown <em>before</em> anything is registered or persisted, so a rejected definition
 * leaves the {@link ClusterRegistry} exactly as it was.
 */
public class InvalidClusterException extends RuntimeException {

	public InvalidClusterException(String message) {
		super(message);
	}

	public InvalidClusterException(String message, Throwable cause) {
		super(message, cause);
	}

}

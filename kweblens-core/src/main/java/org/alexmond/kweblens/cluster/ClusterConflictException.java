package org.alexmond.kweblens.cluster;

/**
 * The request is well-formed but conflicts with the current state — adding an id that is
 * already registered, or editing/removing a cluster that was declared in configuration
 * rather than added at runtime. Maps to a 409.
 */
public class ClusterConflictException extends RuntimeException {

	public ClusterConflictException(String message) {
		super(message);
	}

}

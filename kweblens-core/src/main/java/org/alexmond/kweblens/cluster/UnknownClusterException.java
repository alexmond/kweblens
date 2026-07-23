package org.alexmond.kweblens.cluster;

/**
 * Thrown when a cluster id is referenced that the {@link ClusterRegistry} does not know
 * about.
 */
public class UnknownClusterException extends RuntimeException {

	public UnknownClusterException(String clusterId) {
		super("No cluster registered with id '" + clusterId + "'");
	}

}

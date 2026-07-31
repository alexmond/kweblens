package org.alexmond.kweblens.cluster;

/**
 * Lightweight, serializable description of a connected cluster — what the UI and API
 * expose without leaking the underlying client.
 *
 * @param id stable identifier used in URLs and the API
 * @param name human-readable display name
 * @param masterUrl the API server URL the client is pointed at
 * @param origin whether the cluster is runtime-managed (editable) or declared elsewhere
 */
public record ClusterInfo(String id, String name, String masterUrl, ClusterOrigin origin) {

	/** A cluster declared outside kweblens — the default for every existing caller. */
	public ClusterInfo(String id, String name, String masterUrl) {
		this(id, name, masterUrl, ClusterOrigin.STATIC);
	}

}

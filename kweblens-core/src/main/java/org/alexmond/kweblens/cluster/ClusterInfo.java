package org.alexmond.kweblens.cluster;

/**
 * Lightweight, serializable description of a connected cluster — what the UI and API
 * expose without leaking the underlying client.
 *
 * @param id stable identifier used in URLs and the API
 * @param name human-readable display name
 * @param masterUrl the API server URL the client is pointed at
 */
public record ClusterInfo(String id, String name, String masterUrl) {
}

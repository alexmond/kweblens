package org.alexmond.kweblens.resource;

import java.util.Map;

/**
 * A single resource projected for its detail view — the header fields plus labels. YAML
 * and events are fetched separately by the web layer.
 *
 * @param kind the Kubernetes kind
 * @param namespace the namespace (null for cluster-scoped)
 * @param name the resource name
 * @param status a short status/phase string, or null
 * @param age compact age since creation
 * @param labels the resource's labels
 */
public record ResourceDetail(String kind, String namespace, String name, String status, String age,
		Map<String, String> labels) {
}

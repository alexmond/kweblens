package org.alexmond.kweblens.web.search;

/**
 * One object matched by global search.
 *
 * <p>
 * A row carries its own <b>kind and namespace</b>, not just a name: names collide across
 * namespaces (every chart installed twice has a {@code postgresql} Service), so a bare
 * name is not addressable. {@code resourceId} is the nav route id, which is what actually
 * addresses the object in the rest of the API — a hit can therefore be opened by its own
 * kind rather than by whatever list the reader happened to be looking at (GH#187).
 *
 * @param resourceId nav route id of the hit's kind (e.g. {@code deployments})
 * @param kind the Kubernetes kind (e.g. {@code Deployment})
 * @param namespace the namespace, or {@code null} for a cluster-scoped object
 * @param name the object name
 * @param status short status/phase string, or null
 * @param age compact kubectl-style age
 * @param score ranking score — see {@link SearchRanking}
 */
public record SearchHit(String resourceId, String kind, String namespace, String name, String status, String age,
		int score) {
}

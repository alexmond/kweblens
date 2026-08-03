package org.alexmond.kweblens.web.api;

import lombok.RequiredArgsConstructor;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.web.search.SearchResult;
import org.alexmond.kweblens.web.search.SearchService;

/**
 * Global search: find an object by name across kinds and namespaces (GH#259).
 *
 * <p>
 * A {@code GET}, so in the default open-mode it is public — the same posture as every
 * other read in this API ({@code /objects}, {@code /namespaces}, the overviews), all of
 * which already return object names across every namespace. It returns names, kinds,
 * namespaces and phases only; no object bodies, so nothing here is readable that
 * {@code /resources/{id}/objects} does not already hand out. Under
 * {@code kweblens.security.open-mode=false} it needs the admin login like everything
 * else.
 *
 * <p>
 * The engine is in {@code web/search} — this is only the HTTP shape.
 */
@RestController
@RequiredArgsConstructor
public class SearchApiController {

	private final SearchService search;

	/**
	 * Search a cluster.
	 * @param clusterId the cluster
	 * @param q the query; blank returns an empty result rather than the whole cluster
	 * @param namespace optional namespace filter (cluster-scoped kinds ignore it)
	 * @param limit rows to return, default 20, clamped to 100
	 * @return the ranked hits, the true total, and the kinds that were not searched
	 */
	@GetMapping(value = "/api/v1/clusters/{clusterId}/search", produces = MediaType.APPLICATION_JSON_VALUE)
	public SearchResult search(@PathVariable String clusterId, @RequestParam(defaultValue = "") String q,
			@RequestParam(required = false) String namespace,
			@RequestParam(defaultValue = "" + SearchService.DEFAULT_LIMIT) int limit) {
		return this.search.search(clusterId, q, namespace, limit);
	}

}

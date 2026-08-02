package org.alexmond.kweblens.web.diag;

import java.util.Map;

import lombok.RequiredArgsConstructor;

import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only diagnostics endpoints, split the way the two questions actually differ.
 *
 * <p>
 * Per-cluster capabilities answer "why is this screen empty?" and are a property of the
 * cluster (its API groups, whether a metrics backend is reachable). App info answers
 * "what am I running and how is it configured?" and is a property of the instance.
 * Conflating them into one payload would mean re-probing every cluster to render a
 * version string.
 *
 * <p>
 * Both are GETs, so in the default {@code open-mode} they are readable without signing in
 * — intentionally: "why is this empty" is a question a read-only user has too. Neither
 * returns a secret.
 */
@RestController
@RequiredArgsConstructor
public class DiagnosticsApiController {

	private final DiagnosticsService diagnostics;

	/** What this cluster supports, and what is missing, with an explanation per row. */
	@GetMapping(value = "/api/v1/clusters/{clusterId}/diagnostics", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> cluster(@PathVariable String clusterId) {
		return this.diagnostics.clusterDiagnostics(clusterId);
	}

	/**
	 * Version, build, cluster count and the effective configuration.
	 *
	 * <p>
	 * Public in open-mode, so the operationally sensitive fields — the admin username,
	 * the cluster store's host path, the pod file browser's allowed roots — are withheld
	 * unless the caller has signed in. The SPA only needs them once signed in anyway.
	 */
	@GetMapping(value = "/api/v1/about", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> about(Authentication authentication) {
		boolean authenticated = authentication != null && authentication.isAuthenticated()
				&& !(authentication instanceof AnonymousAuthenticationToken);
		return this.diagnostics.appDiagnostics(authenticated);
	}

}

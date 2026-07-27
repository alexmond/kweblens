package org.alexmond.kweblens.web.api;

import java.io.IOException;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alexmond.jhelm.core.service.RepoManager;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.web.helm.HelmChartService;
import org.alexmond.kweblens.web.security.AuditService;

/**
 * Manually refresh a chart repository: drop the charts-browser cache for it and re-pull
 * its jhelm index (used by version-check / install). Sits alongside jhelm-rest's repo
 * endpoints under {@code /api/v1/helm/repos}; a POST, so it is auth-gated and audited.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/helm/repos/{name}")
@RequiredArgsConstructor
public class HelmRepoRefreshController {

	private final HelmChartService chartService;

	private final RepoManager repoManager;

	private final AuditService audit;

	@PostMapping("/refresh")
	public Map<String, String> refresh(@PathVariable String name) {
		chartService.evict(name);
		try {
			repoManager.updateRepo(name);
		}
		catch (IOException | RuntimeException ex) {
			log.warn("Could not re-pull jhelm index for repo '{}': {}", name, ex.getMessage());
		}
		audit.record("-", "helm-repo-refresh", name);
		return Map.of("result", "refreshed " + name);
	}

}

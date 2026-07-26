package org.alexmond.kweblens.web.helm;

import lombok.extern.slf4j.Slf4j;
import org.alexmond.jhelm.core.service.RepoManager;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires jhelm-rest into kweblens. jhelm-rest exposes the cluster-agnostic Helm surface
 * (repos, chart versions/values, Artifact Hub) under {@code /api/v1/helm/*}; kweblens
 * keeps its own per-cluster release API. Two integration points:
 *
 * <ul>
 * <li><strong>Single security gate.</strong> jhelm-rest ships an
 * {@code AccessModeInterceptor} that would deny-by-default mutating calls on top of
 * kweblens's own {@code SecurityConfig} (which already gates every non-GET
 * {@code /api/v1/helm/**} behind the admin login). We override the
 * {@code jhelmAccessModeWebMvcConfigurer} bean (the autoconfig declares it
 * {@code @ConditionalOnMissingBean(name = ...)}) with a no-op so kweblens's Spring
 * Security is the only gate.</li>
 * <li><strong>Repo seeding.</strong> The configured {@code kweblens.helm.repositories[*]}
 * are added to the shared, persisted {@link RepoManager} on startup (idempotent,
 * best-effort), so the default repos appear even on a fresh volume.</li>
 * </ul>
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class JhelmRestIntegrationConfig {

	/**
	 * Replaces jhelm-rest's access-mode {@link WebMvcConfigurer} with a no-op so its
	 * interceptor is never registered — kweblens's {@code SecurityConfig} is the sole
	 * authorization gate.
	 * @return a configurer that registers no interceptors
	 */
	@Bean
	public WebMvcConfigurer jhelmAccessModeWebMvcConfigurer() {
		return new WebMvcConfigurer() {
		};
	}

	/**
	 * Seeds the configured repositories into the shared {@link RepoManager} once at
	 * startup; runtime changes thereafter go through jhelm-rest's
	 * {@code /api/v1/helm/repos} API and persist to {@code jhelm.core.config-path}.
	 * @param repoManager the shared, persisted jhelm repository manager
	 * @param properties the kweblens Helm configuration carrying the seed repositories
	 * @return the startup runner
	 */
	@Bean
	public ApplicationRunner seedHelmRepositories(RepoManager repoManager, HelmProperties properties) {
		return (args) -> {
			for (HelmProperties.Repository repo : properties.getRepositories()) {
				if (!StringUtils.hasText(repo.getName()) || !StringUtils.hasText(repo.getUrl())) {
					continue;
				}
				try {
					if (repoManager.getRepoUrl(repo.getName()) != null) {
						continue;
					}
					repoManager.addRepo(repo.getName(), repo.getUrl());
					log.info("Seeded Helm repository '{}' -> {}", repo.getName(), repo.getUrl());
				}
				catch (Exception ex) {
					log.warn("Could not seed Helm repository '{}': {}", repo.getName(), ex.getMessage());
				}
			}
		};
	}

}

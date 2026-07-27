package org.alexmond.kweblens.web.api;

import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.web.helm.HelmMutationResult;
import org.alexmond.kweblens.web.helm.HelmService;
import org.alexmond.kweblens.web.security.AuditService;

/**
 * Mutating Helm actions — install, upgrade, rollback (via jhelm). All are POSTs, so they
 * are auth-gated by SecurityConfig. Each supports a {@code dryRun} that renders the
 * manifest without touching the cluster (the SPA previews a dry-run before applying);
 * only an applied change is audited.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/helm/releases")
@RequiredArgsConstructor
public class HelmActionApiController {

	private final HelmService helm;

	private final AuditService audit;

	@PostMapping
	public HelmMutationResult install(@PathVariable String clusterId, @RequestBody InstallRequest request) {
		HelmMutationResult result = helm.install(clusterId, request.namespace(), request.releaseName(),
				request.repository(), request.chart(), request.version(), parseValues(request.valuesYaml()),
				request.dryRun(), request.createNamespace(), request.noHooks(), request.description());
		if (!request.dryRun()) {
			audit.record(clusterId, "helm-install", request.releaseName() + " (" + request.repository() + "/"
					+ request.chart() + "-" + request.version() + ") ns=" + request.namespace());
		}
		return result;
	}

	@PostMapping("/{namespace}/{name}/upgrade")
	public HelmMutationResult upgrade(@PathVariable String clusterId, @PathVariable String namespace,
			@PathVariable String name, @RequestBody UpgradeRequest request) {
		HelmMutationResult result = helm.upgrade(clusterId, namespace, name, request.repository(), request.chart(),
				request.version(), parseValues(request.valuesYaml()), request.dryRun(), request.noHooks(),
				request.force(), request.valueStrategy(), request.maxHistory(), request.description());
		if (!request.dryRun()) {
			audit.record(clusterId, "helm-upgrade", name + " -> " + request.repository() + "/" + request.chart() + "-"
					+ request.version() + " ns=" + namespace);
		}
		return result;
	}

	@PostMapping("/{namespace}/{name}/rollback")
	public HelmMutationResult rollback(@PathVariable String clusterId, @PathVariable String namespace,
			@PathVariable String name, @RequestBody RollbackRequest request) {
		HelmMutationResult result = helm.rollback(clusterId, namespace, name, request.revision(), request.dryRun());
		if (!request.dryRun()) {
			audit.record(clusterId, "helm-rollback", name + " -> rev " + request.revision() + " ns=" + namespace);
		}
		return result;
	}

	@PostMapping("/{namespace}/{name}/uninstall")
	public Map<String, String> uninstall(@PathVariable String clusterId, @PathVariable String namespace,
			@PathVariable String name) {
		helm.uninstall(clusterId, namespace, name);
		audit.record(clusterId, "helm-uninstall", name + " ns=" + namespace);
		return Map.of("result", "uninstalled " + name);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> parseValues(String valuesYaml) {
		if (valuesYaml == null || valuesYaml.isBlank()) {
			return Map.of();
		}
		Object parsed = new Yaml(new SafeConstructor(new org.yaml.snakeyaml.LoaderOptions())).load(valuesYaml);
		return (parsed instanceof Map<?, ?> map) ? (Map<String, Object>) map : Map.of();
	}

	/**
	 * Install a chart as a new release. {@code valuesYaml} and {@code createNamespace}
	 * optional.
	 */
	public record InstallRequest(String namespace, String releaseName, String repository, String chart, String version,
			String valuesYaml, boolean dryRun, boolean createNamespace, boolean noHooks, String description) {
	}

	/**
	 * Upgrade an existing release to a chart version. {@code valueStrategy} is one of
	 * jhelm's {@code UpgradeValueStrategy} names (default/reset/reuse/reset_then_reuse);
	 * blank uses the jhelm default. {@code maxHistory} 0 leaves the default.
	 */
	public record UpgradeRequest(String repository, String chart, String version, String valuesYaml, boolean dryRun,
			boolean noHooks, boolean force, String valueStrategy, Integer maxHistory, String description) {
	}

	/** Roll a release back to an earlier revision. */
	public record RollbackRequest(int revision, boolean dryRun) {
	}

}

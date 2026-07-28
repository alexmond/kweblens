package org.alexmond.kweblens.web.helm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alexmond.jhelm.core.action.HistoryAction;
import org.alexmond.jhelm.core.action.InstallAction;
import org.alexmond.jhelm.core.action.InstallOptions;
import org.alexmond.jhelm.core.action.ListAction;
import org.alexmond.jhelm.core.action.RollbackAction;
import org.alexmond.jhelm.core.action.RollbackOptions;
import org.alexmond.jhelm.core.action.StatusAction;
import org.alexmond.jhelm.core.action.UninstallAction;
import org.alexmond.jhelm.core.action.UninstallOptions;
import org.alexmond.jhelm.core.action.UpgradeAction;
import org.alexmond.jhelm.core.action.UpgradeOptions;
import org.alexmond.jhelm.core.action.UpgradeValueStrategy;
import org.alexmond.jhelm.core.model.Chart;
import org.alexmond.jhelm.core.model.ChartMetadata;
import org.alexmond.jhelm.core.model.Release;
import org.alexmond.jhelm.core.service.Engine;
import org.alexmond.jhelm.core.service.KubeService;
import org.alexmond.jhelm.kube.KubeServices;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * Helm releases via the sibling <b>jhelm</b> library (kweblens dogfoods jhelm). Reads
 * Helm's release Secrets — never the {@code helm} binary. jhelm's fabric8 backend lets us
 * build a fully-decorated per-cluster {@link KubeService} straight from the fabric8
 * {@code KubernetesClient} the {@link ClusterRegistry} already owns, via the public
 * {@link KubeServices#fabric8} factory. So Helm shares the exact same authenticated
 * client as the rest of kweblens — no second (io.kubernetes) client to build or
 * re-authenticate, which is what previously dropped the in-cluster service-account
 * credential.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HelmService {

	/** Rendered manifests can be large; lift SnakeYAML's default 3 MB code-point cap. */
	private static final int MAX_MANIFEST_CODE_POINTS = 32 * 1024 * 1024;

	/**
	 * Release label stamped on installs/upgrades kweblens performs, so the UI can tell
	 * them apart from releases created externally (helm CLI, another tool).
	 */
	static final String MANAGED_BY_LABEL = "kweblens.alexmond.org/managed-by";

	static final String MANAGED_BY_VALUE = "kweblens";

	private final ClusterRegistry clusters;

	private final HelmChartResolver chartResolver;

	private final org.alexmond.jhelm.core.service.RepoManager repoManager;

	/**
	 * Install a chart as a new release. When {@code dryRun} is true nothing is persisted
	 * and the returned result carries the rendered manifest for preview; otherwise the
	 * release is created and the result describes it.
	 */
	public HelmMutationResult install(String clusterId, String namespace, String releaseName, String repository,
			String chart, String version, Map<String, Object> values, boolean dryRun, boolean createNamespace,
			boolean noHooks, String description) {
		Chart chartModel = chartResolver.resolve(repository, chart, version);
		InstallAction action = new InstallAction(new Engine(), kubeService(clusterId));
		Release release = action.install(InstallOptions.builder()
			.chart(chartModel)
			.releaseName(releaseName)
			.namespace(namespace)
			.values((values != null) ? values : Map.of())
			.dryRun(dryRun)
			.createNamespace(createNamespace)
			.noHooks(noHooks)
			.labels(Map.of(MANAGED_BY_LABEL, MANAGED_BY_VALUE))
			.description(describe(description, dryRun, "Installed via kweblens"))
			.build());
		return toMutationResult(dryRun, release);
	}

	/**
	 * Upgrade an existing release to a (possibly new) chart version. Dry-run previews
	 * only.
	 */
	public HelmMutationResult upgrade(String clusterId, String namespace, String releaseName, String repository,
			String chart, String version, Map<String, Object> values, boolean dryRun, boolean noHooks, boolean force,
			String valueStrategy, Integer maxHistory, String description) {
		Release current = new StatusAction(kubeService(clusterId)).status(releaseName, namespace)
			.orElseThrow(() -> new HelmException("No release '" + releaseName + "' in namespace " + namespace));
		Chart chartModel = chartResolver.resolve(repository, chart, version);
		UpgradeOptions.UpgradeOptionsBuilder builder = UpgradeOptions.builder()
			.currentRelease(current)
			.newChart(chartModel)
			.values((values != null) ? values : Map.of())
			.dryRun(dryRun)
			.noHooks(noHooks)
			.force(force)
			.labels(Map.of(MANAGED_BY_LABEL, MANAGED_BY_VALUE))
			.description(describe(description, dryRun, "Upgraded via kweblens"));
		if (maxHistory != null && maxHistory > 0) {
			builder.maxHistory(maxHistory);
		}
		UpgradeValueStrategy strategy = parseValueStrategy(valueStrategy);
		if (strategy != null) {
			builder.valueStrategy(strategy);
		}
		Release release = new UpgradeAction(new Engine(), kubeService(clusterId)).upgrade(builder.build());
		return toMutationResult(dryRun, release);
	}

	/**
	 * Use the caller's description when given; otherwise a dry-run/apply-appropriate
	 * default.
	 */
	private String describe(String provided, boolean dryRun, String fallback) {
		if (StringUtils.hasText(provided)) {
			return provided;
		}
		return dryRun ? "kweblens dry-run" : fallback;
	}

	/**
	 * Parse the upgrade value-merge strategy leniently; unknown/blank falls back to
	 * jhelm's default.
	 */
	private UpgradeValueStrategy parseValueStrategy(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		try {
			return UpgradeValueStrategy.valueOf(value.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			log.warn("Unknown Helm value strategy '{}'; using jhelm default", value);
			return null;
		}
	}

	/**
	 * Uninstall a release (like {@code helm uninstall}) — removes its resources and
	 * history.
	 */
	public void uninstall(String clusterId, String namespace, String releaseName) {
		new UninstallAction(kubeService(clusterId))
			.uninstall(UninstallOptions.builder().releaseName(releaseName).namespace(namespace).build());
	}

	/** Roll a release back to an earlier revision. Dry-run previews only. */
	public HelmMutationResult rollback(String clusterId, String namespace, String releaseName, int revision,
			boolean dryRun) {
		Release release = new RollbackAction(kubeService(clusterId)).rollback(RollbackOptions.builder()
			.releaseName(releaseName)
			.namespace(namespace)
			.revision(revision)
			.dryRun(dryRun)
			.build());
		return toMutationResult(dryRun, release);
	}

	private HelmMutationResult toMutationResult(boolean dryRun, Release release) {
		String status = (release.getInfo() != null && release.getInfo().getStatus() != null)
				? release.getInfo().getStatus().name() : null;
		return new HelmMutationResult(dryRun, release.getName(), release.getNamespace(), release.getVersion(), status,
				release.getManifest());
	}

	/**
	 * List releases in a namespace, or across all namespaces when {@code namespace} is
	 * blank.
	 */
	public List<HelmReleaseSummary> listReleases(String clusterId, String namespace) {
		ListAction list = new ListAction(kubeService(clusterId));
		List<Release> releases = StringUtils.hasText(namespace) ? list.list(namespace) : list.listAll();
		// Memoise the latest-version lookup so each distinct chart is resolved once per
		// list.
		Map<String, Optional<Latest>> memo = new java.util.HashMap<>();
		return releases.stream().map(this::toSummary).map((summary) -> withUpdate(summary, memo)).toList();
	}

	/** The latest revision of a named release. */
	public Optional<HelmReleaseSummary> status(String clusterId, String namespace, String name) {
		return new StatusAction(kubeService(clusterId)).status(name, namespace)
			.map(this::toSummary)
			.map((summary) -> withUpdate(summary, new java.util.HashMap<>()));
	}

	/** The revision history of a named release. */
	public List<HelmReleaseSummary> history(String clusterId, String namespace, String name) {
		return new HistoryAction(kubeService(clusterId)).history(name, namespace)
			.stream()
			.map(this::toSummary)
			.toList();
	}

	/**
	 * The Kubernetes objects a release manages, parsed from its rendered manifest — links
	 * a release to the actual resources it created.
	 */
	public List<HelmResourceRef> resources(String clusterId, String namespace, String name) {
		Release release = new StatusAction(kubeService(clusterId)).status(name, namespace)
			.orElseThrow(() -> new HelmException("No release '" + name + "' in namespace " + namespace));
		return parseManifest(release.getManifest(), namespace);
	}

	/**
	 * The values a release was installed/upgraded with, as YAML (Helm's stored config).
	 */
	public String releaseValues(String clusterId, String namespace, String name) {
		Release release = new StatusAction(kubeService(clusterId)).status(name, namespace)
			.orElseThrow(() -> new HelmException("No release '" + name + "' in namespace " + namespace));
		Release.MapConfig config = release.getConfig();
		Map<String, Object> values = (config != null && config.jsonValue() != null) ? config.jsonValue() : Map.of();
		if (values.isEmpty()) {
			return "";
		}
		org.yaml.snakeyaml.DumperOptions options = new org.yaml.snakeyaml.DumperOptions();
		options.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
		options.setPrettyFlow(true);
		return new Yaml(options).dump(values);
	}

	@SuppressWarnings("unchecked")
	static List<HelmResourceRef> parseManifest(String manifest, String releaseNamespace) {
		List<HelmResourceRef> refs = new ArrayList<>();
		if (!StringUtils.hasText(manifest)) {
			return refs;
		}
		LoaderOptions options = new LoaderOptions();
		options.setCodePointLimit(MAX_MANIFEST_CODE_POINTS);
		for (Object doc : new Yaml(new SafeConstructor(options)).loadAll(manifest)) {
			if (!(doc instanceof Map<?, ?> map) || map.get("kind") == null) {
				continue;
			}
			Map<String, Object> meta = (map.get("metadata") instanceof Map<?, ?> m) ? (Map<String, Object>) m
					: Map.of();
			String objName = str(meta.get("name"));
			if (!StringUtils.hasText(objName)) {
				continue;
			}
			String ns = StringUtils.hasText(str(meta.get("namespace"))) ? str(meta.get("namespace")) : releaseNamespace;
			refs.add(new HelmResourceRef(str(map.get("apiVersion")), str(map.get("kind")), ns, objName));
		}
		return refs;
	}

	private static String str(Object value) {
		return (value != null) ? value.toString() : null;
	}

	/**
	 * Build a decorated jhelm {@link KubeService} for a cluster straight from that
	 * cluster's fabric8 client (the same one the registry hands every other kweblens
	 * feature). {@link KubeServices#fabric8} applies jhelm's module-wide decorator chain
	 * (retry, metrics) — so no ambient jhelm client and no io.kubernetes
	 * {@code ApiClient} are involved. Cheap to build; kept per-call so a re-registered
	 * cluster (new client) is always honoured.
	 */
	private KubeService kubeService(String clusterId) {
		return KubeServices.fabric8(clusters.require(clusterId));
	}

	private HelmReleaseSummary toSummary(Release release) {
		String status = (release.getInfo() != null && release.getInfo().getStatus() != null)
				? release.getInfo().getStatus().name() : null;
		String updated = (release.getInfo() != null && release.getInfo().getLastDeployed() != null)
				? release.getInfo().getLastDeployed().toString() : null;
		String chart = null;
		String chartVersion = null;
		String appVersion = null;
		Chart chartModel = release.getChart();
		if (chartModel != null && chartModel.getMetadata() != null) {
			ChartMetadata metadata = chartModel.getMetadata();
			chart = metadata.getName();
			chartVersion = metadata.getVersion();
			appVersion = metadata.getAppVersion();
		}
		Map<String, String> labels = release.getLabels();
		boolean managed = labels != null && MANAGED_BY_VALUE.equals(labels.get(MANAGED_BY_LABEL));
		return new HelmReleaseSummary(release.getName(), release.getNamespace(), release.getVersion(), status, chart,
				chartVersion, appVersion, updated, managed, null, null, false);
	}

	/**
	 * Enrich a summary with the newest chart version available across the configured
	 * repositories, and whether it is newer than the installed one. The {@code memo}
	 * caches per-chart lookups so a list of releases resolves each distinct chart just
	 * once.
	 */
	private HelmReleaseSummary withUpdate(HelmReleaseSummary summary, Map<String, Optional<Latest>> memo) {
		if (!StringUtils.hasText(summary.chart())) {
			return summary;
		}
		Optional<Latest> latest = memo.computeIfAbsent(summary.chart(), this::findLatest);
		if (latest.isEmpty()) {
			return summary;
		}
		Latest best = latest.get();
		boolean available = StringUtils.hasText(summary.chartVersion())
				&& compareVersions(best.version(), summary.chartVersion()) > 0;
		return new HelmReleaseSummary(summary.name(), summary.namespace(), summary.revision(), summary.status(),
				summary.chart(), summary.chartVersion(), summary.appVersion(), summary.updated(),
				summary.managedByKweblens(), best.version(), best.repository(), available);
	}

	/** The newest version of {@code chartName} across all configured repositories. */
	private Optional<Latest> findLatest(String chartName) {
		Latest best = null;
		try {
			for (org.alexmond.jhelm.core.model.RepositoryConfig.Repository repo : repoManager.loadConfig()
				.getRepositories()) {
				List<org.alexmond.jhelm.core.service.RepoManager.ChartVersion> versions;
				try {
					versions = repoManager.getChartVersions(repo.getName(), chartName);
				}
				catch (IOException | RuntimeException ex) {
					continue;
				}
				if (versions == null || versions.isEmpty()) {
					continue;
				}
				// getChartVersions returns newest-first, so element 0 is this repo's
				// latest.
				String candidate = versions.get(0).getChartVersion();
				if (StringUtils.hasText(candidate)
						&& (best == null || compareVersions(candidate, best.version()) > 0)) {
					best = new Latest(repo.getName(), candidate);
				}
			}
		}
		catch (IOException ex) {
			log.warn("Could not read repositories to find latest of '{}': {}", chartName, ex.getMessage());
		}
		return Optional.ofNullable(best);
	}

	/**
	 * Compare two chart versions, newest-wins. A lenient SemVer: leading {@code v} is
	 * ignored, dotted numeric segments compare numerically, and a build/pre-release
	 * suffix (anything after {@code -} or {@code +}) makes a version older than the same
	 * core without one. Falls back to lexical comparison for non-numeric segments.
	 */
	static int compareVersions(String a, String b) {
		if (a == null || b == null) {
			return ((a != null) ? 1 : 0) - ((b != null) ? 1 : 0);
		}
		String[] aParts = splitVersion(a);
		String[] bParts = splitVersion(b);
		int coreCmp = compareCore(aParts[0], bParts[0]);
		if (coreCmp != 0) {
			return coreCmp;
		}
		// Equal cores: no pre-release outranks a pre-release; otherwise compare suffixes.
		boolean aPre = !aParts[1].isEmpty();
		boolean bPre = !bParts[1].isEmpty();
		if (aPre != bPre) {
			return aPre ? -1 : 1;
		}
		return aParts[1].compareTo(bParts[1]);
	}

	private static String[] splitVersion(String v) {
		String s = v.trim();
		if (s.startsWith("v") || s.startsWith("V")) {
			s = s.substring(1);
		}
		int cut = s.length();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '-' || c == '+') {
				cut = i;
				break;
			}
		}
		return new String[] { s.substring(0, cut), (cut < s.length()) ? s.substring(cut + 1) : "" };
	}

	private static int compareCore(String a, String b) {
		String[] as = a.split("\\.");
		String[] bs = b.split("\\.");
		int n = Math.max(as.length, bs.length);
		for (int i = 0; i < n; i++) {
			String ap = (i < as.length) ? as[i] : "0";
			String bp = (i < bs.length) ? bs[i] : "0";
			int cmp;
			if (ap.matches("\\d+") && bp.matches("\\d+")) {
				cmp = Long.compare(Long.parseLong(ap), Long.parseLong(bp));
			}
			else {
				cmp = ap.compareTo(bp);
			}
			if (cmp != 0) {
				return cmp;
			}
		}
		return 0;
	}

	/** A chart's newest version and the repo it was found in. */
	private record Latest(String repository, String version) {
	}

}

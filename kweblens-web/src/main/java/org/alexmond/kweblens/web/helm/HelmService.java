package org.alexmond.kweblens.web.helm;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import io.kubernetes.client.util.credentials.AccessTokenAuthentication;
import io.kubernetes.client.util.credentials.ClientCertificateAuthentication;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alexmond.jhelm.core.action.HistoryAction;
import org.alexmond.jhelm.core.action.InstallAction;
import org.alexmond.jhelm.core.action.InstallOptions;
import org.alexmond.jhelm.core.action.ListAction;
import org.alexmond.jhelm.core.action.RollbackAction;
import org.alexmond.jhelm.core.action.RollbackOptions;
import org.alexmond.jhelm.core.action.StatusAction;
import org.alexmond.jhelm.core.action.UpgradeAction;
import org.alexmond.jhelm.core.action.UpgradeOptions;
import org.alexmond.jhelm.core.model.Chart;
import org.alexmond.jhelm.core.model.ChartMetadata;
import org.alexmond.jhelm.core.model.Release;
import org.alexmond.jhelm.core.service.Engine;
import org.alexmond.jhelm.core.service.KubeService;
import org.alexmond.jhelm.kube.service.internal.HelmKubeService;
import org.alexmond.jhelm.kube.service.internal.KubeClient;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * Helm releases via the sibling <b>jhelm</b> library (kweblens dogfoods jhelm). Reads
 * Helm's release Secrets — never the {@code helm} binary. jhelm speaks the official
 * {@code io.kubernetes} client, so for each kweblens cluster we build an
 * {@link ApiClient} from that cluster's fabric8 config (base URL + bearer token) and hand
 * it to a per-cluster jhelm {@link KubeService}. This keeps Helm cluster-scoped through
 * the same {@link ClusterRegistry} everything else uses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HelmService {

	/** Rendered manifests can be large; lift SnakeYAML's default 3 MB code-point cap. */
	private static final int MAX_MANIFEST_CODE_POINTS = 32 * 1024 * 1024;

	private final ClusterRegistry clusters;

	private final HelmChartResolver chartResolver;

	/**
	 * Install a chart as a new release. When {@code dryRun} is true nothing is persisted
	 * and the returned result carries the rendered manifest for preview; otherwise the
	 * release is created and the result describes it.
	 */
	public HelmMutationResult install(String clusterId, String namespace, String releaseName, String repository,
			String chart, String version, Map<String, Object> values, boolean dryRun, boolean createNamespace) {
		Chart chartModel = chartResolver.resolve(repository, chart, version);
		InstallAction action = new InstallAction(new Engine(), kubeService(clusterId));
		Release release = action.install(InstallOptions.builder()
			.chart(chartModel)
			.releaseName(releaseName)
			.namespace(namespace)
			.values((values != null) ? values : Map.of())
			.dryRun(dryRun)
			.createNamespace(createNamespace)
			.description(dryRun ? "kweblens dry-run" : "Installed via kweblens")
			.build());
		return toMutationResult(dryRun, release);
	}

	/**
	 * Upgrade an existing release to a (possibly new) chart version. Dry-run previews
	 * only.
	 */
	public HelmMutationResult upgrade(String clusterId, String namespace, String releaseName, String repository,
			String chart, String version, Map<String, Object> values, boolean dryRun) {
		Release current = new StatusAction(kubeService(clusterId)).status(releaseName, namespace)
			.orElseThrow(() -> new HelmException("No release '" + releaseName + "' in namespace " + namespace));
		Chart chartModel = chartResolver.resolve(repository, chart, version);
		Release release = new UpgradeAction(new Engine(), kubeService(clusterId)).upgrade(UpgradeOptions.builder()
			.currentRelease(current)
			.newChart(chartModel)
			.values((values != null) ? values : Map.of())
			.dryRun(dryRun)
			.description(dryRun ? "kweblens dry-run" : "Upgraded via kweblens")
			.build());
		return toMutationResult(dryRun, release);
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
		return releases.stream().map(this::toSummary).toList();
	}

	/** The latest revision of a named release. */
	public Optional<HelmReleaseSummary> status(String clusterId, String namespace, String name) {
		return new StatusAction(kubeService(clusterId)).status(name, namespace).map(this::toSummary);
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

	private KubeService kubeService(String clusterId) {
		return new HelmKubeService(new KubeClient(apiClientFor(clusterId)));
	}

	/**
	 * Prefer the official client's own kubeconfig loader — it wires CA +
	 * client-certificate + exec auth correctly, keyed by context (the cluster id, for
	 * ambient clusters). Fall back to deriving an ApiClient from the fabric8 config for
	 * clusters not backed by the ambient kubeconfig.
	 */
	private ApiClient apiClientFor(String clusterId) {
		Path kubeconfig = ambientKubeconfigPath();
		if (kubeconfig != null && Files.isReadable(kubeconfig)) {
			try (Reader reader = Files.newBufferedReader(kubeconfig)) {
				KubeConfig kubeConfig = KubeConfig.loadKubeConfig(reader);
				if (kubeConfig.setContext(clusterId)) {
					return ClientBuilder.kubeconfig(kubeConfig).build();
				}
			}
			catch (IOException | RuntimeException ex) {
				log.warn("Kubeconfig client for '{}' failed ({}); deriving from fabric8 config", clusterId,
						ex.getMessage());
			}
		}
		return derivedApiClient(clusterId);
	}

	private ApiClient derivedApiClient(String clusterId) {
		KubernetesClient fabric8 = clusters.require(clusterId);
		io.fabric8.kubernetes.client.Config config = fabric8.getConfiguration();
		ApiClient apiClient = new ApiClient();
		apiClient.setBasePath(stripTrailingSlash(config.getMasterUrl()));
		configureTls(apiClient, config);
		configureAuth(apiClient, config);
		return apiClient;
	}

	private Path ambientKubeconfigPath() {
		String env = System.getenv("KUBECONFIG");
		if (StringUtils.hasText(env)) {
			return Path.of(env.split(File.pathSeparator)[0]);
		}
		String home = System.getProperty("user.home");
		return StringUtils.hasText(home) ? Path.of(home, ".kube", "config") : null;
	}

	/**
	 * Give the official client the same credentials the cluster's kubeconfig uses: a
	 * client certificate + key (mTLS) when present, otherwise a bearer token. Without
	 * this, cert-auth clusters reject Helm's Secret reads even though fabric8 (which has
	 * the certs) works.
	 */
	private void configureAuth(ApiClient apiClient, io.fabric8.kubernetes.client.Config config) {
		byte[] clientCert = pemMaterial(config.getClientCertData(), config.getClientCertFile());
		byte[] clientKey = pemMaterial(config.getClientKeyData(), config.getClientKeyFile());
		if (clientCert != null && clientKey != null) {
			new ClientCertificateAuthentication(clientCert, clientKey).provide(apiClient);
			return;
		}
		String token = config.getOauthToken();
		if (StringUtils.hasText(token)) {
			new AccessTokenAuthentication(token).provide(apiClient);
		}
	}

	/**
	 * Mirror the cluster's fabric8 TLS trust onto the official client: verify by default,
	 * feed the cluster's CA so self-signed API servers still validate, and only skip
	 * verification when that cluster explicitly opted in
	 * ({@code insecure-skip-tls-verify} / fabric8 {@code trustCerts}). TLS verification
	 * is never disabled as a blanket default.
	 */
	private void configureTls(ApiClient apiClient, io.fabric8.kubernetes.client.Config config) {
		if (config.isTrustCerts()) {
			apiClient.setVerifyingSsl(false);
			return;
		}
		apiClient.setVerifyingSsl(true);
		byte[] caCert = caCertBytes(config);
		if (caCert != null) {
			apiClient.setSslCaCert(new ByteArrayInputStream(caCert));
		}
	}

	private byte[] caCertBytes(io.fabric8.kubernetes.client.Config config) {
		return pemMaterial(config.getCaCertData(), config.getCaCertFile());
	}

	/**
	 * Load PEM bytes from inline data (raw PEM or base64-of-PEM) or, failing that, a file
	 * path.
	 */
	private byte[] pemMaterial(String data, String file) {
		try {
			if (StringUtils.hasText(data)) {
				return pemBytes(data);
			}
			if (StringUtils.hasText(file)) {
				return Files.readAllBytes(Path.of(file));
			}
		}
		catch (IllegalArgumentException | IOException ex) {
			log.warn("Could not load PEM material for cluster auth/TLS: {}", ex.getMessage());
		}
		return null;
	}

	/**
	 * Accepts either raw PEM or the base64-of-PEM form kubeconfig uses
	 * (certificate-authority-data).
	 */
	private byte[] pemBytes(String data) {
		String trimmed = data.trim();
		if (trimmed.startsWith("-----BEGIN")) {
			return trimmed.getBytes(StandardCharsets.UTF_8);
		}
		return Base64.getDecoder().decode(trimmed);
	}

	private String stripTrailingSlash(String url) {
		return (url != null && url.endsWith("/")) ? url.substring(0, url.length() - 1) : url;
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
		return new HelmReleaseSummary(release.getName(), release.getNamespace(), release.getVersion(), status, chart,
				chartVersion, appVersion, updated);
	}

}

package org.alexmond.kweblens.web.helm;

import java.util.List;
import java.util.Optional;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.kubernetes.client.openapi.ApiClient;
import lombok.RequiredArgsConstructor;
import org.alexmond.jhelm.core.action.HistoryAction;
import org.alexmond.jhelm.core.action.ListAction;
import org.alexmond.jhelm.core.action.StatusAction;
import org.alexmond.jhelm.core.model.Chart;
import org.alexmond.jhelm.core.model.ChartMetadata;
import org.alexmond.jhelm.core.model.Release;
import org.alexmond.jhelm.core.service.KubeService;
import org.alexmond.jhelm.kube.service.internal.HelmKubeService;
import org.alexmond.jhelm.kube.service.internal.KubeClient;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * Helm releases via the sibling <b>jhelm</b> library (kweblens dogfoods jhelm). Reads
 * Helm's release Secrets — never the {@code helm} binary. jhelm speaks the official
 * {@code io.kubernetes} client, so for each kweblens cluster we build an
 * {@link ApiClient} from that cluster's fabric8 config (base URL + bearer token) and hand
 * it to a per-cluster jhelm {@link KubeService}. This keeps Helm cluster-scoped through
 * the same {@link ClusterRegistry} everything else uses.
 */
@Service
@RequiredArgsConstructor
public class HelmService {

	private final ClusterRegistry clusters;

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

	private KubeService kubeService(String clusterId) {
		KubernetesClient fabric8 = clusters.require(clusterId);
		io.fabric8.kubernetes.client.Config config = fabric8.getConfiguration();
		ApiClient apiClient = new ApiClient();
		apiClient.setBasePath(config.getMasterUrl());
		apiClient.setVerifyingSsl(false);
		String token = config.getOauthToken();
		if (StringUtils.hasText(token)) {
			apiClient.addDefaultHeader("Authorization", "Bearer " + token);
		}
		return new HelmKubeService(new KubeClient(apiClient));
	}

	private HelmReleaseSummary toSummary(Release release) {
		String status = (release.getInfo() != null && release.getInfo().getStatus() != null)
				? release.getInfo().getStatus().name() : null;
		String updated = (release.getInfo() != null && release.getInfo().getLastDeployed() != null)
				? release.getInfo().getLastDeployed().toString() : null;
		String chart = null;
		String appVersion = null;
		Chart chartModel = release.getChart();
		if (chartModel != null && chartModel.getMetadata() != null) {
			ChartMetadata metadata = chartModel.getMetadata();
			chart = ((metadata.getName() != null) ? metadata.getName() : "")
					+ ((metadata.getVersion() != null) ? "-" + metadata.getVersion() : "");
			appVersion = metadata.getAppVersion();
		}
		return new HelmReleaseSummary(release.getName(), release.getNamespace(), release.getVersion(), status, chart,
				appVersion, updated);
	}

}

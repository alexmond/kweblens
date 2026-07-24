package org.alexmond.kweblens.metric;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.ContainerMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * CPU/memory usage from metrics-server, projected into {@link UsageSummary} rows.
 * metrics-server is optional: when it is not installed the API returns an error, which is
 * caught and reported as an empty list so the dashboard degrades gracefully rather than
 * failing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricService {

	private static final BigDecimal MILLI = BigDecimal.valueOf(1000);

	private static final BigDecimal MIB = BigDecimal.valueOf(1024L * 1024L);

	private final ClusterRegistry clusters;

	/** Per-node usage, or an empty list when metrics-server is unavailable. */
	public List<UsageSummary> nodeUsage(String clusterId) {
		KubernetesClient client = clusters.require(clusterId);
		try {
			return client.top().nodes().metrics().getItems().stream().map(this::toNodeUsage).toList();
		}
		catch (KubernetesClientException ex) {
			log.warn("Node metrics unavailable for cluster '{}' (metrics-server installed?): {}", clusterId,
					ex.getMessage());
			return List.of();
		}
	}

	/**
	 * Per-pod usage (optionally namespace-scoped), or empty when metrics-server is
	 * unavailable.
	 */
	public List<UsageSummary> podUsage(String clusterId, String namespace) {
		KubernetesClient client = clusters.require(clusterId);
		try {
			List<PodMetrics> items = StringUtils.hasText(namespace) ? client.top().pods().metrics(namespace).getItems()
					: client.top().pods().metrics().getItems();
			return items.stream().map(this::toPodUsage).toList();
		}
		catch (KubernetesClientException ex) {
			log.warn("Pod metrics unavailable for cluster '{}' (metrics-server installed?): {}", clusterId,
					ex.getMessage());
			return List.of();
		}
	}

	private UsageSummary toNodeUsage(NodeMetrics node) {
		Map<String, Quantity> usage = (node.getUsage() != null) ? node.getUsage() : Map.of();
		return new UsageSummary(name(node.getMetadata()), null, millicores(bytesOf(usage.get("cpu"))),
				mebibytes(bytesOf(usage.get("memory"))));
	}

	private UsageSummary toPodUsage(PodMetrics pod) {
		BigDecimal cpu = BigDecimal.ZERO;
		BigDecimal memory = BigDecimal.ZERO;
		if (pod.getContainers() != null) {
			for (ContainerMetrics container : pod.getContainers()) {
				Map<String, Quantity> usage = (container.getUsage() != null) ? container.getUsage() : Map.of();
				cpu = cpu.add(bytesOf(usage.get("cpu")));
				memory = memory.add(bytesOf(usage.get("memory")));
			}
		}
		String namespace = (pod.getMetadata() != null) ? pod.getMetadata().getNamespace() : null;
		return new UsageSummary(name(pod.getMetadata()), namespace, millicores(cpu), mebibytes(memory));
	}

	private BigDecimal bytesOf(Quantity quantity) {
		return (quantity != null) ? Quantity.getAmountInBytes(quantity) : BigDecimal.ZERO;
	}

	private String millicores(BigDecimal cores) {
		return cores.multiply(MILLI).setScale(0, RoundingMode.HALF_UP) + "m";
	}

	private String mebibytes(BigDecimal bytes) {
		return bytes.divide(MIB, 0, RoundingMode.HALF_UP) + "Mi";
	}

	private String name(ObjectMeta metadata) {
		return (metadata != null) ? metadata.getName() : null;
	}

}

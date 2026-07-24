package org.alexmond.kweblens.log;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ContainerResource;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.kubernetes.client.dsl.PodResource;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * Pod log access: a bounded snapshot ({@link #tail}) and a live follow ({@link #watch})
 * that returns a {@link LogWatch} the web layer reads from and bridges to SSE. A blank
 * container selects the pod's default container.
 */
@Service
@RequiredArgsConstructor
public class LogService {

	private final ClusterRegistry clusters;

	/** The last {@code tailLines} lines of a pod/container's log, as a single string. */
	public String tail(String clusterId, String namespace, String pod, String container, int tailLines) {
		return loggable(clusterId, namespace, pod, container).tailingLines(tailLines).getLog();
	}

	/**
	 * Follow a pod/container's log. The returned {@link LogWatch} exposes the live output
	 * via {@link LogWatch#getOutput()}; fabric8 owns that stream (passing our own piped
	 * stream is rejected by the client), and the caller must close the watch to stop
	 * following.
	 */
	public LogWatch watch(String clusterId, String namespace, String pod, String container) {
		return loggable(clusterId, namespace, pod, container).watchLog();
	}

	private ContainerResource loggable(String clusterId, String namespace, String pod, String container) {
		KubernetesClient client = clusters.require(clusterId);
		PodResource podResource = client.pods().inNamespace(namespace).withName(pod);
		return StringUtils.hasText(container) ? podResource.inContainer(container) : podResource;
	}

}

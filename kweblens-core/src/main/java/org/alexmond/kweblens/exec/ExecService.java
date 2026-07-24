package org.alexmond.kweblens.exec;

import java.io.OutputStream;

import io.fabric8.kubernetes.client.dsl.ContainerResource;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.PodResource;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * Opens an interactive shell ({@code sh}) in a pod/container. The returned
 * {@link ExecWatch} streams the process's combined output to the caller-supplied
 * {@link OutputStream} (bridged to a WebSocket by the web layer) and accepts keystrokes
 * via {@link ExecWatch#getInput()}. A blank container selects the pod's default
 * container.
 */
@Service
@RequiredArgsConstructor
public class ExecService {

	private final ClusterRegistry clusters;

	public ExecWatch exec(String clusterId, String namespace, String pod, String container, OutputStream output,
			ExecListener listener) {
		PodResource podResource = clusters.require(clusterId).pods().inNamespace(namespace).withName(pod);
		ContainerResource target = StringUtils.hasText(container) ? podResource.inContainer(container) : podResource;
		return target.redirectingInput()
			.writingOutput(output)
			.writingError(output)
			.withTTY()
			.usingListener(listener)
			.exec("sh");
	}

}

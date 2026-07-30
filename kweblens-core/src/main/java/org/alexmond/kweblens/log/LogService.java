package org.alexmond.kweblens.log;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.ContainerResource;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.kubernetes.client.dsl.PodResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * Pod log access: a bounded snapshot ({@link #tail}) and a live follow ({@link #watch})
 * that returns a {@link LogWatch} the web layer reads from and bridges to SSE. A blank
 * container selects the pod's default container.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogService {

	private final ClusterRegistry clusters;

	/** The last {@code tailLines} lines of a pod/container's log, as a single string. */
	public String tail(String clusterId, String namespace, String pod, String container, int tailLines) {
		return loggable(clusterId, namespace, pod, container).tailingLines(tailLines).getLog();
	}

	/**
	 * Follow a pod/container's log from <em>now</em> — new lines only, no history replay.
	 * {@code tailingLines(0)} is {@code kubectl logs --tail=0 --follow}: without it,
	 * fabric8 streams the pod's <em>entire</em> log from the beginning on connect, which
	 * floods the browser (a big log can burst tens of thousands of lines instantly) and
	 * also duplicates the {@link #tail} snapshot the caller already fetched. The returned
	 * {@link LogWatch} exposes the live output via {@link LogWatch#getOutput()}; fabric8
	 * owns that stream (passing our own piped stream is rejected by the client), and the
	 * caller must close the watch to stop following.
	 */
	public LogWatch watch(String clusterId, String namespace, String pod, String container) {
		return loggable(clusterId, namespace, pod, container).tailingLines(0).watchLog();
	}

	/**
	 * Follow a log from now with Kubernetes-supplied timestamps on every line
	 * ({@code kubectl logs --timestamps --tail=0 --follow}).
	 *
	 * <p>
	 * Multi-source following needs this: independent streams arrive interleaved by
	 * network timing, so without a per-line time there is nothing to order them by. The
	 * DSL requires {@code usingTimestamps()} <em>before</em> {@code tailingLines(…)} —
	 * the builder narrows at each step and the timestamp option is gone from the returned
	 * type afterwards.
	 */
	public LogWatch watchWithTimestamps(String clusterId, String namespace, String pod, String container) {
		return loggable(clusterId, namespace, pod, container).usingTimestamps().tailingLines(0).watchLog();
	}

	/**
	 * The last {@code tailLines} lines of a pod/container's log, with timestamps — the
	 * snapshot a multi-source view sorts and shows before live following starts.
	 */
	public String tailWithTimestamps(String clusterId, String namespace, String pod, String container, int tailLines) {
		return requireLog(
				loggable(clusterId, namespace, pod, container).usingTimestamps().tailingLines(tailLines).getLog(),
				namespace, pod, container);
	}

	/**
	 * Turn an API-server refusal into an exception, because fabric8 hands it back as
	 * <em>log text</em> — see {@link LogRefusal}.
	 */
	private String requireLog(String body, String namespace, String pod, String container) {
		if (LogRefusal.isRefusal(body)) {
			log.debug("Log request for {}/{} container {} refused: {}", namespace, pod, container, body);
			throw new LogUnavailableException(LogRefusal.message(body));
		}
		return body;
	}

	/**
	 * The log of the container's <em>previous</em> run ({@code kubectl logs --previous}).
	 *
	 * <p>
	 * This is the crashloop diagnostic: once a container has restarted, the current log
	 * starts from the new process and the output that explains the crash lives only in
	 * the terminated instance's log. Returns null when there is no previous run (the API
	 * server 400s rather than returning empty, which is not an error worth propagating).
	 */
	public String previous(String clusterId, String namespace, String pod, String container, int tailLines) {
		try {
			String body = loggable(clusterId, namespace, pod, container).terminated().tailingLines(tailLines).getLog();
			// Same trap as the timestamped tail: when there is no terminated instance the
			// API server refuses, and fabric8 may hand that refusal back as the log
			// rather
			// than throwing. Returning it would present a Kubernetes error object as the
			// crash output — the one thing a reader of THIS method is trying to see.
			return LogRefusal.isRefusal(body) ? null : body;
		}
		catch (KubernetesClientException ex) {
			log.debug("No previous log for {}/{} container {}: {}", namespace, pod, container, ex.getMessage());
			return null;
		}
	}

	private ContainerResource loggable(String clusterId, String namespace, String pod, String container) {
		KubernetesClient client = clusters.require(clusterId);
		PodResource podResource = client.pods().inNamespace(namespace).withName(pod);
		return StringUtils.hasText(container) ? podResource.inContainer(container) : podResource;
	}

}

package org.alexmond.kweblens.web.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.alexmond.kweblens.log.LogService;
import org.alexmond.kweblens.log.LogSource;
import org.alexmond.kweblens.log.LogSourceResolver;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.web.nav.ClusterNavService;

/**
 * Multi-source log API: follow every container of a pod, or every pod behind a workload,
 * in one stream ({@code kubectl logs -f deployment/x --all-containers} / {@code stern}).
 *
 * <p>
 * One multiplexed SSE response is used rather than letting the browser open N
 * {@code EventSource}s, for three reasons: browsers cap concurrent connections per origin
 * (~6 for HTTP/1.1, which a 20-replica Deployment would exhaust), ordering can only be
 * reconciled where all the lines meet, and a single connection is one thing to cancel.
 *
 * <p>
 * Also serves {@code --previous} logs, the crashloop diagnostic: after a restart the
 * current log begins at the new process, so the output explaining the crash exists only
 * in the terminated instance's log.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MultiLogApiController {

	/**
	 * Hard cap on sources per request. A 20-replica × 3-container Deployment is 60
	 * concurrent watches and 60 blocked threads, which would quietly become the app's
	 * dominant cost. Over the cap the request still succeeds with the first
	 * {@code MAX_SOURCES} sources and reports {@code truncated}, because silently showing
	 * a subset would read as "these are all the pods".
	 */
	private static final int MAX_SOURCES = 24;

	/**
	 * Shared across sessions: a log follow is a blocking read, so it needs a thread while
	 * active, but the threads are idle-blocked rather than busy.
	 * Cached-with-daemon-threads keeps them reusable across sessions and never blocks JVM
	 * shutdown. Total live threads are bounded in practice by {@link #MAX_SOURCES} per
	 * open view.
	 */
	private final ExecutorService executor = Executors.newCachedThreadPool((runnable) -> {
		Thread thread = new Thread(runnable, "multi-log");
		thread.setDaemon(true);
		return thread;
	});

	private final LogService logs;

	private final LogSourceResolver resolver;

	private final ClusterNavService clusterNav;

	/**
	 * Follow many logs as one SSE stream. Supply either {@code pod} (all its containers)
	 * or {@code resourceId} + {@code name} (all pods behind that workload).
	 *
	 * <p>
	 * Events: {@code sources} (the resolved set, plus whether it was truncated) once up
	 * front so the client can assign stable per-source colours; {@code line} per log line
	 * ({@code source}, {@code timestamp}, {@code text}); {@code source-error} when one
	 * source cannot be followed but the rest can.
	 */
	@GetMapping(value = "/api/v1/clusters/{clusterId}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream(@PathVariable String clusterId, @RequestParam String namespace,
			@RequestParam(required = false) String pod, @RequestParam(required = false) String resourceId,
			@RequestParam(required = false) String name, @RequestParam(defaultValue = "false") boolean initContainers,
			@RequestParam(defaultValue = "200") int tailLines) {
		SseEmitter emitter = new SseEmitter(0L);
		List<LogSource> resolved;
		try {
			resolved = resolve(clusterId, namespace, pod, resourceId, name, initContainers);
		}
		catch (RuntimeException ex) {
			emitter.completeWithError(ex);
			return emitter;
		}
		if (resolved.isEmpty()) {
			emitter.completeWithError(new IllegalArgumentException("No log sources matched the request"));
			return emitter;
		}
		int totalFound = resolved.size();
		boolean truncated = totalFound > MAX_SOURCES;
		List<LogSource> sources = truncated ? resolved.subList(0, MAX_SOURCES) : resolved;
		if (truncated) {
			log.info("Multi-log request matched {} sources; following the first {}", totalFound, MAX_SOURCES);
		}
		MultiLogStream stream = new MultiLogStream(this.logs, emitter, clusterId, this.executor);
		emitter.onCompletion(stream::close);
		emitter.onTimeout(() -> {
			stream.close();
			emitter.complete();
		});
		emitter.onError((ex) -> stream.close());
		stream.start(sources, tailLines, truncated, totalFound);
		return emitter;
	}

	/**
	 * The previous run's log for one container ({@code kubectl logs --previous}), or 204
	 * if the container has not restarted.
	 */
	@GetMapping(value = "/api/v1/clusters/{clusterId}/pods/{namespace}/{pod}/log/previous",
			produces = MediaType.TEXT_PLAIN_VALUE)
	public String previous(@PathVariable String clusterId, @PathVariable String namespace, @PathVariable String pod,
			@RequestParam(required = false) String container, @RequestParam(defaultValue = "500") int tailLines) {
		return this.logs.previous(clusterId, namespace, pod, container, tailLines);
	}

	/**
	 * The log sources a pod or workload expands to, without following them — lets the UI
	 * show what it is about to stream (and how many) before opening the connection.
	 */
	@GetMapping(value = "/api/v1/clusters/{clusterId}/logs/sources", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> sources(@PathVariable String clusterId, @RequestParam String namespace,
			@RequestParam(required = false) String pod, @RequestParam(required = false) String resourceId,
			@RequestParam(required = false) String name, @RequestParam(defaultValue = "false") boolean initContainers) {
		List<LogSource> resolved = resolve(clusterId, namespace, pod, resourceId, name, initContainers);
		return Map.of("sources", resolved.stream().map(LogSource::id).toList(), "totalFound", resolved.size(), "max",
				MAX_SOURCES);
	}

	private List<LogSource> resolve(String clusterId, String namespace, String pod, String resourceId, String name,
			boolean initContainers) {
		if (pod != null && !pod.isBlank()) {
			return this.resolver.forPod(clusterId, namespace, pod, initContainers);
		}
		if (resourceId == null || resourceId.isBlank() || name == null || name.isBlank()) {
			throw new IllegalArgumentException("Provide either 'pod', or 'resourceId' and 'name'");
		}
		ResourceDescriptor descriptor = this.clusterNav.find(clusterId, resourceId)
			.orElseThrow(() -> new UnknownResourceException(resourceId));
		return this.resolver.forWorkload(clusterId, descriptor, namespace, name, initContainers);
	}

}

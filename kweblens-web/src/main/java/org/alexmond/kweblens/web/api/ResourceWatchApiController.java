package org.alexmond.kweblens.web.api;

import java.io.IOException;

import io.fabric8.kubernetes.client.Watch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.web.nav.ClusterNavService;

/**
 * Live resource-table updates over Server-Sent Events. Bridges a fabric8 watch to an
 * {@link SseEmitter}: each change is one SSE event named {@code ADDED} / {@code MODIFIED}
 * / {@code DELETED} carrying the affected row as JSON. Read-only.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ResourceWatchApiController {

	private final ResourceService resources;

	private final ClusterNavService clusterNav;

	@GetMapping(value = "/api/v1/clusters/{clusterId}/resources/{resourceId}/watch",
			produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter watch(@PathVariable String clusterId, @PathVariable String resourceId,
			@RequestParam(required = false) String namespace) {
		ResourceDescriptor descriptor = clusterNav.find(clusterId, resourceId)
			.orElseThrow(() -> new UnknownResourceException(resourceId));
		SseEmitter emitter = new SseEmitter(0L);
		Watch watch;
		try {
			watch = resources.watch(clusterId, descriptor, namespace, (type, row) -> send(emitter, type, row));
		}
		catch (RuntimeException ex) {
			emitter.completeWithError(ex);
			return emitter;
		}
		emitter.onCompletion(watch::close);
		emitter.onTimeout(() -> {
			watch.close();
			emitter.complete();
		});
		// After the completion hooks, never before — see SseKeepAlive. This is the same
		// watch-behind-a-quiet-stream shape as the objects watch, and it leaked the same
		// way: measured, three departed subscribers still held three API-server watches
		// five minutes on.
		SseKeepAlive.attach(emitter);
		return emitter;
	}

	private void send(SseEmitter emitter, String type, Object row) {
		try {
			emitter.send(SseEmitter.event().name(type).data(row));
		}
		catch (IOException | IllegalStateException ex) {
			log.debug("Watch SSE send failed ({}); closing", ex.getMessage());
			SseKeepAlive.completeQuietly(emitter, ex);
		}
	}

}

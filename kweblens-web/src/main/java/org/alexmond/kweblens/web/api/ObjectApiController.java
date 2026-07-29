package org.alexmond.kweblens.web.api;

import java.io.IOException;
import java.util.List;

import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.alexmond.kweblens.resource.CrdService;
import org.alexmond.kweblens.resource.PrinterColumn;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.web.nav.ClusterNavService;
import org.alexmond.kweblens.web.ui.UnknownResourceException;

/**
 * Raw Kubernetes objects for a kind, so the UI can render kind-specific columns (and,
 * later, CRD printer columns) rather than the generic summary projection. Objects are
 * serialised with fabric8's serializer so the JSON matches the cluster's own
 * representation (apiVersion/kind/metadata/spec/status). Read-only.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ObjectApiController {

	private final ResourceService resources;

	private final ClusterNavService clusterNav;

	private final CrdService crdService;

	/**
	 * The CRD-declared printer columns for a kind (empty for built-in kinds). Lets the UI
	 * render a custom resource's own columns instead of the generic projection.
	 */
	@GetMapping(value = "/api/v1/clusters/{clusterId}/resources/{resourceId}/columns",
			produces = MediaType.APPLICATION_JSON_VALUE)
	public List<PrinterColumn> columns(@PathVariable String clusterId, @PathVariable String resourceId) {
		return crdService.printerColumns(clusterId, resourceId);
	}

	/**
	 * The pods scheduled on a node, as a JSON array — backs the Pods tab of a node's
	 * detail. Field-selected server-side, so only that node's pods are fetched.
	 */
	@GetMapping(value = "/api/v1/clusters/{clusterId}/nodes/{node}/pods", produces = MediaType.APPLICATION_JSON_VALUE)
	public String podsOnNode(@PathVariable String clusterId, @PathVariable String node) {
		return Serialization.asJson(resources.listPodsOnNode(clusterId, node));
	}

	/**
	 * The full objects of a kind as a JSON array (namespaced kinds honour the filter).
	 */
	@GetMapping(value = "/api/v1/clusters/{clusterId}/resources/{resourceId}/objects",
			produces = MediaType.APPLICATION_JSON_VALUE)
	public String objects(@PathVariable String clusterId, @PathVariable String resourceId,
			@RequestParam(required = false) String namespace) {
		ResourceDescriptor descriptor = descriptor(clusterId, resourceId);
		return Serialization.asJson(resources.listRaw(clusterId, descriptor, namespace));
	}

	/**
	 * Live object stream: each SSE event is named ADDED/MODIFIED/DELETED with the object
	 * JSON.
	 */
	@GetMapping(value = "/api/v1/clusters/{clusterId}/resources/{resourceId}/objects/watch",
			produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter watch(@PathVariable String clusterId, @PathVariable String resourceId,
			@RequestParam(required = false) String namespace) {
		ResourceDescriptor descriptor = descriptor(clusterId, resourceId);
		SseEmitter emitter = new SseEmitter(0L);
		Watch watch;
		try {
			watch = resources.watchRaw(clusterId, descriptor, namespace, (type, obj) -> send(emitter, type, obj));
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
		return emitter;
	}

	private void send(SseEmitter emitter, String type, Object obj) {
		try {
			emitter.send(SseEmitter.event().name(type).data(Serialization.asJson(obj), MediaType.APPLICATION_JSON));
		}
		catch (IOException | IllegalStateException ex) {
			log.debug("Object watch SSE send failed ({}); closing", ex.getMessage());
			emitter.completeWithError(ex);
		}
	}

	private ResourceDescriptor descriptor(String clusterId, String resourceId) {
		return clusterNav.find(clusterId, resourceId).orElseThrow(() -> new UnknownResourceException(resourceId));
	}

}

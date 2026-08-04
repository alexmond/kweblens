package org.alexmond.kweblens.web.api;

import java.io.IOException;
import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
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
		return Serialization.asJson(ListProjection.forList(resources.listPodsOnNode(clusterId, node)));
	}

	/**
	 * The objects of a kind as a JSON array (namespaced kinds honour the filter),
	 * projected for a list by {@link ListProjection} — see there for what is dropped and
	 * why the single-object endpoint below is not.
	 */
	@GetMapping(value = "/api/v1/clusters/{clusterId}/resources/{resourceId}/objects",
			produces = MediaType.APPLICATION_JSON_VALUE)
	public String objects(@PathVariable String clusterId, @PathVariable String resourceId,
			@RequestParam(required = false) String namespace) {
		ResourceDescriptor descriptor = descriptor(clusterId, resourceId);
		return Serialization.asJson(ListProjection.forList(resources.listRaw(clusterId, descriptor, namespace)));
	}

	/**
	 * One raw object, addressed by kind + namespace + name.
	 *
	 * <p>
	 * Added for global search (GH#259): a hit names an object that is <b>not</b> in the
	 * list currently on screen, so the drawer cannot be opened from a row the client
	 * already holds. Fetching the single object is the cheap way to do that — the
	 * alternative, listing the whole kind and picking one out of it, costs a full list to
	 * open one drawer, and returning whole objects from the search endpoint would mean
	 * shipping hundreds of them to render twenty rows.
	 *
	 * <p>
	 * {@code namespace} is optional so the same call addresses cluster-scoped kinds,
	 * which ignore it.
	 *
	 * <p>
	 * Deliberately <b>not</b> passed through {@link ListProjection}: this is the endpoint
	 * the detail drawer calls precisely to get back what the list dropped — a ConfigMap's
	 * or Secret's values. One object at a time, when the operator opened it.
	 */
	@GetMapping(value = "/api/v1/clusters/{clusterId}/resources/{resourceId}/object",
			produces = MediaType.APPLICATION_JSON_VALUE)
	public String object(@PathVariable String clusterId, @PathVariable String resourceId,
			@RequestParam(required = false) String namespace, @RequestParam String name) {
		ResourceDescriptor descriptor = descriptor(clusterId, resourceId);
		GenericKubernetesResource object = resources.getRaw(clusterId, descriptor, namespace, name);
		if (object == null) {
			throw new IllegalArgumentException("No such " + descriptor.kind() + ": " + name);
		}
		return Serialization.asJson(object);
	}

	/**
	 * Live object stream: each SSE event is named ADDED/MODIFIED/DELETED with the object
	 * JSON, projected by {@link ListProjection} exactly as the initial list is. The two
	 * are separate code paths feeding the same table, so a strip applied to only one of
	 * them would be undone by the first watch event to arrive.
	 */
	@GetMapping(value = "/api/v1/clusters/{clusterId}/resources/{resourceId}/objects/watch",
			produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter watch(@PathVariable String clusterId, @PathVariable String resourceId,
			@RequestParam(required = false) String namespace) {
		ResourceDescriptor descriptor = descriptor(clusterId, resourceId);
		SseEmitter emitter = new SseEmitter(0L);
		Watch watch;
		try {
			watch = resources.watchRaw(clusterId, descriptor, namespace,
					(type, obj) -> send(emitter, type, ListProjection.forList(obj)));
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
		// After the completion hooks, never before: the keepalive is what discovers a
		// departed subscriber, and closing the watch is that discovery's whole point. See
		// SseKeepAlive for the measurement — without it a watch on a quiet kind outlived
		// its subscriber by minutes, so one operator walking the nav accumulated one
		// API-server watch per list view visited.
		SseKeepAlive.attach(emitter);
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

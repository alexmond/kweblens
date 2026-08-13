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

import org.alexmond.kweblens.config.KweblensProperties;
import org.alexmond.kweblens.health.StatusContext;
import org.alexmond.kweblens.health.StatusContexts;
import org.alexmond.kweblens.resource.CrdService;
import org.alexmond.kweblens.resource.ListChunkExpiredException;
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

	private final KweblensProperties properties;

	private final StatusContexts statusContexts;

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
	 * detail. Field-selected server-side, so only that node's pods are fetched, and
	 * bounded by what one node can run rather than by the cluster — hence no chunking.
	 */
	@GetMapping(value = "/api/v1/clusters/{clusterId}/nodes/{node}/pods", produces = MediaType.APPLICATION_JSON_VALUE)
	public byte[] podsOnNode(@PathVariable String clusterId, @PathVariable String node) {
		// A Pod's verdict is a pure function of the Pod, so this list needs no context to
		// carry the same state the Workloads card counted.
		return ListJson.of(resources.listPodsOnNode(clusterId, node), StatusContext.none());
	}

	/**
	 * The objects of a kind as a JSON array (namespaced kinds honour the filter),
	 * projected for a list by {@link ListProjection} — see there for what is dropped and
	 * why the single-object endpoint below is not.
	 *
	 * <p>
	 * The collection is fetched in server-side chunks and serialised a chunk at a time
	 * ({@link ListJson}), because listing 3 000 Secrets as one collection cost 1.33 GB of
	 * transient heap against a 1 GiB container limit (#292/#293). The <b>response is
	 * unchanged</b>: every object of the kind, same bytes. GH#263's refusal of a
	 * client-visible {@code limit} stands — a substring filter over a truncated page
	 * would report "no matches" for an object that exists.
	 *
	 * <p>
	 * The {@link StatusContext} is opened <b>once, here</b>, and handed to every row:
	 * four kinds have a verdict that needs a second collection (GH#340), and fetching it
	 * per row is the one implementation of that which is not allowed. It is also opened
	 * before the chunked walk rather than inside it, so a retry after an expired chunk
	 * re-uses the same one instead of paying for it twice.
	 */
	@GetMapping(value = "/api/v1/clusters/{clusterId}/resources/{resourceId}/objects",
			produces = MediaType.APPLICATION_JSON_VALUE)
	public byte[] objects(@PathVariable String clusterId, @PathVariable String resourceId,
			@RequestParam(required = false) String namespace) {
		ResourceDescriptor descriptor = descriptor(clusterId, resourceId);
		int chunkSize = properties.getList().getChunkSize();
		StatusContext context = statusContexts.open(clusterId, descriptor.kind(), namespace);
		try {
			return ListJson.chunked(resources, clusterId, descriptor, namespace, chunkSize, context);
		}
		catch (ListChunkExpiredException ex) {
			// The snapshot was compacted away mid-scan, so every chunk already assembled
			// describes a revision that no longer exists. Start again on a fresh one
			// rather than return a list stitched from two — once; a second expiry is a
			// cluster problem and surfaces as one.
			log.info("Chunked list of '{}' expired mid-scan; restarting", resourceId);
			return ListJson.chunked(resources, clusterId, descriptor, namespace, chunkSize, context);
		}
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
	 *
	 * <p>
	 * That is also why the context is {@code refreshing} rather than a snapshot: a state
	 * dropped from a watch event would silently remove the row from a {@code status:}
	 * filter, and a state computed from Endpoints fetched when the page opened would
	 * still be asserting them hours later. The stream is not watching those inputs — see
	 * {@link StatusContexts} for the ceiling that puts on re-opening.
	 */
	@GetMapping(value = "/api/v1/clusters/{clusterId}/resources/{resourceId}/objects/watch",
			produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter watch(@PathVariable String clusterId, @PathVariable String resourceId,
			@RequestParam(required = false) String namespace) {
		ResourceDescriptor descriptor = descriptor(clusterId, resourceId);
		SseEmitter emitter = new SseEmitter(0L);
		StatusContext context = statusContexts.refreshing(clusterId, descriptor.kind(), namespace);
		Watch watch;
		try {
			watch = resources.watchRaw(clusterId, descriptor, namespace,
					(type, obj) -> send(emitter, type, ListProjection.forList(obj, context)));
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
			SseKeepAlive.completeQuietly(emitter, ex);
		}
	}

	private ResourceDescriptor descriptor(String clusterId, String resourceId) {
		return clusterNav.find(clusterId, resourceId).orElseThrow(() -> new UnknownResourceException(resourceId));
	}

}

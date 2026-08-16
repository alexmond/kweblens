package org.alexmond.kweblens.resource;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ListMeta;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * Read access to Kubernetes resources, projected into the kind-agnostic
 * {@link ResourceSummary} rows the UI and CLI render. Every kind — built-in or custom
 * (CRD) — flows through one generic path keyed by a {@link ResourceDescriptor}, so the
 * catalog is data rather than a method per kind. Each call resolves the target cluster's
 * client through the {@link ClusterRegistry}, so a cluster is addressed purely by its id.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceService {

	/**
	 * What an API server answers when a continue token's snapshot has been compacted
	 * away.
	 */
	private static final int HTTP_GONE = 410;

	/**
	 * The listener the four-argument {@code watchRaw} installs: it says, in one named
	 * place, that this caller has decided it does not need the signal. The alternative —
	 * an empty {@code onClose} body inside the watcher — is what GH#413 was, and it read
	 * as an oversight because it looked like one.
	 */
	private static final WatchEndListener END_IGNORED = new WatchEndListener() {
		@Override
		public void completed() {
			// The caller closed it, or learns it is gone some other way.
		}

		@Override
		public void failed(WatcherException cause) {
			log.debug("Watch ended and nobody asked to be told", cause);
		}
	};

	private final ClusterRegistry clusters;

	/**
	 * List a kind's resources. Cluster-scoped kinds ignore {@code namespace}; namespaced
	 * kinds list across all namespaces when it is null/blank.
	 */
	public List<ResourceSummary> list(String clusterId, ResourceDescriptor descriptor, String namespace) {
		return listRaw(clusterId, descriptor, namespace).stream().map((r) -> toSummary(descriptor.kind(), r)).toList();
	}

	/**
	 * List a kind's resources as raw {@link GenericKubernetesResource}s — the shared
	 * generic path that {@link #list} projects and that specialised services (events,
	 * etc.) map differently.
	 */
	public List<GenericKubernetesResource> listRaw(String clusterId, ResourceDescriptor descriptor, String namespace) {
		var op = clusters.require(clusterId).genericKubernetesResources(contextFor(descriptor));
		if (!descriptor.namespaced()) {
			return op.list().getItems();
		}
		if (namespace == null || namespace.isBlank()) {
			return op.inAnyNamespace().list().getItems();
		}
		return op.inNamespace(namespace).list().getItems();
	}

	/**
	 * List a kind's resources the same way {@link #listRaw} does — <b>every</b> object,
	 * same order, same objects — but fetched in server-side pages, handing each page to
	 * {@code onChunk} and keeping no reference to it afterwards. A caller that also drops
	 * each chunk holds one page at a time instead of the whole collection.
	 *
	 * <p>
	 * <b>Why this exists.</b> Measured on a live cluster (#292/#293), one list request
	 * costs ~241 KB of transient heap per Secret, against a chart that limits the
	 * container to 1 GiB — an OOM-kill at roughly 2 000 Secrets, which one Secret per
	 * Helm release revision reaches on an ordinary cluster. JFR allocation sampling
	 * attributed 94% of that to the response body and the deserialised model graph and
	 * only 1.4% to the output {@code String}, both of which exist in full before the
	 * caller is handed anything. Fetching in pages is what bounds them; streaming the
	 * reply is not.
	 *
	 * <p>
	 * <b>This is not paging the API.</b> The caller still receives every object of the
	 * kind — {@code limit}/{@code continue} is an implementation detail of the fetch, and
	 * deliberately so: GH#263 refuses a client-visible {@code limit} because a substring
	 * filter over a truncated page reports "no matches" for an object that exists.
	 * Kubernetes serves a chunked list from a pinned revision, so the assembled result is
	 * a consistent snapshot rather than a stitched-together one.
	 *
	 * <p>
	 * Two behaviours of real API servers are handled rather than assumed:
	 * <ul>
	 * <li><b>{@code limit} ignored</b> — some kinds do this (ComponentStatus on the
	 * reference cluster; the fabric8 CRUD mock does it for every kind). The first
	 * response then carries the whole collection and no continue token, which terminates
	 * the loop after one chunk: the behaviour degrades to exactly {@link #listRaw}.</li>
	 * <li><b>an expired continue token</b> — the pinned revision can be compacted away
	 * mid-scan, which the API server answers 410. That surfaces as
	 * {@link ListChunkExpiredException} so the caller can restart the scan; it must not
	 * be confused with the kind not existing.</li>
	 * </ul>
	 * @param chunkSize objects per request; not honoured by every API server, see above.
	 * Zero or less asks for the whole collection in one request — one chunk, no
	 * {@code limit} sent at all, which is the escape hatch if an API server mishandles
	 * continue tokens.
	 * @param onChunk called once per page, in order, with that page's objects
	 */
	public void listRawChunked(String clusterId, ResourceDescriptor descriptor, String namespace, int chunkSize,
			Consumer<List<GenericKubernetesResource>> onChunk) {
		if (chunkSize <= 0) {
			onChunk.accept(listRaw(clusterId, descriptor, namespace));
			return;
		}
		String token = null;
		do {
			ListOptions options = new ListOptionsBuilder().withLimit((long) chunkSize).withContinue(token).build();
			GenericKubernetesResourceList page = listPage(clusterId, descriptor, namespace, options, token != null);
			onChunk.accept((page.getItems() != null) ? page.getItems() : List.of());
			ListMeta meta = page.getMetadata();
			token = (meta != null) ? meta.getContinue() : null;
		}
		while (token != null && !token.isBlank());
	}

	/**
	 * How many objects of a kind exist, <em>without</em> fetching them.
	 *
	 * <p>
	 * The obvious implementation — {@code listRaw(...).size()} — pulls every object of
	 * every kind out of the API server to produce an integer. Measured on a small real
	 * cluster (1 561 objects across 118 kinds) that is about 23 MB of JSON decoded per
	 * call, 10 MB of it the CustomResourceDefinitions' own OpenAPI schemas and 6.5 MB the
	 * Secrets' data, for 118 numbers on a sidebar that is re-fetched on every namespace
	 * switch. The cost scales with the cluster's total content; the numbers do not.
	 *
	 * <p>
	 * So ask for one item and let the server say how many more there are.
	 * {@code metadata.remainingItemCount} is <b>best-effort</b> — the API server may omit
	 * it, and a count that silently becomes wrong would be worse than one that is slow —
	 * so every branch is explicit:
	 * <ul>
	 * <li><b>{@code remainingItemCount} present</b> — the answer is that plus what came
	 * back.</li>
	 * <li><b>absent, and no continue token</b> — the page IS the whole collection, so its
	 * size is exact. This also covers a server that ignored {@code limit} outright
	 * (ComponentStatus on this cluster does exactly that, and still counts
	 * correctly).</li>
	 * <li><b>absent, but truncated</b> — the only genuinely unknown case. Fall back to
	 * the full list rather than guess.</li>
	 * </ul>
	 * Verified against a live API server (k3s 1.35) for many/one/zero objects, namespaced
	 * and cluster-scoped kinds, and a CRD-backed kind; every derived count matched
	 * {@code kubectl}.
	 */
	public int count(String clusterId, ResourceDescriptor descriptor, String namespace) {
		GenericKubernetesResourceList page = listPage(clusterId, descriptor, namespace,
				new ListOptionsBuilder().withLimit(1L).build(), false);
		int returned = (page.getItems() != null) ? page.getItems().size() : 0;
		ListMeta meta = page.getMetadata();
		Long remaining = (meta != null) ? meta.getRemainingItemCount() : null;
		if (remaining != null) {
			return Math.toIntExact(returned + remaining);
		}
		String token = (meta != null) ? meta.getContinue() : null;
		if (token == null || token.isBlank()) {
			return returned;
		}
		log.debug("No remainingItemCount for '{}'; falling back to a full list", descriptor.id());
		return listRaw(clusterId, descriptor, namespace).size();
	}

	/**
	 * One page of a kind — the single item {@link #count} reasons about, or one chunk of
	 * {@link #listRawChunked}.
	 * @param continuing whether {@code options} carries a continue token, which is the
	 * only case in which a 410 means "the snapshot expired" rather than something being
	 * wrong with the request
	 */
	private GenericKubernetesResourceList listPage(String clusterId, ResourceDescriptor descriptor, String namespace,
			ListOptions options, boolean continuing) {
		var op = clusters.require(clusterId).genericKubernetesResources(contextFor(descriptor));
		try {
			if (!descriptor.namespaced()) {
				return op.list(options);
			}
			if (namespace == null || namespace.isBlank()) {
				return op.inAnyNamespace().list(options);
			}
			return op.inNamespace(namespace).list(options);
		}
		catch (KubernetesClientException ex) {
			if (continuing && ex.getCode() == HTTP_GONE) {
				throw new ListChunkExpiredException(descriptor.id(), ex);
			}
			throw ex;
		}
	}

	/**
	 * Watch a kind and deliver each change to {@code onEvent} as (action, row) —
	 * {@code ADDED}, {@code MODIFIED}, or {@code DELETED} with the affected
	 * {@link ResourceSummary}. The returned {@link Watch} must be closed to stop
	 * watching.
	 */
	public Watch watch(String clusterId, ResourceDescriptor descriptor, String namespace,
			BiConsumer<String, ResourceSummary> onEvent) {
		var op = clusters.require(clusterId).genericKubernetesResources(contextFor(descriptor));
		Watcher<GenericKubernetesResource> watcher = new Watcher<>() {
			@Override
			public void eventReceived(Action action, GenericKubernetesResource resource) {
				onEvent.accept(action.name(), toSummary(descriptor.kind(), resource));
			}

			@Override
			public void onClose(WatcherException cause) {
				// The web layer completes the SSE emitter via its own close hooks.
			}
		};
		if (!descriptor.namespaced()) {
			return op.watch(watcher);
		}
		if (namespace == null || namespace.isBlank()) {
			return op.inAnyNamespace().watch(watcher);
		}
		return op.inNamespace(namespace).watch(watcher);
	}

	/**
	 * Watch a kind and deliver each change as (action, raw object) — the full
	 * {@link GenericKubernetesResource}, so the web layer can project kind-specific
	 * columns. The returned {@link Watch} must be closed to stop watching.
	 *
	 * <p>
	 * <b>This flavour drops the end of the stream.</b> That is deliberate and it is only
	 * safe for a caller that learns about the disconnect some other way: the web layer's
	 * {@code SseKeepAlive} fails a write to a departed subscriber, completes the emitter
	 * and closes the watch through its own hook, so a callback here would be a second
	 * copy of a decision it already makes. A caller with no such hook — the TUI — must
	 * use
	 * {@link #watchRaw(String, ResourceDescriptor, String, BiConsumer, WatchEndListener)},
	 * or a dead watch leaves it drawing a stale list that looks live (GH#413).
	 */
	public Watch watchRaw(String clusterId, ResourceDescriptor descriptor, String namespace,
			BiConsumer<String, GenericKubernetesResource> onEvent) {
		return watchRaw(clusterId, descriptor, namespace, onEvent, END_IGNORED);
	}

	/**
	 * The same watch, with the end of the stream reported to {@code onEnd}.
	 *
	 * <p>
	 * fabric8 reconnects by itself, so {@code onEnd} fires only once it has given up —
	 * see {@link WatchEndListener} for which ending is which and why they are two
	 * methods. Neither callback closes anything: the returned {@link Watch} is still the
	 * caller's to release, because a watch that failed and a watch that was never opened
	 * are different states and only the caller knows which of its own handles it is
	 * holding.
	 * @param onEnd told once, when nothing further will arrive
	 */
	public Watch watchRaw(String clusterId, ResourceDescriptor descriptor, String namespace,
			BiConsumer<String, GenericKubernetesResource> onEvent, WatchEndListener onEnd) {
		var op = clusters.require(clusterId).genericKubernetesResources(contextFor(descriptor));
		Watcher<GenericKubernetesResource> watcher = new Watcher<>() {
			@Override
			public void eventReceived(Action action, GenericKubernetesResource resource) {
				onEvent.accept(action.name(), resource);
			}

			@Override
			public void onClose() {
				onEnd.completed();
			}

			@Override
			public void onClose(WatcherException cause) {
				onEnd.failed(cause);
			}
		};
		if (!descriptor.namespaced()) {
			return op.watch(watcher);
		}
		if (namespace == null || namespace.isBlank()) {
			return op.inAnyNamespace().watch(watcher);
		}
		return op.inNamespace(namespace).watch(watcher);
	}

	/**
	 * Pods scheduled on a node, across all namespaces. The field selector is applied
	 * server-side (as {@code kubectl get pods --field-selector spec.nodeName=…} does), so
	 * only the node's own pods come back rather than the whole cluster's.
	 */
	public List<GenericKubernetesResource> listPodsOnNode(String clusterId, String nodeName) {
		return clusters.require(clusterId)
			.genericKubernetesResources(contextFor(WellKnownKinds.PODS))
			.inAnyNamespace()
			.withField("spec.nodeName", nodeName)
			.list()
			.getItems();
	}

	/**
	 * The YAML of a single resource, or null if it does not exist.
	 */
	public String getYaml(String clusterId, ResourceDescriptor descriptor, String namespace, String name) {
		GenericKubernetesResource resource = getRaw(clusterId, descriptor, namespace, name);
		return (resource != null) ? Serialization.asYaml(resource) : null;
	}

	/** The detail projection of a single resource, or empty if it does not exist. */
	public Optional<ResourceDetail> detail(String clusterId, ResourceDescriptor descriptor, String namespace,
			String name) {
		GenericKubernetesResource resource = getRaw(clusterId, descriptor, namespace, name);
		if (resource == null) {
			return Optional.empty();
		}
		Map<String, String> labels = (resource.getMetadata() != null && resource.getMetadata().getLabels() != null)
				? resource.getMetadata().getLabels() : Map.of();
		return Optional.of(new ResourceDetail(descriptor.kind(), namespace(resource), name(resource), phase(resource),
				age(resource), labels));
	}

	/**
	 * A single resource of any kind as a raw {@link GenericKubernetesResource}, or null
	 * if it does not exist. Public because callers outside the projection path need
	 * fields the {@link ResourceSummary} does not carry — e.g. the log layer reads a
	 * workload's {@code spec.selector.matchLabels} to find the pods to follow.
	 */
	public GenericKubernetesResource getRaw(String clusterId, ResourceDescriptor descriptor, String namespace,
			String name) {
		var op = clusters.require(clusterId).genericKubernetesResources(contextFor(descriptor));
		return descriptor.namespaced() ? op.inNamespace(namespace).withName(name).get() : op.withName(name).get();
	}

	/**
	 * Apply a YAML manifest (server-side apply). The manifest is self-describing, so no
	 * descriptor is needed. Returns a summary of the applied resource.
	 */
	public ResourceSummary apply(String clusterId, String yaml) {
		KubernetesClient client = clusters.require(clusterId);
		HasMetadata applied = client.resource(forApply(yaml)).forceConflicts().serverSideApply();
		return new ResourceSummary(applied.getKind(), namespace(applied), name(applied), null, "-");
	}

	/**
	 * Ask the API server what {@link #apply} <em>would</em> produce, without producing
	 * it.
	 *
	 * <p>
	 * The editor's Review Changes tab could only ever diff the edited text against the
	 * text it loaded — an honest answer to "what did I type", and no answer at all to
	 * "what will the cluster do with it". Three things live in that gap and all of them
	 * are invisible until the write lands: <b>defaulting</b> (the fields the server fills
	 * in), <b>another manager's fields</b> (which {@code forceConflicts()} silently takes
	 * over), and <b>admission</b> — a validating webhook that rejects this, a quota that
	 * blocks it, or a mutating webhook that rewrites it into something other than what
	 * was typed.
	 *
	 * <p>
	 * {@code dryRun=All} runs the whole chain and returns the object that would have been
	 * persisted, then discards it. Crucially it goes through {@link #forApply} — the same
	 * normalisation the real apply uses — because a preview that differs from the apply
	 * in even one field is worse than no preview: it would be believed.
	 * @return the object the server says would result, serialised as YAML
	 */
	public String dryRunApply(String clusterId, String yaml) {
		KubernetesClient client = clusters.require(clusterId);
		HasMetadata result = client.resource(forApply(yaml)).dryRun().forceConflicts().serverSideApply();
		return Serialization.asYaml(result);
	}

	/**
	 * Parse a manifest and strip the server-managed metadata that would make it fail to
	 * apply cleanly.
	 *
	 * <p>
	 * Editing a fetched manifest carries {@code managedFields}, {@code resourceVersion}
	 * and friends back with it. Shared between {@link #apply} and {@link #dryRunApply}
	 * rather than duplicated, for the same reason {@link #restartPatch} takes its
	 * timestamp: the preview and the write must be made of the same bytes, or the preview
	 * is describing a different request from the one that will be sent.
	 */
	private static HasMetadata forApply(String yaml) {
		HasMetadata parsed = Serialization.unmarshal(yaml);
		ObjectMeta meta = parsed.getMetadata();
		if (meta != null) {
			meta.setManagedFields(null);
			meta.setResourceVersion(null);
			meta.setUid(null);
			meta.setCreationTimestamp(null);
			meta.setGeneration(null);
		}
		return parsed;
	}

	/**
	 * Apply a JSON Merge Patch (RFC 7386) to a single resource. Fields present in the
	 * patch are set; a key mapped to {@code null} is deleted. Used by the structured
	 * (form) editor for targeted edits — labels, annotations, ConfigMap/Secret data —
	 * without touching anything else on the object.
	 */
	public ResourceSummary patch(String clusterId, ResourceDescriptor descriptor, String namespace, String name,
			String jsonMergePatch) {
		PatchContext context = new PatchContext.Builder().withPatchType(PatchType.JSON_MERGE).build();
		GenericKubernetesResource patched = resource(clusterId, descriptor, namespace, name).patch(context,
				jsonMergePatch);
		return new ResourceSummary(patched.getKind(), namespace(patched), name(patched), null, "-");
	}

	/** Delete a single resource. */
	public void delete(String clusterId, ResourceDescriptor descriptor, String namespace, String name) {
		delete(clusterId, descriptor, namespace, name, false);
	}

	/**
	 * Delete a single resource; when {@code force} is true, delete immediately with a
	 * zero grace period (skips graceful termination — use for stuck resources).
	 */
	public void delete(String clusterId, ResourceDescriptor descriptor, String namespace, String name, boolean force) {
		Resource<GenericKubernetesResource> resource = resource(clusterId, descriptor, namespace, name);
		if (force) {
			resource.cascading(true).withGracePeriod(0L).delete();
		}
		else {
			resource.delete();
		}
	}

	/** Set a workload's replica count (Deployments, StatefulSets, ReplicaSets). */
	public void scale(String clusterId, ResourceDescriptor descriptor, String namespace, String name, int replicas) {
		strategicPatch(clusterId, descriptor, namespace, name, scalePatch(replicas));
	}

	/**
	 * Trigger a rolling restart by stamping the pod template with a restart annotation
	 * (the same mechanism as {@code kubectl rollout restart}). Works for
	 * Deployments/StatefulSets/DaemonSets.
	 */
	public void rolloutRestart(String clusterId, ResourceDescriptor descriptor, String namespace, String name) {
		strategicPatch(clusterId, descriptor, namespace, name, restartPatch(Instant.now()));
	}

	/**
	 * Roll a Deployment or StatefulSet back to its previous revision (equivalent to
	 * {@code kubectl rollout undo}). fabric8 resolves the prior ReplicaSet/revision and
	 * patches the template — it fails if there is no rollout history.
	 */
	public void rollback(String clusterId, ResourceDescriptor descriptor, String namespace, String name) {
		KubernetesClient client = clusters.require(clusterId);
		switch (descriptor.kind()) {
			case "Deployment" -> client.apps().deployments().inNamespace(namespace).withName(name).rolling().undo();
			case "StatefulSet" -> client.apps().statefulSets().inNamespace(namespace).withName(name).rolling().undo();
			default -> throw new IllegalArgumentException("Rollback is not supported for " + descriptor.kind());
		}
	}

	/**
	 * Cordon ({@code unschedulable=true}) or uncordon a node — mark it (un)schedulable,
	 * the safe half of a drain.
	 */
	public void setUnschedulable(String clusterId, String nodeName, boolean unschedulable) {
		strategicPatch(clusterId, WellKnownKinds.NODES, null, nodeName,
				"{\"spec\":{\"unschedulable\":" + unschedulable + "}}");
	}

	/** Suspend or resume a CronJob/Job by setting {@code spec.suspend}. */
	public void setSuspended(String clusterId, ResourceDescriptor descriptor, String namespace, String name,
			boolean suspend) {
		strategicPatch(clusterId, descriptor, namespace, name, "{\"spec\":{\"suspend\":" + suspend + "}}");
	}

	/**
	 * Trigger a CronJob now: create a Job from its {@code jobTemplate} (like
	 * {@code kubectl create job --from=cronjob/x}), owned by the CronJob.
	 */
	public void triggerCronJob(String clusterId, String namespace, String name) {
		KubernetesClient client = clusters.require(clusterId);
		io.fabric8.kubernetes.api.model.batch.v1.CronJob cronJob = client.batch()
			.v1()
			.cronjobs()
			.inNamespace(namespace)
			.withName(name)
			.get();
		if (cronJob == null || cronJob.getSpec() == null || cronJob.getSpec().getJobTemplate() == null) {
			throw new IllegalArgumentException("CronJob not found or has no jobTemplate: " + name);
		}
		var template = cronJob.getSpec().getJobTemplate();
		var owner = new io.fabric8.kubernetes.api.model.OwnerReferenceBuilder().withApiVersion("batch/v1")
			.withKind("CronJob")
			.withName(cronJob.getMetadata().getName())
			.withUid(cronJob.getMetadata().getUid())
			.withController(true)
			.withBlockOwnerDeletion(false)
			.build();
		var job = new io.fabric8.kubernetes.api.model.batch.v1.JobBuilder().withNewMetadata()
			.withName(name + "-manual-" + Instant.now().getEpochSecond())
			.withNamespace(namespace)
			.addToAnnotations("cronjob.kubernetes.io/instantiate", "manual")
			.withLabels((template.getMetadata() != null) ? template.getMetadata().getLabels() : null)
			.withOwnerReferences(owner)
			.endMetadata()
			.withSpec(template.getSpec())
			.build();
		client.batch().v1().jobs().inNamespace(namespace).resource(job).create();
	}

	/**
	 * Drain a node: cordon it, then evict its pods (skipping DaemonSet-managed and mirror
	 * pods, as {@code kubectl drain} does). Best-effort — eviction failures are logged.
	 */
	public void drainNode(String clusterId, String nodeName) {
		setUnschedulable(clusterId, nodeName, true);
		KubernetesClient client = clusters.require(clusterId);
		List<io.fabric8.kubernetes.api.model.Pod> pods = client.pods()
			.inAnyNamespace()
			.withField("spec.nodeName", nodeName)
			.list()
			.getItems();
		for (io.fabric8.kubernetes.api.model.Pod pod : pods) {
			ObjectMeta meta = pod.getMetadata();
			boolean daemon = meta.getOwnerReferences() != null
					&& meta.getOwnerReferences().stream().anyMatch((o) -> "DaemonSet".equals(o.getKind()));
			boolean mirror = meta.getAnnotations() != null
					&& meta.getAnnotations().containsKey("kubernetes.io/config.mirror");
			if (daemon || mirror) {
				continue;
			}
			try {
				client.pods().inNamespace(meta.getNamespace()).withName(meta.getName()).evict();
			}
			catch (RuntimeException ex) {
				log.warn("Eviction failed for {}/{}: {}", meta.getNamespace(), meta.getName(), ex.getMessage());
			}
		}
	}

	/**
	 * Ask the API server what a patch <em>would</em> do, without doing it.
	 *
	 * <p>
	 * {@code dryRun=All} runs the request through the whole admission chain — validation,
	 * mutating and validating webhooks, quota — and returns the object that would have
	 * been persisted, then discards it. That is the difference between describing an
	 * intention and knowing the outcome: a webhook that rejects the change, a quota that
	 * blocks it, or a mutating webhook that rewrites it all surface here rather than at
	 * apply time.
	 * @return the object the server says would result, serialised as YAML
	 */
	public String dryRunPatch(String clusterId, ResourceDescriptor descriptor, String namespace, String name,
			String patchJson) {
		PatchContext context = new PatchContext.Builder().withPatchType(PatchType.JSON_MERGE)
			.withDryRun(List.of("All"))
			.build();
		GenericKubernetesResource result = resource(clusterId, descriptor, namespace, name).patch(context, patchJson);
		return Serialization.asYaml(result);
	}

	/** The patch {@link #scale} would send — shared so a dry-run cannot drift from it. */
	public static String scalePatch(int replicas) {
		return "{\"spec\":{\"replicas\":" + replicas + "}}";
	}

	/**
	 * The patch {@link #rolloutRestart} would send.
	 *
	 * <p>
	 * Takes the timestamp rather than calling {@code Instant.now()} so a dry-run and the
	 * apply that follows it stamp the same value; otherwise the preview would differ from
	 * the real change in the one field the change is made of.
	 */
	public static String restartPatch(Instant at) {
		return "{\"spec\":{\"template\":{\"metadata\":{\"annotations\":{" + "\"kweblens.alexmond.org/restartedAt\":\""
				+ at + "\"}}}}}";
	}

	private void strategicPatch(String clusterId, ResourceDescriptor descriptor, String namespace, String name,
			String patchJson) {
		// JSON merge patch: recursively merges objects and sets leaf fields — correct for
		// the
		// simple spec edits here (replicas, unschedulable, an added annotation) and
		// widely supported.
		PatchContext context = new PatchContext.Builder().withPatchType(PatchType.JSON_MERGE).build();
		resource(clusterId, descriptor, namespace, name).patch(context, patchJson);
	}

	private Resource<GenericKubernetesResource> resource(String clusterId, ResourceDescriptor descriptor,
			String namespace, String name) {
		var op = clusters.require(clusterId).genericKubernetesResources(contextFor(descriptor));
		return descriptor.namespaced() ? op.inNamespace(namespace).withName(name) : op.withName(name);
	}

	private ResourceDefinitionContext contextFor(ResourceDescriptor descriptor) {
		return new ResourceDefinitionContext.Builder().withGroup(descriptor.group())
			.withVersion(descriptor.version())
			.withKind(descriptor.kind())
			.withPlural(descriptor.plural())
			.withNamespaced(descriptor.namespaced())
			.build();
	}

	/** List every namespace in the cluster. */
	public List<ResourceSummary> listNamespaces(String clusterId) {
		return list(clusterId, WellKnownKinds.NAMESPACES, null);
	}

	/**
	 * List pods in a namespace (or all namespaces when {@code namespace} is null/blank).
	 */
	public List<ResourceSummary> listPods(String clusterId, String namespace) {
		return list(clusterId, WellKnownKinds.PODS, namespace);
	}

	private ResourceSummary toSummary(String kind, GenericKubernetesResource resource) {
		return new ResourceSummary(kind, namespace(resource), name(resource), phase(resource), age(resource));
	}

	private String phase(GenericKubernetesResource resource) {
		Object status = resource.getAdditionalProperties().get("status");
		if (status instanceof Map<?, ?> map) {
			Object phase = map.get("phase");
			return (phase != null) ? phase.toString() : null;
		}
		return null;
	}

	private String name(HasMetadata resource) {
		return (resource.getMetadata() != null) ? resource.getMetadata().getName() : null;
	}

	private String namespace(HasMetadata resource) {
		return (resource.getMetadata() != null) ? resource.getMetadata().getNamespace() : null;
	}

	private String age(HasMetadata resource) {
		if (resource.getMetadata() == null) {
			return "-";
		}
		return ResourceSummary.age(parse(resource.getMetadata().getCreationTimestamp()), Instant.now());
	}

	private Instant parse(String timestamp) {
		if (timestamp == null || timestamp.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(timestamp);
		}
		catch (DateTimeParseException ex) {
			log.debug("Unparseable creationTimestamp '{}'", timestamp);
			return null;
		}
	}

}

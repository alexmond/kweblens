package org.alexmond.kweblens.resource;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import java.util.function.BiConsumer;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
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
	 */
	public Watch watchRaw(String clusterId, ResourceDescriptor descriptor, String namespace,
			BiConsumer<String, GenericKubernetesResource> onEvent) {
		var op = clusters.require(clusterId).genericKubernetesResources(contextFor(descriptor));
		Watcher<GenericKubernetesResource> watcher = new Watcher<>() {
			@Override
			public void eventReceived(Action action, GenericKubernetesResource resource) {
				onEvent.accept(action.name(), resource);
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

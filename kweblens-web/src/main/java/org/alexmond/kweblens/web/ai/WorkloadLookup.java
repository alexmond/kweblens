package org.alexmond.kweblens.web.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.resource.WellKnownKinds;

/**
 * Resolves the object a remediation should actually be aimed at.
 *
 * <p>
 * A diagnosis names the object that shows the symptom — a pod, a Service. Every fix above
 * "delete this pod" acts on something else: the workload that owns the pod, or the
 * workload behind the Service. This walks those two links, and it is also where the
 * <b>preconditions</b> for the workload-level actions are checked, because "can this fix
 * work?" is a question about the controller, not about the symptom.
 *
 * <p>
 * Every lookup fails closed: if the object cannot be read, no owner is returned and no
 * proposal is made. An unofferable fix is better than one that cannot work.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class WorkloadLookup {

	static final ResourceDescriptor DEPLOYMENTS = ResourceDescriptor.namespaced("deployments", "Deployments",
			"Deployment", "apps", "v1", "deployments");

	static final ResourceDescriptor STATEFUL_SETS = ResourceDescriptor.namespaced("statefulsets", "Stateful Sets",
			"StatefulSet", "apps", "v1", "statefulsets");

	static final ResourceDescriptor DAEMON_SETS = ResourceDescriptor.namespaced("daemonsets", "Daemon Sets",
			"DaemonSet", "apps", "v1", "daemonsets");

	static final ResourceDescriptor REPLICA_SETS = ResourceDescriptor.namespaced("replicasets", "Replica Sets",
			"ReplicaSet", "apps", "v1", "replicasets");

	static final ResourceDescriptor SERVICES = ResourceDescriptor.coreNamespaced("services", "Services", "Service",
			"services");

	/**
	 * Kinds whose pods a controller recreates from a template — the only ones a
	 * workload-level restart means anything for. A pod owned by a Job is deliberately
	 * absent: a Job's template is immutable and its pod count is its whole contract, so
	 * "restart the workload" is not an operation it has.
	 */
	private static final Set<String> RESTARTABLE_KINDS = Set.of("Deployment", "StatefulSet", "DaemonSet");

	/** Kinds a Service can select pods from, for the scale-up precondition. */
	private static final List<ResourceDescriptor> SELECTABLE_WORKLOADS = List.of(DEPLOYMENTS, STATEFUL_SETS);

	/**
	 * Written by the Deployment controller; {@code 1} means the current template is the
	 * only one there has ever been.
	 */
	private static final String REVISION = "deployment.kubernetes.io/revision";

	private final ResourceService resources;

	/**
	 * The workload that owns a pod, following ReplicaSet through to its Deployment.
	 *
	 * <p>
	 * The pod's own controller reference stops at the ReplicaSet, which is an
	 * implementation detail nobody operates on — remediating a ReplicaSet is undone by
	 * the Deployment on its next sync. So the second hop is not optional.
	 */
	Optional<WorkloadRef> ownerOf(String clusterId, String namespace, String podName) {
		GenericKubernetesResource pod = read(WellKnownKinds.PODS, clusterId, namespace, podName);
		OwnerReference owner = controllerOf(pod);
		if (owner == null) {
			return Optional.empty();
		}
		if ("ReplicaSet".equals(owner.getKind())) {
			OwnerReference above = controllerOf(read(REPLICA_SETS, clusterId, namespace, owner.getName()));
			return (above != null && "Deployment".equals(above.getKind()))
					? Optional.of(new WorkloadRef("Deployment", above.getName())) : Optional.empty();
		}
		return RESTARTABLE_KINDS.contains(owner.getKind())
				? Optional.of(new WorkloadRef(owner.getKind(), owner.getName())) : Optional.empty();
	}

	/**
	 * Whether there is a previous revision to roll back to.
	 *
	 * <p>
	 * Deliberately Deployment-only. A Deployment records its revision on the object, so
	 * "has this ever been changed?" is answerable from one read. A StatefulSet's history
	 * lives in ControllerRevisions, and an undo with nothing behind it fails at the API —
	 * offering a rollback we cannot first prove is possible is exactly the suggestion
	 * that wastes an operator's confirmation.
	 */
	boolean hasRolloutHistory(String clusterId, String namespace, WorkloadRef workload) {
		if (!"Deployment".equals(workload.kind())) {
			return false;
		}
		GenericKubernetesResource deployment = read(DEPLOYMENTS, clusterId, namespace, workload.name());
		if (deployment == null || deployment.getMetadata() == null
				|| deployment.getMetadata().getAnnotations() == null) {
			return false;
		}
		String revision = deployment.getMetadata().getAnnotations().get(REVISION);
		try {
			return revision != null && Integer.parseInt(revision.trim()) > 1;
		}
		catch (NumberFormatException ex) {
			return false;
		}
	}

	/**
	 * The workload behind a Service that has no endpoints, when — and only when — scaling
	 * it up is what would put endpoints back.
	 *
	 * <p>
	 * A Service with nothing behind it has two very different causes, and only one of
	 * them is a fix. If exactly one workload's pod template carries the Service's
	 * selector and that workload sits at zero replicas, the Service is empty because
	 * nothing was asked to run — scaling to one restores it. If NO workload matches, the
	 * selector is wrong and scaling anything changes nothing. If several match, we cannot
	 * tell which one the Service was meant for, and picking is guessing. Both of those
	 * return empty.
	 */
	Optional<WorkloadRef> idleWorkloadBehind(String clusterId, String namespace, String serviceName) {
		Map<String, String> selector = selectorOf(read(SERVICES, clusterId, namespace, serviceName));
		if (selector.isEmpty()) {
			return Optional.empty();
		}
		List<GenericKubernetesResource> matches = new ArrayList<>();
		List<String> kinds = new ArrayList<>();
		for (ResourceDescriptor descriptor : SELECTABLE_WORKLOADS) {
			for (GenericKubernetesResource workload : list(descriptor, clusterId, namespace)) {
				if (templateLabels(workload).entrySet().containsAll(selector.entrySet())) {
					matches.add(workload);
					kinds.add(descriptor.kind());
				}
			}
		}
		if (matches.size() != 1) {
			return Optional.empty();
		}
		GenericKubernetesResource only = matches.get(0);
		if (replicas(only) != 0) {
			return Optional.empty();
		}
		return Optional.of(new WorkloadRef(kinds.get(0), only.getMetadata().getName()));
	}

	/**
	 * Why every Service in scope has nothing behind it, in a fixed number of API calls.
	 *
	 * <p>
	 * {@link #idleWorkloadBehind} answers this for one Service, at a cost of a GET plus a
	 * LIST per workload kind. Asking it per finding does not scale: a cluster with
	 * fifteen empty Services would spend sixty calls on a request that already takes
	 * seconds. This lists the Services once and each workload kind once — <b>three calls
	 * for the whole scope, however many Services are involved</b> — and matches in
	 * memory.
	 * @param namespace the namespace to scope to, or null for every namespace
	 * @return backing keyed by {@code namespace/name}. A Service with no selector is
	 * absent: it never picks pods, so its Endpoints come from somewhere this cannot see.
	 */
	Map<String, ServiceBacking> backingByService(String clusterId, String namespace) {
		Map<String, List<GenericKubernetesResource>> workloads = new HashMap<>();
		Map<String, String> kinds = new HashMap<>();
		for (ResourceDescriptor descriptor : SELECTABLE_WORKLOADS) {
			for (GenericKubernetesResource workload : list(descriptor, clusterId, namespace)) {
				String ns = namespaceOf(workload);
				workloads.computeIfAbsent(ns, (key) -> new ArrayList<>()).add(workload);
				kinds.put(ns + "/" + workload.getMetadata().getName(), descriptor.kind());
			}
		}
		Map<String, ServiceBacking> out = new HashMap<>();
		for (GenericKubernetesResource service : list(SERVICES, clusterId, namespace)) {
			Map<String, String> selector = selectorOf(service);
			if (selector.isEmpty()) {
				continue;
			}
			String ns = namespaceOf(service);
			List<GenericKubernetesResource> matches = workloads.getOrDefault(ns, List.of())
				.stream()
				.filter((workload) -> templateLabels(workload).entrySet().containsAll(selector.entrySet()))
				.toList();
			out.put(ns + "/" + service.getMetadata().getName(), classify(matches, ns, kinds));
		}
		return out;
	}

	private ServiceBacking classify(List<GenericKubernetesResource> matches, String namespace,
			Map<String, String> kinds) {
		if (matches.isEmpty()) {
			return ServiceBacking.of(ServiceBacking.Cause.NO_MATCH);
		}
		if (matches.size() > 1) {
			return ServiceBacking.of(ServiceBacking.Cause.AMBIGUOUS);
		}
		GenericKubernetesResource only = matches.get(0);
		if (replicas(only) != 0) {
			return ServiceBacking.of(ServiceBacking.Cause.MATCHED_RUNNING);
		}
		String name = only.getMetadata().getName();
		return ServiceBacking.idle(new WorkloadRef(kinds.get(namespace + "/" + name), name));
	}

	private String namespaceOf(GenericKubernetesResource resource) {
		ObjectMeta meta = resource.getMetadata();
		return (meta != null && meta.getNamespace() != null) ? meta.getNamespace() : "";
	}

	/** The descriptor for a workload kind, or null when the kind is not one we act on. */
	static ResourceDescriptor descriptorFor(String kind) {
		return switch (kind) {
			case "Deployment" -> DEPLOYMENTS;
			case "StatefulSet" -> STATEFUL_SETS;
			case "DaemonSet" -> DAEMON_SETS;
			default -> null;
		};
	}

	private GenericKubernetesResource read(ResourceDescriptor descriptor, String clusterId, String namespace,
			String name) {
		try {
			return resources.getRaw(clusterId, descriptor, namespace, name);
		}
		catch (RuntimeException ex) {
			log.debug("Could not read {}/{} in {}: {}", descriptor.kind(), name, namespace, ex.getMessage());
			return null;
		}
	}

	private List<GenericKubernetesResource> list(ResourceDescriptor descriptor, String clusterId, String namespace) {
		try {
			return resources.listRaw(clusterId, descriptor, namespace);
		}
		catch (RuntimeException ex) {
			log.debug("Could not list {} in {}: {}", descriptor.kind(), namespace, ex.getMessage());
			return List.of();
		}
	}

	private OwnerReference controllerOf(GenericKubernetesResource resource) {
		ObjectMeta meta = (resource != null) ? resource.getMetadata() : null;
		List<OwnerReference> owners = (meta != null) ? meta.getOwnerReferences() : null;
		if (owners == null || owners.isEmpty()) {
			return null;
		}
		return owners.stream().filter((o) -> Boolean.TRUE.equals(o.getController())).findFirst().orElse(owners.get(0));
	}

	private Map<String, String> selectorOf(GenericKubernetesResource service) {
		return strings(map(spec(service).get("selector")));
	}

	private Map<String, String> templateLabels(GenericKubernetesResource workload) {
		return strings(map(map(map(spec(workload).get("template")).get("metadata")).get("labels")));
	}

	private int replicas(GenericKubernetesResource workload) {
		Object value = spec(workload).get("replicas");
		// Absent means one, not zero — that is the API's default, and treating it as zero
		// would offer a scale-up for a workload that is already running its single
		// replica.
		return (value instanceof Number n) ? n.intValue() : 1;
	}

	private Map<String, Object> spec(GenericKubernetesResource resource) {
		return (resource != null) ? map(resource.getAdditionalProperties().get("spec")) : Map.of();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(Object value) {
		return (value instanceof Map) ? (Map<String, Object>) value : Map.of();
	}

	private Map<String, String> strings(Map<String, Object> raw) {
		Map<String, String> out = new LinkedHashMap<>();
		raw.forEach((key, value) -> {
			if (value != null) {
				out.put(key, value.toString());
			}
		});
		return out;
	}

}

package org.alexmond.kweblens.resource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * Resolves the RELATIONS of an object — the questions a detail view should answer that
 * cannot be answered from the object alone.
 *
 * <p>
 * This exists because the UI's section registry is a pure function of one object
 * ({@code (o) => body}), so "which pods back this Service", "what mounts this Secret" and
 * "which pods does this selector match" are not merely unimplemented there — they are
 * inexpressible. Doing the joins here means one implementation serves the SPA, a future
 * TUI and the agent tool surface, instead of each reimplementing them. See
 * {@code docs/design/detail-sections-audit.md}.
 *
 * <p>
 * <b>Every relation is independently guarded and bounded.</b> A drawer open must not
 * become a cluster-wide scan, and one unreadable kind must not blank the whole detail —
 * so each join returns a {@link Relation} carrying items, an error, or "not permitted",
 * and never throws.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelationService {

	/**
	 * Cap on related objects returned per relation. Bounded because the reverse joins
	 * ("what mounts this Secret") have no server-side selector and must list pods; a
	 * namespace with thousands of pods would otherwise turn opening a drawer into a large
	 * transfer. Truncation is reported rather than hidden.
	 */
	private static final int MAX_ITEMS = 100;

	private final ClusterRegistry clusters;

	/**
	 * Every relation kweblens knows how to resolve for this kind. Keyed by a stable name
	 * the UI maps to a section. A kind with no relations returns an empty map and costs
	 * nothing.
	 */
	public Map<String, Relation> relationsFor(String clusterId, ResourceDescriptor descriptor,
			GenericKubernetesResource object) {
		Map<String, Relation> out = new LinkedHashMap<>();
		String kind = descriptor.kind();
		String namespace = (object.getMetadata() != null) ? object.getMetadata().getNamespace() : null;
		String name = (object.getMetadata() != null) ? object.getMetadata().getName() : null;
		if (namespace == null || name == null) {
			return out;
		}
		if ("Service".equals(kind)) {
			out.put("endpoints", endpoints(clusterId, namespace, name));
		}
		if (hasSelector(object)) {
			out.put("selectedPods", selectedPods(clusterId, namespace, object));
		}
		if ("Secret".equals(kind) || "ConfigMap".equals(kind)) {
			out.put("mountedBy", mountedBy(clusterId, namespace, kind, name));
		}
		return out;
	}

	/**
	 * The Endpoints backing a Service, which is how you tell "no pods match my selector"
	 * from "pods match but none are ready" — the single most common Service
	 * misconfiguration, and invisible from the Service object itself.
	 */
	private Relation endpoints(String clusterId, String namespace, String serviceName) {
		try {
			var endpoints = this.clusters.require(clusterId)
				.endpoints()
				.inNamespace(namespace)
				.withName(serviceName)
				.get();
			if (endpoints == null) {
				// No Endpoints object at all is itself the answer: nothing is backing it.
				return Relation.of(List.of());
			}
			return bound(List.of(endpoints));
		}
		catch (RuntimeException ex) {
			log.debug("Endpoints lookup failed for {}/{}: {}", namespace, serviceName, ex.getMessage());
			return Relation.from(ex);
		}
	}

	/**
	 * Pods matched by the object's selector — for a Service, what it actually routes to;
	 * for a workload, which replicas it owns. Uses a server-side label selector, so the
	 * API server does the filtering rather than kweblens listing the namespace.
	 */
	private Relation selectedPods(String clusterId, String namespace, GenericKubernetesResource object) {
		Map<String, String> selector = selectorOf(object);
		if (selector.isEmpty()) {
			// A selector-less object matches nothing; saying so beats an unexplained
			// blank.
			return Relation.of(List.of());
		}
		try {
			List<Pod> pods = this.clusters.require(clusterId)
				.pods()
				.inNamespace(namespace)
				.withLabels(selector)
				.list()
				.getItems();
			return bound(pods);
		}
		catch (RuntimeException ex) {
			log.debug("Selector pod lookup failed in {}: {}", namespace, ex.getMessage());
			return Relation.from(ex);
		}
	}

	/**
	 * Which pods mount this Secret/ConfigMap — as a volume, via {@code envFrom}, or via a
	 * single {@code valueFrom} key reference.
	 *
	 * <p>
	 * This is a REVERSE join with no server-side selector available, so it lists the
	 * namespace's pods and filters locally. That is why it is bounded and
	 * namespace-scoped: a cluster-wide version of this query would be genuinely
	 * expensive, and a drawer is not the place to pay for it.
	 */
	private Relation mountedBy(String clusterId, String namespace, String kind, String name) {
		try {
			List<Pod> pods = this.clusters.require(clusterId).pods().inNamespace(namespace).list().getItems();
			List<Object> matches = new ArrayList<>();
			for (Pod pod : pods) {
				if (podReferences(pod, kind, name)) {
					matches.add(pod);
				}
			}
			return bound(matches);
		}
		catch (RuntimeException ex) {
			log.debug("mountedBy scan failed in {}: {}", namespace, ex.getMessage());
			return Relation.from(ex);
		}
	}

	private boolean podReferences(Pod pod, String kind, String name) {
		if (pod.getSpec() == null) {
			return false;
		}
		boolean secret = "Secret".equals(kind);
		if (volumeReferences(pod, secret, name)) {
			return true;
		}
		return containerReferences(pod, secret, name);
	}

	private boolean volumeReferences(Pod pod, boolean secret, String name) {
		if (pod.getSpec().getVolumes() == null) {
			return false;
		}
		return pod.getSpec().getVolumes().stream().anyMatch((v) -> {
			if (secret) {
				return v.getSecret() != null && name.equals(v.getSecret().getSecretName());
			}
			return v.getConfigMap() != null && name.equals(v.getConfigMap().getName());
		});
	}

	private boolean containerReferences(Pod pod, boolean secret, String name) {
		var containers = new ArrayList<io.fabric8.kubernetes.api.model.Container>();
		if (pod.getSpec().getContainers() != null) {
			containers.addAll(pod.getSpec().getContainers());
		}
		if (pod.getSpec().getInitContainers() != null) {
			containers.addAll(pod.getSpec().getInitContainers());
		}
		for (var container : containers) {
			if (container.getEnvFrom() != null && container.getEnvFrom().stream().anyMatch((from) -> {
				if (secret) {
					return from.getSecretRef() != null && name.equals(from.getSecretRef().getName());
				}
				return from.getConfigMapRef() != null && name.equals(from.getConfigMapRef().getName());
			})) {
				return true;
			}
			if (container.getEnv() != null
					&& container.getEnv().stream().anyMatch((e) -> envReferences(e, secret, name))) {
				return true;
			}
		}
		return false;
	}

	private boolean envReferences(io.fabric8.kubernetes.api.model.EnvVar env, boolean secret, String name) {
		if (env.getValueFrom() == null) {
			return false;
		}
		if (secret) {
			var ref = env.getValueFrom().getSecretKeyRef();
			return ref != null && name.equals(ref.getName());
		}
		var ref = env.getValueFrom().getConfigMapKeyRef();
		return ref != null && name.equals(ref.getName());
	}

	/** True when the object has a selector we can resolve to pods. */
	private boolean hasSelector(GenericKubernetesResource object) {
		return !selectorOf(object).isEmpty();
	}

	/**
	 * The object's pod selector. Services carry a flat {@code spec.selector}; workloads
	 * nest it under {@code matchLabels}. Only {@code matchLabels} is supported —
	 * set-based {@code matchExpressions} cannot be passed to {@code withLabels()} without
	 * translating the full grammar, and every built-in workload controller writes
	 * matchLabels.
	 */
	@SuppressWarnings("unchecked")
	private Map<String, String> selectorOf(GenericKubernetesResource object) {
		Object raw = object.get("spec", "selector");
		if (!(raw instanceof Map<?, ?> map)) {
			return Map.of();
		}
		Object nested = map.get("matchLabels");
		Map<?, ?> selector = (nested instanceof Map<?, ?> inner) ? inner : map;
		Map<String, String> out = new LinkedHashMap<>();
		for (var entry : selector.entrySet()) {
			if (entry.getKey() instanceof String key && entry.getValue() instanceof String value) {
				out.put(key, value);
			}
		}
		return out;
	}

	private Relation bound(List<? extends Object> items) {
		List<? extends Object> capped = (items.size() <= MAX_ITEMS) ? items : items.subList(0, MAX_ITEMS);
		return Relation.of(List.copyOf(capped.stream().map(this::forDisplay).toList()), items.size() > MAX_ITEMS);
	}

	/**
	 * Strip {@code managedFields} from a related object.
	 *
	 * <p>
	 * Relation items exist to be displayed in a table — nothing reads their server-side
	 * field-management bookkeeping, and it is bulky: measured on a real Service it was
	 * <b>35% of the whole detail payload</b>, and that multiplies by the item cap. The
	 * main object is left untouched; only the related items are trimmed, because those
	 * are the ones returned in quantity.
	 */
	private Object forDisplay(Object item) {
		if (item instanceof HasMetadata resource && resource.getMetadata() != null) {
			resource.getMetadata().setManagedFields(null);
		}
		return item;
	}

}

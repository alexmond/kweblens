package org.alexmond.kweblens.log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;

/**
 * Expands a user's request ("logs for this pod" / "logs for this Deployment") into the
 * concrete set of {@link LogSource}s to follow.
 *
 * <p>
 * This is what makes multi-source logs possible: a pod fans out to its containers, and a
 * workload fans out to its pods (via its label selector) and then to their containers —
 * the {@code stern} / {@code kubectl logs deployment/x --all-containers} behaviour.
 */
@Service
@RequiredArgsConstructor
public class LogSourceResolver {

	private final ClusterRegistry clusters;

	private final ResourceService resources;

	/**
	 * Every container of one pod. Init containers are included last (and only when asked)
	 * because their logs are usually finished and would otherwise dilute a live tail —
	 * though for a pod stuck in {@code Init:CrashLoopBackOff} they are the whole story.
	 */
	public List<LogSource> forPod(String clusterId, String namespace, String pod, boolean includeInitContainers) {
		Pod found = clusters.require(clusterId).pods().inNamespace(namespace).withName(pod).get();
		if (found == null) {
			throw new IllegalArgumentException("No such pod: " + namespace + "/" + pod);
		}
		return sourcesFor(found, includeInitContainers);
	}

	/**
	 * Every container of every pod behind a workload, resolved through the workload's
	 * label selector (the same way the Kubernetes controller finds them). Pods are
	 * ordered by name so the source list — and therefore the client's colour assignment —
	 * is stable across reconnects.
	 */
	public List<LogSource> forWorkload(String clusterId, ResourceDescriptor descriptor, String namespace, String name,
			boolean includeInitContainers) {
		Map<String, String> selector = selectorOf(clusterId, descriptor, namespace, name);
		List<Pod> pods = clusters.require(clusterId)
			.pods()
			.inNamespace(namespace)
			.withLabels(selector)
			.list()
			.getItems()
			.stream()
			.sorted(Comparator.comparing((p) -> p.getMetadata().getName()))
			.toList();
		List<LogSource> sources = new ArrayList<>();
		for (Pod pod : pods) {
			sources.addAll(sourcesFor(pod, includeInitContainers));
		}
		return sources;
	}

	/**
	 * The label selector a workload uses to find its pods.
	 *
	 * <p>
	 * Only {@code matchLabels} is supported: it is what every built-in workload
	 * controller actually writes, and {@code matchExpressions} cannot be passed to
	 * {@code withLabels()} without translating the full set-based grammar. A workload
	 * with no {@code matchLabels} fails loudly rather than silently following nothing —
	 * the failure mode that would otherwise look like "the workload has no logs".
	 */
	private Map<String, String> selectorOf(String clusterId, ResourceDescriptor descriptor, String namespace,
			String name) {
		GenericKubernetesResource workload = resources.getRaw(clusterId, descriptor, namespace, name);
		if (workload == null) {
			throw new IllegalArgumentException("No such " + descriptor.kind() + ": " + namespace + "/" + name);
		}
		Map<String, String> matchLabels = workload.get("spec", "selector", "matchLabels");
		if (matchLabels == null || matchLabels.isEmpty()) {
			throw new IllegalArgumentException(descriptor.kind() + " " + namespace + "/" + name
					+ " has no spec.selector.matchLabels, " + "so its pods cannot be resolved for log following");
		}
		return matchLabels;
	}

	private List<LogSource> sourcesFor(Pod pod, boolean includeInitContainers) {
		String namespace = pod.getMetadata().getNamespace();
		String name = pod.getMetadata().getName();
		List<LogSource> sources = new ArrayList<>();
		for (Container container : pod.getSpec().getContainers()) {
			sources.add(new LogSource(namespace, name, container.getName()));
		}
		if (includeInitContainers && pod.getSpec().getInitContainers() != null) {
			for (Container container : pod.getSpec().getInitContainers()) {
				sources.add(new LogSource(namespace, name, container.getName()));
			}
		}
		return sources;
	}

}

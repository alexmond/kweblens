package org.alexmond.kweblens.resource;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * Read access to Kubernetes resources, projected into the kind-agnostic
 * {@link ResourceSummary} rows the UI and CLI render. Each call resolves the target
 * cluster's client through the {@link ClusterRegistry}, so a cluster is addressed purely
 * by its id.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceService {

	private final ClusterRegistry clusters;

	/** List every namespace in the cluster. */
	public List<ResourceSummary> listNamespaces(String clusterId) {
		KubernetesClient client = clusters.require(clusterId);
		return client.namespaces().list().getItems().stream().map(this::toNamespaceSummary).toList();
	}

	/**
	 * List pods in a namespace (or all namespaces when {@code namespace} is null/blank).
	 */
	public List<ResourceSummary> listPods(String clusterId, String namespace) {
		KubernetesClient client = clusters.require(clusterId);
		List<Pod> pods = (namespace == null || namespace.isBlank()) ? client.pods().inAnyNamespace().list().getItems()
				: client.pods().inNamespace(namespace).list().getItems();
		return pods.stream().map(this::toPodSummary).toList();
	}

	private ResourceSummary toNamespaceSummary(Namespace ns) {
		String status = (ns.getStatus() != null) ? ns.getStatus().getPhase() : null;
		return new ResourceSummary("Namespace", null, name(ns), status, age(ns));
	}

	private ResourceSummary toPodSummary(Pod pod) {
		String status = (pod.getStatus() != null) ? pod.getStatus().getPhase() : null;
		return new ResourceSummary("Pod", namespace(pod), name(pod), status, age(pod));
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

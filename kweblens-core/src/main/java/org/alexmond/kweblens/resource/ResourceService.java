package org.alexmond.kweblens.resource;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
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
	 * The YAML of a single resource, or null if it does not exist.
	 */
	public String getYaml(String clusterId, ResourceDescriptor descriptor, String namespace, String name) {
		var op = clusters.require(clusterId).genericKubernetesResources(contextFor(descriptor));
		GenericKubernetesResource resource = descriptor.namespaced() ? op.inNamespace(namespace).withName(name).get()
				: op.withName(name).get();
		return (resource != null) ? Serialization.asYaml(resource) : null;
	}

	/**
	 * Apply a YAML manifest (server-side apply). The manifest is self-describing, so no
	 * descriptor is needed. Returns a summary of the applied resource.
	 */
	public ResourceSummary apply(String clusterId, String yaml) {
		KubernetesClient client = clusters.require(clusterId);
		HasMetadata parsed = Serialization.unmarshal(yaml);
		HasMetadata applied = client.resource(parsed).serverSideApply();
		return new ResourceSummary(applied.getKind(), namespace(applied), name(applied), null, "-");
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
		return list(clusterId, ResourceDescriptor.coreCluster("namespaces", "Namespaces", "Namespace", "namespaces"),
				null);
	}

	/**
	 * List pods in a namespace (or all namespaces when {@code namespace} is null/blank).
	 */
	public List<ResourceSummary> listPods(String clusterId, String namespace) {
		return list(clusterId, ResourceDescriptor.coreNamespaced("pods", "Pods", "Pod", "pods"), namespace);
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

package org.alexmond.kweblens.resource;

import java.util.List;

import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionSpec;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionVersion;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * Discovers a cluster's CustomResourceDefinitions and turns each into a
 * {@link ResourceDescriptor}, so custom kinds flow through the same generic access + nav
 * path as built-ins. Cached (short TTL) because the nav asks for this on every in-cluster
 * page render.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrdService {

	private final ClusterRegistry clusters;

	/** Descriptors for every CRD in the cluster; empty if the API cannot be reached. */
	@Cacheable(cacheNames = "crds", sync = true)
	public List<ResourceDescriptor> customResourceDescriptors(String clusterId) {
		try {
			KubernetesClient client = clusters.require(clusterId);
			return client.apiextensions()
				.v1()
				.customResourceDefinitions()
				.list()
				.getItems()
				.stream()
				.map(this::toDescriptor)
				.filter((d) -> d != null)
				.toList();
		}
		catch (RuntimeException ex) {
			log.warn("Could not discover CRDs for cluster '{}': {}", clusterId, ex.getMessage());
			return List.of();
		}
	}

	/**
	 * The CRD-declared printer columns for a custom kind (by its {@code group.plural}
	 * id), or empty for built-in kinds or when the CRD cannot be read. Wide columns
	 * (priority &gt; 0) are omitted, matching {@code kubectl}'s default view.
	 */
	public List<PrinterColumn> printerColumns(String clusterId, String resourceId) {
		try {
			KubernetesClient client = clusters.require(clusterId);
			return client.apiextensions()
				.v1()
				.customResourceDefinitions()
				.list()
				.getItems()
				.stream()
				.filter((crd) -> resourceId.equals(idOf(crd)))
				.findFirst()
				.map(this::columnsOf)
				.orElseGet(List::of);
		}
		catch (RuntimeException ex) {
			log.warn("Could not read printer columns for '{}' on '{}': {}", resourceId, clusterId, ex.getMessage());
			return List.of();
		}
	}

	private String idOf(CustomResourceDefinition crd) {
		CustomResourceDefinitionSpec spec = crd.getSpec();
		if (spec == null || spec.getNames() == null || spec.getGroup() == null) {
			return null;
		}
		return spec.getGroup() + "." + spec.getNames().getPlural();
	}

	private List<PrinterColumn> columnsOf(CustomResourceDefinition crd) {
		CustomResourceDefinitionSpec spec = crd.getSpec();
		String served = servedVersion(spec);
		CustomResourceDefinitionVersion version = spec.getVersions()
			.stream()
			.filter((v) -> v.getName().equals(served))
			.findFirst()
			.orElse(null);
		if (version == null || version.getAdditionalPrinterColumns() == null) {
			return List.of();
		}
		return version.getAdditionalPrinterColumns()
			.stream()
			.filter((c) -> c.getPriority() == null || c.getPriority() == 0)
			.map((c) -> new PrinterColumn(c.getName(), c.getJsonPath(), c.getType()))
			.toList();
	}

	private ResourceDescriptor toDescriptor(CustomResourceDefinition crd) {
		CustomResourceDefinitionSpec spec = crd.getSpec();
		if (spec == null || spec.getNames() == null) {
			return null;
		}
		String group = spec.getGroup();
		String plural = spec.getNames().getPlural();
		String kind = spec.getNames().getKind();
		String version = servedVersion(spec);
		if (group == null || plural == null || kind == null || version == null) {
			return null;
		}
		boolean namespaced = "Namespaced".equals(spec.getScope());
		// The label is written — `HTTP Routes`, not `HTTPRoute` — because a CRD kind now
		// sits next to built-in ones in the same list (#433). KindLabel derives it from
		// the CRD's own two names, never from a table here. The id and the kind keep
		// their raw values: routes and MCP tool arguments do not move.
		return new ResourceDescriptor(group + "." + plural, KindLabel.forCustomResource(kind, plural), kind, group,
				version, plural, namespaced, false);
	}

	private String servedVersion(CustomResourceDefinitionSpec spec) {
		if (spec.getVersions() == null || spec.getVersions().isEmpty()) {
			return null;
		}
		CustomResourceDefinitionVersion storage = spec.getVersions()
			.stream()
			.filter((v) -> Boolean.TRUE.equals(v.getStorage()))
			.findFirst()
			.orElse(spec.getVersions().get(0));
		return storage.getName();
	}

}

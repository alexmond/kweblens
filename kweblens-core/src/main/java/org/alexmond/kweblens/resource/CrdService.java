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
	 *
	 * <p>
	 * <b>A count is not a list, and neither is a lookup</b> (#459). This used to fetch
	 * every CustomResourceDefinition in the cluster and filter the result in memory —
	 * measured at <b>10 393 833 bytes</b> on the lab cluster, because a CRD carries its
	 * whole OpenAPI schema — to answer a question about one kind. The TUI asks this
	 * question synchronously on the render thread, so it was a multi-second stall inside
	 * a keystroke, once per kind visited.
	 *
	 * <p>
	 * Two things replace it, and the first is free. <b>A kind with no API group cannot be
	 * CRD-delivered</b>: a CRD's {@code spec.group} is required and its
	 * {@code metadata.name} is {@code <plural>.<group>}, so a core-group id — which is
	 * every built-in id {@code NavCatalog} spells, and {@code pods} / {@code secrets} /
	 * {@code configmaps} as discovery spells them — is answered with <b>no request at
	 * all</b>. Everything else is <b>one keyed GET</b> for that one CRD; a 404 comes back
	 * as {@code null}, which is the quiet "no declared columns" a built-in that does live
	 * in an API group ({@code apps.statefulsets}) must still get.
	 *
	 * <p>
	 * The name is derived rather than searched for because the API server validates that
	 * a CRD's {@code metadata.name} equals {@code spec.names.plural + "." + spec.group} —
	 * so a CRD it would accept is always found here, and one it would reject is the only
	 * thing this stops seeing.
	 */
	public List<PrinterColumn> printerColumns(String clusterId, String resourceId) {
		String name = crdName(resourceId);
		if (name == null) {
			return List.of();
		}
		try {
			KubernetesClient client = clusters.require(clusterId);
			CustomResourceDefinition crd = client.apiextensions().v1().customResourceDefinitions().withName(name).get();
			return (crd != null) ? columnsOf(crd) : List.of();
		}
		catch (RuntimeException ex) {
			log.warn("Could not read printer columns for '{}' on '{}': {}", resourceId, clusterId, ex.getMessage());
			return List.of();
		}
	}

	/**
	 * The exact inverse of the id {@link #toDescriptor} mints: this project's
	 * {@code <group>.<plural>} back to the CRD's own {@code <plural>.<group>} name, or
	 * {@code null} when the id names no group and therefore no CRD. The split is at the
	 * <b>last</b> dot because a group is a DNS subdomain and may hold several of them
	 * ({@code cert-manager.io.certificates}), while a plural is one lowercase label and
	 * holds none.
	 */
	private static String crdName(String resourceId) {
		if (resourceId == null) {
			return null;
		}
		int split = resourceId.lastIndexOf('.');
		if (split <= 0 || split == resourceId.length() - 1) {
			return null;
		}
		return resourceId.substring(split + 1) + "." + resourceId.substring(0, split);
	}

	private List<PrinterColumn> columnsOf(CustomResourceDefinition crd) {
		CustomResourceDefinitionSpec spec = crd.getSpec();
		if (spec == null || spec.getVersions() == null) {
			return List.of();
		}
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

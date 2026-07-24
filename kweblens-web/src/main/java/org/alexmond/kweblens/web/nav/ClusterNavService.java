package org.alexmond.kweblens.web.nav;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.resource.CrdService;
import org.alexmond.kweblens.resource.ResourceDescriptor;

/**
 * The per-cluster navigation: the static {@link NavCatalog} plus a dynamic <b>Custom
 * Resources</b> section generated from the cluster's CRDs, one category per API group.
 * Also resolves a route id back to its descriptor, whether built-in or custom.
 */
@Service
@RequiredArgsConstructor
public class ClusterNavService {

	private static final String CRD_ICON = "bi-puzzle";

	private final NavCatalog navCatalog;

	private final CrdService crdService;

	/** Static categories followed by one category per CRD API group (alphabetical). */
	public List<NavCategory> categories(String clusterId) {
		List<NavCategory> categories = new ArrayList<>(navCatalog.categories());
		Map<String, List<ResourceDescriptor>> byGroup = crdService.customResourceDescriptors(clusterId)
			.stream()
			.collect(Collectors.groupingBy(ResourceDescriptor::group, TreeMap::new, Collectors.toList()));
		byGroup.forEach((group, items) -> {
			items.sort(Comparator.comparing(ResourceDescriptor::label));
			categories.add(new NavCategory(group, CRD_ICON, items));
		});
		return categories;
	}

	/** Resolve a route id to its descriptor — built-in first, then the cluster's CRDs. */
	public Optional<ResourceDescriptor> find(String clusterId, String id) {
		return navCatalog.find(id)
			.or(() -> crdService.customResourceDescriptors(clusterId)
				.stream()
				.filter((d) -> d.id().equals(id))
				.findFirst());
	}

}

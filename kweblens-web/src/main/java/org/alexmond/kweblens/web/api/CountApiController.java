package org.alexmond.kweblens.web.api;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.web.nav.NavCatalog;
import org.alexmond.kweblens.web.nav.NavCategory;

/**
 * Per-kind resource counts for the built-in Navigator kinds, keyed by route id, so the UI
 * can show count badges (vCenter-style). CRD kinds are intentionally excluded to bound
 * the work. A kind that cannot be listed is simply omitted, never failing the whole
 * response.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}")
@RequiredArgsConstructor
public class CountApiController {

	private final NavCatalog navCatalog;

	private final ResourceService resources;

	@GetMapping("/counts")
	public Map<String, Integer> counts(@PathVariable String clusterId) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (NavCategory category : navCatalog.categories()) {
			for (ResourceDescriptor descriptor : category.items()) {
				try {
					counts.put(descriptor.id(), resources.listRaw(clusterId, descriptor, null).size());
				}
				catch (RuntimeException ex) {
					log.debug("Count skipped for '{}': {}", descriptor.id(), ex.getMessage());
				}
			}
		}
		return counts;
	}

}

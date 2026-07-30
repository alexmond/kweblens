package org.alexmond.kweblens.web.api;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.health.HealthService;
import org.alexmond.kweblens.health.KindHealth;
import org.alexmond.kweblens.health.WorkloadHealth;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.web.nav.NavCatalog;

/**
 * Workload health for the overview: per-kind tallies plus the <b>named</b> objects
 * needing attention.
 *
 * <p>
 * Replaces the client fetching every object of seven kinds to compute seven numbers. The
 * browser now receives a summary, and — because the reasons are computed here too — it
 * also gets the answer to "which ones", which previously required the collection anyway.
 *
 * <p>
 * Kinds come from the <b>Workloads nav category</b> rather than a second hardcoded list,
 * so adding a workload kind to the catalog automatically includes it here.
 */
@RestController
@RequiredArgsConstructor
public class HealthApiController {

	private static final String WORKLOADS = "Workloads";

	private final NavCatalog navCatalog;

	private final HealthService health;

	@GetMapping(value = "/api/v1/clusters/{clusterId}/workload-health", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<KindHealth> workloadHealth(@PathVariable String clusterId,
			@RequestParam(required = false) String namespace) {
		List<ResourceDescriptor> kinds = this.navCatalog.categories()
			.stream()
			.filter((c) -> WORKLOADS.equals(c.label()))
			.flatMap((c) -> c.items().stream())
			// Only kinds the health rules actually understand. A kind with no rule would
			// otherwise be reported as uniformly healthy, which is a claim rather than a
			// measurement.
			.filter((d) -> WorkloadHealth.supports(d.kind()))
			.toList();
		return this.health.summarise(clusterId, kinds, namespace);
	}

}

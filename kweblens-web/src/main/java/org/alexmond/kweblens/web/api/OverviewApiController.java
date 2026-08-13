package org.alexmond.kweblens.web.api;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import org.alexmond.kweblens.health.ConfigUsageService;
import org.alexmond.kweblens.health.HealthService;
import org.alexmond.kweblens.health.KindHealth;
import org.alexmond.kweblens.health.NetworkHealthService;
import org.alexmond.kweblens.health.StatusVocabulary;
import org.alexmond.kweblens.health.StorageHealthService;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.web.nav.NavCatalog;

/**
 * The category overviews: per-kind tallies plus the <b>named</b> objects needing
 * attention.
 *
 * <p>
 * One endpoint for every category because every overview asks the same shape of question
 * — how many, how many are wrong, and which ones — even though the checks behind them
 * differ completely. That keeps a single client component rendering all of them, and it
 * means a new category is a service plus a case here rather than a new screen.
 *
 * <p>
 * The checks themselves live in {@code kweblens-core}, so the same summaries can serve a
 * future TUI and the agent tool surface without either reimplementing them.
 */
@RestController
@RequiredArgsConstructor
public class OverviewApiController {

	private static final String WORKLOADS = "Workloads";

	private final NavCatalog navCatalog;

	private final HealthService health;

	private final NetworkHealthService network;

	private final StorageHealthService storage;

	private final ConfigUsageService config;

	@GetMapping(value = "/api/v1/clusters/{clusterId}/overview/{category}", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<KindHealth> overview(@PathVariable String clusterId, @PathVariable String category,
			@RequestParam(required = false) String namespace) {
		return switch (category) {
			case "workloads" -> workloads(clusterId, namespace);
			case "network" -> this.network.summarise(clusterId, namespace);
			case "storage" -> this.storage.summarise(clusterId, namespace);
			case "config" -> this.config.summarise(clusterId, namespace);
			// An unknown category is a client bug, not an empty cluster. Returning []
			// would
			// render as a healthy, empty dashboard.
			default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No overview for category: " + category);
		};
	}

	/**
	 * Workload kinds come from the <b>Workloads nav category</b> rather than a second
	 * hardcoded list, so adding a workload kind to the catalog automatically includes it
	 * here.
	 */
	private List<KindHealth> workloads(String clusterId, String namespace) {
		List<ResourceDescriptor> kinds = this.navCatalog.categories()
			.stream()
			.filter((c) -> WORKLOADS.equals(c.label()))
			.flatMap((c) -> c.items().stream())
			// Only kinds the health rules actually understand. A kind with no rule would
			// otherwise be reported as uniformly healthy, which is a claim rather than a
			// measurement. Asked of StatusVocabulary, which also decides whether a list
			// row carries a state — so "the card has a breakdown" and "the rows are
			// selectable by it" are one set, not two lists that can diverge.
			.filter((d) -> StatusVocabulary.covers(d.kind()))
			.toList();
		return this.health.summarise(clusterId, kinds, namespace);
	}

}

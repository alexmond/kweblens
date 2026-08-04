package org.alexmond.kweblens.web.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.web.nav.ClusterNavService;
import org.alexmond.kweblens.web.nav.NavCategory;

/**
 * Per-kind resource counts, keyed by route id, so the nav can show count badges.
 *
 * <p>
 * Counts come from the <b>per-cluster</b> nav rather than the static catalog. The static
 * one omits every CRD-backed kind, which meant Gateway and Custom Resources had no counts
 * at all — and the client, finding none, fell back to counting menu entries, so those
 * badges silently showed a different quantity in the same styling as the real ones.
 *
 * <p>
 * That triples the number of kinds on a typical cluster (39 → 118 here, 72 of them CRDs),
 * so the listing is done <b>concurrently</b>. Sequentially it would take about three
 * seconds, and this endpoint is re-fetched on every namespace switch — a badge is not
 * worth making navigation feel slow. Concurrency is bounded rather than unlimited: firing
 * 118 simultaneous list calls at an API server to populate sidebar numbers is impolite,
 * and a burst like that is exactly what rate limiting exists to stop.
 *
 * <p>
 * A kind that cannot be listed is simply omitted, never failing the whole response.
 *
 * <p>
 * The counting itself is {@link ResourceService#count} — one {@code limit=1} request per
 * kind, not a full LIST. Before that it fetched every object of every kind to produce
 * these integers: about 23 MB of JSON per call on a small real cluster, re-fetched on
 * every namespace switch. See that method for how an absent
 * {@code metadata.remainingItemCount} is handled.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}")
@RequiredArgsConstructor
public class CountApiController {

	/**
	 * Simultaneous list calls. Enough to hide the latency of a hundred-odd kinds, small
	 * enough that a sidebar refresh is not a burst the API server has to absorb.
	 */
	private static final int MAX_CONCURRENT = 12;

	/**
	 * Virtual threads: these tasks are blocking HTTP calls that spend their life waiting,
	 * so a platform thread each would be pure overhead. The pool is fixed-size purely to
	 * cap concurrency, not to cap threads.
	 */
	private final ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT, (runnable) -> {
		Thread thread = new Thread(runnable, "counts");
		thread.setDaemon(true);
		return thread;
	});

	private final ClusterNavService clusterNav;

	private final ResourceService resources;

	@GetMapping("/counts")
	public Map<String, Integer> counts(@PathVariable String clusterId,
			@RequestParam(required = false) String namespace) {
		List<ResourceDescriptor> kinds = this.clusterNav.categories(clusterId)
			.stream()
			.flatMap((category) -> allItems(category).stream())
			.toList();
		Map<String, Integer> counts = new ConcurrentHashMap<>();
		List<Future<?>> pending = new ArrayList<>();
		for (ResourceDescriptor descriptor : kinds) {
			pending.add(this.executor.submit(() -> count(clusterId, descriptor, namespace, counts)));
		}
		for (Future<?> future : pending) {
			await(future);
		}
		return counts;
	}

	/**
	 * A category's own kinds plus any in its subgroups — Custom Resources nests by API
	 * group.
	 */
	private List<ResourceDescriptor> allItems(NavCategory category) {
		List<ResourceDescriptor> all = new ArrayList<>(category.items());
		category.subgroups().forEach((group) -> all.addAll(group.items()));
		return all;
	}

	private void count(String clusterId, ResourceDescriptor descriptor, String namespace, Map<String, Integer> into) {
		try {
			// Namespaced kinds count within the selected namespace; cluster-scoped kinds
			// ignore it (ResourceService.count does this), so the badge stays
			// cluster-wide.
			into.put(descriptor.id(), this.resources.count(clusterId, descriptor, namespace));
		}
		catch (RuntimeException ex) {
			log.debug("Count skipped for '{}': {}", descriptor.id(), ex.getMessage());
		}
	}

	private void await(Future<?> future) {
		try {
			future.get();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		catch (ExecutionException ex) {
			log.debug("Count task failed: {}", ex.getMessage());
		}
	}

}

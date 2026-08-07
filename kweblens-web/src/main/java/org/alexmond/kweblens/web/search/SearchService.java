package org.alexmond.kweblens.web.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.resource.ResourceSummary;
import org.alexmond.kweblens.web.nav.ClusterNavService;
import org.alexmond.kweblens.web.nav.NavCategory;

/**
 * Global search: find an object by name across kinds and namespaces, without already
 * knowing which of the cluster's ~120 kinds it lives in.
 *
 * <h2>Where the cost goes, and what is bounded</h2>
 *
 * <p>
 * The expensive dimension is the <b>number of kinds</b>, not the number of objects. A
 * cluster here has 118 kinds but only a few hundred objects in the ones anybody searches;
 * listing thirteen kinds costs thirteen round trips, listing all of them costs 118 — and
 * 263 of the objects are custom resources nobody looks up by name. So the kinds are
 * bounded ({@link SearchKinds}) and each bounded kind is listed <b>in full</b>.
 *
 * <p>
 * That is a deliberate departure from capping each list with a server-side {@code limit}.
 * A {@code limit} truncates in the API server's own key order, which has nothing to do
 * with the query: the object you are searching for is simply absent if it sorts past the
 * cap, and nothing in the response can tell you that happened. Truncating the
 * <b>matches</b> instead keeps every answer inside a searched kind complete, and the only
 * incompleteness left — the kinds not in the set — is reported by name. Listing the
 * thirteen in full is what makes "not found" mean something.
 *
 * <p>
 * Two further bounds keep a burst polite: at most {@link #MAX_CONCURRENT} simultaneous
 * list calls (the same reasoning as the nav count badges — a keystroke should not be a
 * thundering herd at the API server), and at most {@link #PER_KIND_CANDIDATES} rows
 * carried forward per kind. The per-kind cap is applied <b>after</b> scoring, so it drops
 * the worst matches rather than an arbitrary slice, and every match is still counted for
 * the honest total.
 *
 * <h2>Superseded searches</h2>
 *
 * <p>
 * A palette search is abandoned far more often than it is read: the client aborts the
 * previous request on every keystroke past its debounce. Nothing tells the server that. A
 * plain synchronous handler gets no disconnect signal — an {@code HttpServletResponse}
 * learns its client is gone from a failed write, and a search that is still listing has
 * not written yet — so the abandoned work used to run to completion, and, worse, its
 * queued tasks sat in this pool's FIFO <b>ahead of</b> the query the operator was
 * actually waiting for.
 *
 * <p>
 * So supersession is derived from the only signal that is actually available: <b>a newer
 * search for the same cluster</b>. Starting one cancels the previous attempt's tasks,
 * which drains the not-yet-started ones out of the queue. The superseded caller still
 * gets a well-formed answer — cancelled kinds are reported by name in
 * {@link SearchResult#skippedKinds()}, never silently dropped, because a short list that
 * looks complete is the one result this must not produce.
 *
 * <p>
 * Listing goes through {@link ResourceService}'s projection rather than a second listing
 * path, so a search row is the same {@link ResourceSummary} the tables render.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

	/** Rows returned when the caller does not ask for a size. */
	public static final int DEFAULT_LIMIT = 20;

	/** Ceiling on the requested size — a palette dropdown, not a report. */
	public static final int MAX_LIMIT = 100;

	/**
	 * Simultaneous list calls. Smaller than the count endpoint's 12 because this fires on
	 * (debounced) typing rather than on navigation, so it can repeat several times a
	 * second.
	 */
	private static final int MAX_CONCURRENT = 8;

	/**
	 * Best matches carried forward per kind. Scoring is a string compare, so scoring
	 * every object is free; keeping every match is not, and 100 from one kind already
	 * exceeds any dropdown.
	 */
	private static final int PER_KIND_CANDIDATES = 100;

	/** What a kind cancelled by a newer search reports as its reason. */
	static final String SUPERSEDED = "not searched — a newer query for this cluster replaced this one";

	/**
	 * Virtual threads would be ideal here, but the pool exists to cap concurrency, so a
	 * small fixed pool of daemon threads does the job without a second knob.
	 */
	private final ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT, (runnable) -> {
		Thread thread = new Thread(runnable, "search");
		thread.setDaemon(true);
		return thread;
	});

	/** The in-flight attempt per cluster, so a newer search can cancel an older one. */
	private final ConcurrentMap<String, Attempt> inFlight = new ConcurrentHashMap<>();

	private final ClusterNavService clusterNav;

	private final ResourceService resources;

	/**
	 * Search a cluster for objects whose name contains {@code query}.
	 * @param clusterId the cluster to search
	 * @param query the free-text query; blank returns an empty result rather than
	 * everything
	 * @param namespace namespace filter, or null/blank for cluster-wide (cluster-scoped
	 * kinds ignore it)
	 * @param limit maximum rows to return, clamped to {@link #MAX_LIMIT}
	 * @return the ranked hits plus what was truncated and what was never searched
	 */
	public SearchResult search(String clusterId, String query, String namespace, int limit) {
		String trimmed = (query != null) ? query.trim() : "";
		String scope = (namespace != null && !namespace.isBlank()) ? namespace : null;
		if (trimmed.isEmpty()) {
			return SearchResult.empty(trimmed, scope);
		}
		long start = System.nanoTime();
		List<ResourceDescriptor> allKinds = allKinds(clusterId);
		Map<String, ResourceDescriptor> byId = allKinds.stream()
			.collect(Collectors.toMap(ResourceDescriptor::id, Function.identity(), (a, b) -> a));
		List<ResourceDescriptor> targets = SearchKinds.ids().stream().map(byId::get).filter(Objects::nonNull).toList();
		List<KindResult> results = runAll(clusterId, targets, trimmed.toLowerCase(Locale.ROOT), scope);
		return assemble(trimmed, scope, allKinds, targets, results, clamp(limit),
				(System.nanoTime() - start) / 1_000_000L);
	}

	/** Every kind this cluster's nav models, including CRDs nested under sub-groups. */
	private List<ResourceDescriptor> allKinds(String clusterId) {
		List<ResourceDescriptor> all = new ArrayList<>();
		for (NavCategory category : this.clusterNav.categories(clusterId)) {
			all.addAll(category.items());
			category.subgroups().forEach((group) -> all.addAll(group.items()));
		}
		return all;
	}

	private List<KindResult> runAll(String clusterId, List<ResourceDescriptor> targets, String query,
			String namespace) {
		Attempt attempt = new Attempt();
		Attempt previous = this.inFlight.put(clusterId, attempt);
		if (previous != null) {
			previous.supersede();
		}
		try {
			List<Future<KindResult>> pending = new ArrayList<>();
			for (ResourceDescriptor descriptor : targets) {
				pending.add(attempt.submit(this.executor, () -> searchKind(clusterId, descriptor, query, namespace)));
			}
			List<KindResult> results = new ArrayList<>();
			for (int i = 0; i < pending.size(); i++) {
				results.add(await(targets.get(i), pending.get(i)));
			}
			return results;
		}
		finally {
			// Only if it is still ours: a newer attempt has already taken the slot.
			this.inFlight.remove(clusterId, attempt);
		}
	}

	/**
	 * One kind: list it, score every name, keep the best {@link #PER_KIND_CANDIDATES} —
	 * but count them all, so the caller's "showing N of M" is measured rather than
	 * capped.
	 */
	private KindResult searchKind(String clusterId, ResourceDescriptor descriptor, String query, String namespace) {
		List<ResourceSummary> rows;
		try {
			rows = this.resources.list(clusterId, descriptor, namespace);
		}
		catch (RuntimeException ex) {
			log.debug("Search skipped '{}': {}", descriptor.id(), ex.getMessage());
			return new KindResult(descriptor, List.of(), 0, message(ex));
		}
		int bonus = SearchKinds.bonus(descriptor.id());
		List<SearchHit> matches = new ArrayList<>();
		for (ResourceSummary row : rows) {
			int score = SearchRanking.score(query, row.name());
			if (score >= 0) {
				matches.add(new SearchHit(descriptor.id(), row.kind(), row.namespace(), row.name(), row.status(),
						row.age(), score + bonus));
			}
		}
		matches.sort(byRank());
		int total = matches.size();
		return new KindResult(descriptor, List.copyOf(matches.subList(0, Math.min(total, PER_KIND_CANDIDATES))), total,
				null);
	}

	private SearchResult assemble(String query, String namespace, List<ResourceDescriptor> allKinds,
			List<ResourceDescriptor> targets, List<KindResult> results, int limit, long tookMs) {
		List<SearchHit> hits = results.stream().flatMap((r) -> r.hits().stream()).sorted(byRank()).toList();
		int total = results.stream().mapToInt(KindResult::total).sum();
		List<String> searchedIds = targets.stream().map(ResourceDescriptor::id).toList();
		List<String> unsearched = allKinds.stream()
			.filter((descriptor) -> !searchedIds.contains(descriptor.id()))
			.map(ResourceDescriptor::label)
			.distinct()
			.sorted()
			.toList();
		List<SearchResult.SkippedKind> skipped = results.stream()
			.filter((r) -> r.error() != null)
			.map((r) -> new SearchResult.SkippedKind(r.descriptor().label(), r.error()))
			.toList();
		return new SearchResult(query, namespace, hits.subList(0, Math.min(hits.size(), limit)), total, total > limit,
				targets.stream().map(ResourceDescriptor::label).toList(), unsearched, skipped, tookMs);
	}

	/**
	 * Score first, then kind, then name — so a re-run of the same query returns the same
	 * order. Ties broken by anything unstable make the armed row in a keyboard-driven
	 * dropdown move under the reader's fingers.
	 */
	private static Comparator<SearchHit> byRank() {
		return Comparator.comparingInt(SearchHit::score)
			.reversed()
			.thenComparing(SearchHit::kind)
			.thenComparing(SearchHit::name);
	}

	private int clamp(int limit) {
		if (limit <= 0) {
			return DEFAULT_LIMIT;
		}
		return Math.min(limit, MAX_LIMIT);
	}

	private String message(RuntimeException ex) {
		return (ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getSimpleName();
	}

	/**
	 * Wait for one kind. Every way this can fail produces a {@link KindResult} carrying
	 * the reason rather than nothing at all — a kind that was not searched has to reach
	 * {@link SearchResult#skippedKinds()}, or the caller reads a partial answer as a
	 * complete one.
	 */
	private KindResult await(ResourceDescriptor descriptor, Future<KindResult> future) {
		try {
			return future.get();
		}
		catch (CancellationException ex) {
			return new KindResult(descriptor, List.of(), 0, SUPERSEDED);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return new KindResult(descriptor, List.of(), 0, "not searched — the request was interrupted");
		}
		catch (ExecutionException ex) {
			log.debug("Search task failed: {}", ex.getMessage());
			Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
			String reason = (cause.getMessage() != null) ? cause.getMessage() : cause.getClass().getSimpleName();
			return new KindResult(descriptor, List.of(), 0, reason);
		}
	}

	/**
	 * One kind's contribution: its best hits, how many matched in total (which is more
	 * than {@code hits} when the kind was capped), and the reason it could not be listed.
	 */
	private record KindResult(ResourceDescriptor descriptor, List<SearchHit> hits, int total, String error) {
	}

	/**
	 * One search's tasks, so that a newer search for the same cluster can cancel them.
	 *
	 * <p>
	 * Submission and cancellation share a lock because they race: the superseding thread
	 * can arrive between two of the thirteen {@code submit} calls, and a task submitted
	 * after the cancellation swept the list would otherwise survive it. Cancellation is
	 * {@code cancel(false)} — the point is to drain the <em>queue</em>, and interrupting
	 * a list already in flight buys a fraction of one round trip in exchange for a
	 * half-consumed response.
	 */
	private static final class Attempt {

		private final ReentrantLock lock = new ReentrantLock();

		private final List<Future<?>> futures = new ArrayList<>();

		private boolean superseded;

		Future<KindResult> submit(ExecutorService executor, Callable<KindResult> task) {
			Future<KindResult> future = executor.submit(task);
			this.lock.lock();
			try {
				this.futures.add(future);
				if (this.superseded) {
					future.cancel(false);
				}
			}
			finally {
				this.lock.unlock();
			}
			return future;
		}

		void supersede() {
			this.lock.lock();
			try {
				this.superseded = true;
				this.futures.forEach((future) -> future.cancel(false));
			}
			finally {
				this.lock.unlock();
			}
		}

	}

}

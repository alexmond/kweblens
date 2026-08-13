package org.alexmond.kweblens.health;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

/**
 * Opens the {@link StatusContext} a kind's verdict needs — <b>once per request</b>,
 * before any row is looked at.
 *
 * <p>
 * This is the "per-kind provider" {@link StatusVocabulary} promises: the one place that
 * knows a Service list also has to fetch Endpoints, a claims list also has to ask the
 * metrics backend, and a ConfigMaps list also has to scan the namespace. Callers name a
 * kind and get either a context or {@link StatusContext#none()}; they never learn which
 * check answered, and adding a kind is a case here plus a {@code supports} on the check.
 *
 * <p>
 * <b>A failure to open one is not a failure of the request.</b> If the second collection
 * cannot be fetched — RBAC, a metrics backend that has gone away — the rows come back
 * with no state rather than the list coming back as an error. That degrades to exactly
 * the behaviour of a kind nobody judges: nothing is claimed, and no {@code status:} term
 * selects anything. The alternative, guessing, is the failure #336 exists to prevent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatusContexts {

	/**
	 * How long a watch's context is used before it is re-opened.
	 *
	 * <p>
	 * A list request opens one and uses it within milliseconds. A watch keeps one for as
	 * long as the page is open, and that is the case worth bounding on <i>both</i> sides:
	 * the inputs to these three verdicts — Endpoints, kubelet's volume stats, the pods of
	 * a namespace — are objects the list is <b>not</b> watching, so no event on the
	 * watched kind can tell us they changed, and a context opened once would still be
	 * describing this morning's cluster tonight. Re-opening on every event would instead
	 * turn a busy list into a second stream of list calls. Thirty seconds is the
	 * compromise, and it is a ceiling rather than a schedule: a quiet list re-opens
	 * nothing at all, because the check happens when an event arrives.
	 */
	private static final Duration WATCH_TTL = Duration.ofSeconds(30);

	private final NetworkHealthService network;

	private final StorageHealthService storage;

	private final ConfigUsageService config;

	/**
	 * The context for one list request, or {@link StatusContext#none()} when this kind
	 * needs none — or when the one it needs could not be opened.
	 */
	public StatusContext open(String clusterId, String kind, String namespace) {
		try {
			if (NetworkHealthService.supports(kind)) {
				return this.network.contextFor(clusterId, namespace);
			}
			if (StorageHealthService.supports(kind)) {
				return this.storage.contextFor(clusterId, namespace);
			}
			if (ConfigUsageService.supports(kind)) {
				return this.config.contextFor(clusterId, namespace);
			}
		}
		catch (RuntimeException ex) {
			log.debug("No status context for '{}' on cluster '{}': {}", kind, clusterId, ex.getMessage());
		}
		return StatusContext.none();
	}

	/**
	 * The context for a <b>watch</b>: the same thing, re-opened at most once every
	 * {@link #WATCH_TTL} for as long as the stream is subscribed.
	 *
	 * <p>
	 * Cheap for the kinds that need no context — those get {@link StatusContext#none()}
	 * and never open anything.
	 */
	public StatusContext refreshing(String clusterId, String kind, String namespace) {
		if (!StatusVocabulary.needsContext(kind)) {
			return StatusContext.none();
		}
		return new Refreshing(() -> open(clusterId, kind, namespace));
	}

	// Nested types last (Checkstyle InnerTypeLast).

	/** What a {@link Refreshing} context calls to get a fresh one. */
	@FunctionalInterface
	private interface Source {

		StatusContext open();

	}

	/**
	 * A context that re-opens itself once it is older than {@link #WATCH_TTL}.
	 *
	 * <p>
	 * Lazily, on the next question rather than on a timer, so a watch nobody is sending
	 * events to costs nothing. The re-open runs on whichever thread asked — the watch's
	 * delivery thread — which is why the TTL is measured in tens of seconds rather than
	 * one: it must not become a per-event fetch on a busy list.
	 */
	private final class Refreshing implements StatusContext {

		private final ReentrantLock lock = new ReentrantLock();

		private final Source source;

		private StatusContext delegate;

		private long openedAt;

		Refreshing(Source source) {
			this.source = source;
		}

		@Override
		public ObjectState state(String kind, GenericKubernetesResource object) {
			return current().state(kind, object);
		}

		private StatusContext current() {
			this.lock.lock();
			try {
				long now = System.nanoTime();
				if (this.delegate == null || (now - this.openedAt) >= WATCH_TTL.toNanos()) {
					this.delegate = this.source.open();
					this.openedAt = now;
				}
				return this.delegate;
			}
			finally {
				this.lock.unlock();
			}
		}

	}

}

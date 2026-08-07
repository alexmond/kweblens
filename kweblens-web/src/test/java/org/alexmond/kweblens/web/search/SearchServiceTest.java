package org.alexmond.kweblens.web.search;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.resource.ResourceSummary;
import org.alexmond.kweblens.web.nav.ClusterNavService;
import org.alexmond.kweblens.web.nav.NavCategory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;

/**
 * Search behaviour that is about the <em>lifecycle</em> of a query rather than its
 * ranking (which is {@code SearchRankingTest}).
 *
 * <p>
 * No cluster and no mock API server: the listing is stubbed, because what is under test
 * is what the service does with the work it queued once the caller for it is gone.
 */
class SearchServiceTest {

	private static final ResourceDescriptor DEPLOYMENTS = ResourceDescriptor.namespaced("deployments", "Deployments",
			"Deployment", "apps", "v1", "deployments");

	private static final ResourceDescriptor SERVICES = ResourceDescriptor.coreNamespaced("services", "Services",
			"Service", "services");

	private final ClusterNavService nav = mock(ClusterNavService.class);

	private final ResourceService resources = mock(ResourceService.class);

	private final SearchService service = new SearchService(this.nav, this.resources);

	private final ExecutorService callers = Executors.newFixedThreadPool(2);

	@AfterEach
	void stopCallers() {
		this.callers.shutdownNow();
	}

	private void navServes(ResourceDescriptor... kinds) {
		given(this.nav.categories(anyString())).willReturn(List.of(new NavCategory("Workloads", "bi", List.of(kinds))));
	}

	private ResourceSummary row(String name) {
		return new ResourceSummary("Deployment", "web", name, "Running", "1d");
	}

	@Test
	void aBlankQueryNeverListsAnything() {
		navServes(DEPLOYMENTS);

		SearchResult result = this.service.search("c1", "   ", null, 20);

		assertThat(result.hits()).isEmpty();
		assertThat(result.total()).isZero();
	}

	@Test
	void aKindThatCannotBeListedIsReportedRatherThanDropped() {
		navServes(DEPLOYMENTS, SERVICES);
		given(this.resources.list(anyString(), any(), any())).willThrow(new IllegalStateException("forbidden"));

		SearchResult result = this.service.search("c1", "ngin", null, 20);

		// A kind that was searched and failed must not be indistinguishable from a kind
		// that was searched and had no matches.
		assertThat(result.skippedKinds()).hasSize(2);
		assertThat(result.hits()).isEmpty();
	}

	@Test
	void aNewerSearchForTheSameClusterCancelsTheOlderOne() throws Exception {
		navServes(DEPLOYMENTS, SERVICES);
		// Two kinds per search: the third listing to start can only be the second
		// search's, which means it has already taken the slot and superseded the first.
		Gate gate = new Gate(3);

		Future<SearchResult> superseded = this.callers.submit(() -> this.service.search("c1", "ngin", null, 20));
		gate.awaitFirst();
		Future<SearchResult> current = this.callers.submit(() -> this.service.search("c1", "nginx", null, 20));
		gate.awaitAllAndRelease();

		SearchResult stale = superseded.get(20, TimeUnit.SECONDS);
		SearchResult live = current.get(20, TimeUnit.SECONDS);

		// The abandoned search says what it did not do rather than returning a short list
		// that looks complete.
		assertThat(stale.skippedKinds()).isNotEmpty()
			.allSatisfy((kind) -> assertThat(kind.reason()).isEqualTo(SearchService.SUPERSEDED));
		// The query the operator is actually waiting for is unaffected.
		assertThat(live.skippedKinds()).isEmpty();
		assertThat(live.hits()).isNotEmpty();
	}

	@Test
	void searchesForDifferentClustersDoNotCancelEachOther() throws Exception {
		navServes(DEPLOYMENTS);
		Gate gate = new Gate(2);

		Future<SearchResult> first = this.callers.submit(() -> this.service.search("c1", "ngin", null, 20));
		gate.awaitFirst();
		Future<SearchResult> second = this.callers.submit(() -> this.service.search("c2", "ngin", null, 20));
		gate.awaitAllAndRelease();

		assertThat(first.get(20, TimeUnit.SECONDS).skippedKinds()).isEmpty();
		assertThat(second.get(20, TimeUnit.SECONDS).skippedKinds()).isEmpty();
	}

	/**
	 * Holds every stubbed listing open until the expected number of them have started, so
	 * the ordering under test is established rather than slept for.
	 */
	private final class Gate {

		private final CountDownLatch first = new CountDownLatch(1);

		private final CountDownLatch all;

		private final CountDownLatch release = new CountDownLatch(1);

		Gate(int expectedListings) {
			this.all = new CountDownLatch(expectedListings);
			willAnswer((invocation) -> {
				this.first.countDown();
				this.all.countDown();
				this.release.await(20, TimeUnit.SECONDS);
				return List.of(row("nginx"));
			}).given(SearchServiceTest.this.resources).list(anyString(), any(), any());
		}

		void awaitFirst() throws InterruptedException {
			assertThat(this.first.await(20, TimeUnit.SECONDS)).as("the first search reached the listing").isTrue();
		}

		void awaitAllAndRelease() throws InterruptedException {
			assertThat(this.all.await(20, TimeUnit.SECONDS)).as("every expected listing started").isTrue();
			this.release.countDown();
		}

	}

}

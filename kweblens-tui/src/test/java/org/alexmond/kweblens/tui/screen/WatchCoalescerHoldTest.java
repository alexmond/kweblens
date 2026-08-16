package org.alexmond.kweblens.tui.screen;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.tui.screen.WatchCoalescer.Flush;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>GH#420 at the buffer.</b> A subscription's events wait for that subscription's own
 * list, and are applied the moment it lands.
 *
 * <p>
 * {@code ScreenRecoveryEventTest} proves the same rule where it matters — on the running
 * screen, through a real reconnect with the re-list parked. This is the same rule with
 * the timing taken out, so a failure says which half broke.
 */
class WatchCoalescerHoldTest {

	private final ResourceModel model = new ResourceModel();

	private final WatchCoalescer coalescer = new WatchCoalescer(this.model, this::project);

	private List<ResourceRow> project(List<GenericKubernetesResource> objects) {
		List<ResourceRow> rows = new ArrayList<>(objects.size());
		for (GenericKubernetesResource object : objects) {
			rows.add(ResourceRow.of(object, "Running", Instant.parse("2026-08-15T00:00:00Z")));
		}
		return rows;
	}

	private static GenericKubernetesResource pod(String name) {
		return new GenericKubernetesResourceBuilder().withApiVersion("v1")
			.withKind("Pod")
			.withNewMetadata()
			.withNamespace("ns")
			.withName(name)
			.endMetadata()
			.build();
	}

	@Test
	void aSubscriptionsEventsAreKeptButNotAppliedUntilItsOwnListIsInstalled() {
		this.coalescer.rebase(1);
		this.coalescer.sink(1).accept("ADDED", pod("a"));

		// Kept — losing it is the defect — but not applied, because the list this
		// subscription is taking is about to replace every row in the model.
		assertThat(this.coalescer.buffered()).as("kept").isEqualTo(1);
		assertThat(this.coalescer.held()).as("and held").isEqualTo(1);
		assertThat(this.coalescer.flush()).isEqualTo(Flush.IDLE);
		assertThat(this.model.rows()).isEmpty();

		// The list lands. What it does not know about arrives on top of it, not under.
		this.model.replaceAll(List.of(new ResourceRow("ns/listed", "ns", "listed", "Running", "1d")));
		this.coalescer.installed(1);

		assertThat(this.coalescer.held()).isZero();
		assertThat(this.coalescer.flush()).isEqualTo(Flush.APPLIED);
		assertThat(this.model.rows()).extracting(ResourceRow::name).containsExactly("a", "listed");
	}

	@Test
	void aListBelongingToASupersededSubscriptionDoesNotReleaseTheReplacementsHold() {
		this.coalescer.rebase(1);
		this.coalescer.rebase(2);
		this.coalescer.sink(2).accept("ADDED", pod("a"));

		// A reconnect whose rows were produced by a subscription that has since been
		// replaced. Letting it speak for the replacement would release the hold one list
		// too early — the same confusion as an arrival-stamped event, one level up.
		this.coalescer.installed(1);

		assertThat(this.coalescer.held()).isEqualTo(1);
		assertThat(this.coalescer.flush()).isEqualTo(Flush.IDLE);

		// The control: the replacement's own list releases it.
		this.coalescer.installed(2);
		assertThat(this.coalescer.flush()).isEqualTo(Flush.APPLIED);
		assertThat(this.model.rows()).extracting(ResourceRow::name).containsExactly("a");
	}

	@Test
	void aBufferNobodyHasPointedAtASubscriptionHoldsNothingBack() {
		// The control for the hold itself. A buffer that held by default would look
		// exactly like a working one to every test that flushes what it offers — right up
		// until the screen quietly stopped moving. This is the state the in-package
		// coalescing tests run in, and it is asserted rather than assumed.
		this.coalescer.offer("ADDED", pod("a"));

		assertThat(this.coalescer.held()).isZero();
		assertThat(this.coalescer.buffered()).isEqualTo(1);
		assertThat(this.coalescer.flush()).isEqualTo(Flush.APPLIED);
		assertThat(this.model.rows()).extracting(ResourceRow::name).containsExactly("a");
	}

}

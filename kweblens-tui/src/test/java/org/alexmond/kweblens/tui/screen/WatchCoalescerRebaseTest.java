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
 * <b>GH#417 at the buffer.</b> The buffer belongs to one subscription at a time, and
 * there are two ways an older one can reach it: what it had already left there, and what
 * it delivers after it has been replaced. Both are answered by the generation, and both
 * are asserted here.
 *
 * <p>
 * {@code ScreenStaleEventTest} proves the same rule where it matters — on the running
 * screen, through a real reconnect. This is the same rule with the timing taken out, so a
 * failure says which half broke.
 */
class WatchCoalescerRebaseTest {

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
	void aRebaseThrowsAwayWhatTheSubscriptionItReplacesLeftBuffered() {
		this.coalescer.rebase(1);
		this.coalescer.sink(1).accept("ADDED", pod("a"));
		assertThat(this.coalescer.buffered()).isEqualTo(1);

		// A reconnect rebases before it re-lists, so the buffered change is discarded by
		// something that is about to fetch it again anyway.
		this.coalescer.rebase(2);

		assertThat(this.coalescer.buffered()).isZero();
		assertThat(this.coalescer.flush()).isEqualTo(Flush.IDLE);
		assertThat(this.model.rows()).isEmpty();
	}

	@Test
	void anEventFromASupersededSubscriptionIsRefusedAndItsReplacementIsNot() {
		this.coalescer.rebase(1);
		this.coalescer.sink(1).accept("ADDED", pod("a"));
		assertThat(this.coalescer.flush()).isEqualTo(Flush.APPLIED);
		this.coalescer.rebase(2);

		// The watch that has been replaced, still talking.
		this.coalescer.sink(1).accept("DELETED", pod("a"));

		assertThat(this.coalescer.buffered()).isZero();
		assertThat(this.coalescer.stale()).isEqualTo(1);
		assertThat(this.coalescer.received()).as("a refused event was never absorbed").isEqualTo(1);
		assertThat(this.coalescer.flush()).isEqualTo(Flush.IDLE);
		assertThat(this.model.rows()).extracting(ResourceRow::name).containsExactly("a");

		// The control: the same event from the subscription that is current is applied.
		this.coalescer.sink(2).accept("DELETED", pod("a"));
		assertThat(this.coalescer.flush()).isEqualTo(Flush.APPLIED);
		assertThat(this.model.rows()).isEmpty();
		assertThat(this.coalescer.stale()).isEqualTo(1);
	}

}

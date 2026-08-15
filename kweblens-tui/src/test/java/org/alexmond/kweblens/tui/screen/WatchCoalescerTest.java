package org.alexmond.kweblens.tui.screen;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.tui.screen.WatchCoalescer.Flush;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The coalescing rule, without a terminal: <b>N events in one period become one model
 * update</b>, and an add cancelled by a delete inside that period becomes none.
 *
 * <p>
 * Mirrors {@code useResourceData.test.ts} assertion for assertion — 157 events, then one
 * more — because the two surfaces are governed by one sentence and should therefore fail
 * for one reason. What a "period" is differs (a tick, not an animation frame) and nothing
 * else does.
 */
class WatchCoalescerTest {

	private static final int BURST = 157;

	private final ResourceModel model = new ResourceModel();

	private final AtomicInteger projections = new AtomicInteger();

	private final AtomicInteger projectedObjects = new AtomicInteger();

	private final WatchCoalescer coalescer = new WatchCoalescer(this.model, this::project);

	/** A second coalescer that has to refer to itself from inside its own projection. */
	private WatchCoalescer nested;

	/**
	 * Stands in for {@code ObjectStates.forList}, and counts calls — because "one status
	 * context per batch, never per row" is the property that makes a 2 000-row flush
	 * affordable, and it is invisible unless something counts.
	 */
	private List<ResourceRow> project(List<GenericKubernetesResource> objects) {
		this.projections.incrementAndGet();
		this.projectedObjects.addAndGet(objects.size());
		List<ResourceRow> rows = new ArrayList<>(objects.size());
		for (GenericKubernetesResource object : objects) {
			rows.add(ResourceRow.of(object, label(object), Instant.parse("2026-08-15T00:00:00Z")));
		}
		return rows;
	}

	private static String label(GenericKubernetesResource object) {
		Object value = (object.getAdditionalProperties() != null) ? object.getAdditionalProperties().get("state")
				: null;
		return (value != null) ? value.toString() : null;
	}

	private static GenericKubernetesResource pod(String name, String state) {
		GenericKubernetesResource object = new GenericKubernetesResourceBuilder().withApiVersion("v1")
			.withKind("Pod")
			.withNewMetadata()
			.withNamespace("ns")
			.withName(name)
			.endMetadata()
			.build();
		if (state != null) {
			object.setAdditionalProperty("state", state);
		}
		return object;
	}

	@Test
	void aBurstOfEventsBecomesOneModelUpdate() {
		for (int i = 0; i < BURST; i++) {
			this.coalescer.offer("ADDED", pod("rs-" + i, "Running"));
		}

		assertThat(this.coalescer.buffered()).isEqualTo(BURST);
		assertThat(this.model.size()).as("nothing is applied until the tick").isZero();
		assertThat(this.projections).hasValue(0);

		assertThat(this.coalescer.flush()).isEqualTo(Flush.APPLIED);
		assertThat(this.model.size()).isEqualTo(BURST);
		assertThat(this.projections).as("one status context for the whole burst, not 157").hasValue(1);
		assertThat(this.projectedObjects).hasValue(BURST);

		// One more event on the next period is its own single update.
		this.coalescer.offer("ADDED", pod("rs-999", "Running"));
		assertThat(this.coalescer.flush()).isEqualTo(Flush.APPLIED);
		assertThat(this.model.size()).isEqualTo(BURST + 1);
		assertThat(this.projections).hasValue(2);
	}

	@Test
	void aTickWithNothingBufferedOwesNoRepaint() {
		assertThat(this.coalescer.flush()).isEqualTo(Flush.IDLE);
		assertThat(this.coalescer.flush().repaints()).isFalse();
		assertThat(this.projections).as("an empty batch opens no status context at all").hasValue(0);
	}

	@Test
	void addModifyAndDeleteInsideOnePeriodCollapse() {
		this.coalescer.offer("ADDED", pod("a", "Pending"));
		this.coalescer.offer("ADDED", pod("b", "Pending"));
		this.coalescer.offer("MODIFIED", pod("a", "Running"));
		this.coalescer.offer("DELETED", pod("b", "Pending"));

		assertThat(this.coalescer.buffered()).as("four events, two keys").isEqualTo(2);
		assertThat(this.coalescer.flush()).isEqualTo(Flush.APPLIED);

		assertThat(this.model.rows()).singleElement()
			.satisfies((row) -> assertThat(row.name()).isEqualTo("a"))
			.satisfies((row) -> assertThat(row.state()).isEqualTo("Running"));
		assertThat(this.projectedObjects).as("b was never projected — it was added and deleted before a frame")
			.hasValue(1);
	}

	@Test
	void aDeleteRemovesARowThatWasAlreadyDrawn() {
		this.coalescer.offer("ADDED", pod("a", "Running"));
		this.coalescer.flush();

		this.coalescer.offer("DELETED", pod("a", "Running"));
		assertThat(this.coalescer.flush()).isEqualTo(Flush.APPLIED);
		assertThat(this.model.rows()).isEmpty();
	}

	@Test
	void aFlushArrivingWhileOneIsRunningIsDroppedAndItsEventsSurvive() {
		AtomicReference<Flush> reentrant = new AtomicReference<>();
		ResourceModel target = new ResourceModel();
		this.nested = new WatchCoalescer(target, (objects) -> {
			// Inside the flush: a second tick arrives and offers more work.
			this.nested.offer("ADDED", pod("late", "Running"));
			reentrant.set(this.nested.flush());
			return List.of(ResourceRow.of(objects.get(0), "Running", Instant.parse("2026-08-15T00:00:00Z")));
		});
		this.nested.offer("ADDED", pod("first", "Running"));

		assertThat(this.nested.flush()).isEqualTo(Flush.APPLIED);

		assertThat(reentrant).as("a flush inside a flush is refused, not interleaved").hasValue(Flush.DROPPED);
		assertThat(this.nested.dropped()).isEqualTo(1);
		assertThat(this.nested.buffered()).as("the refused tick's event is still buffered — dropped, not lost")
			.isEqualTo(1);
		assertThat(target.rows()).singleElement().satisfies((row) -> assertThat(row.name()).isEqualTo("first"));

		// The next tick applies what the refused one carried.
		assertThat(this.nested.flush()).isEqualTo(Flush.APPLIED);
		assertThat(target.rows()).hasSize(2);
	}

	@Test
	void anEventWithNoObjectIsIgnoredRatherThanCounted() {
		this.coalescer.offer("ADDED", null);

		assertThat(this.coalescer.received()).isZero();
		assertThat(this.coalescer.flush()).isEqualTo(Flush.IDLE);
	}

	@Test
	void countersReportWhatHappened() {
		this.coalescer.offer("ADDED", pod("a", null));
		this.coalescer.offer("MODIFIED", pod("a", null));
		this.coalescer.flush();

		assertThat(this.coalescer.received()).isEqualTo(2);
		assertThat(this.coalescer.flushes()).isEqualTo(1);
		assertThat(this.coalescer.dropped()).isZero();
	}

}

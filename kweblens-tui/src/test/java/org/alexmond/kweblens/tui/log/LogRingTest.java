package org.alexmond.kweblens.tui.log;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>The gate for GH#369's done-when 4: a pod logging indefinitely does not grow the
 * TUI's heap.</b>
 *
 * <p>
 * The bound is the whole feature here, so the assertions are about what happens
 * <em>past</em> it: the size stops, the oldest lines are the ones gone, and the number
 * that were dropped is available rather than silent. A ring that grew would pass a test
 * that only appended a few lines and checked they were there.
 */
class LogRingTest {

	@Test
	void aRingStopsGrowingAtItsCapacityAndKeepsTheNewestLines() {
		LogRing ring = new LogRing(5);

		for (int i = 0; i < 100; i++) {
			ring.add("line-" + i);
		}

		assertThat(ring.size()).as("a hundred lines into a ring of five").isEqualTo(5);
		assertThat(ring.slice(0, 5)).containsExactly("line-95", "line-96", "line-97", "line-98", "line-99");
		assertThat(ring.discarded()).as("and the count of what was lost is a fact the pane can report").isEqualTo(95);
	}

	/**
	 * The default is 5 000 lines, k9s's, and it is asserted rather than assumed: a
	 * constant that drifts to a bigger number is exactly how a bound stops being one.
	 */
	@Test
	void theDefaultBoundIsFiveThousandLines() {
		LogRing ring = new LogRing();

		for (int i = 0; i < 20_000; i++) {
			ring.add("line-" + i);
		}

		assertThat(ring.capacity()).isEqualTo(LogRing.DEFAULT_CAPACITY).isEqualTo(5_000);
		assertThat(ring.size()).isEqualTo(5_000);
		assertThat(ring.discarded()).isEqualTo(15_000);
		assertThat(ring.get(0)).as("index 0 is the oldest line STILL HELD, not the first one ever emitted")
			.isEqualTo("line-15000");
	}

	@Test
	void indexingIsFromTheOldestLineHeldAndWrapsCorrectly() {
		LogRing ring = new LogRing(3);
		ring.addAll(List.of("a", "b", "c", "d"));

		assertThat(ring.get(0)).isEqualTo("b");
		assertThat(ring.get(2)).isEqualTo("d");
		assertThatThrownBy(() -> ring.get(3)).isInstanceOf(IndexOutOfBoundsException.class)
			.hasMessageContaining("3 held");
	}

	@Test
	void aSliceIsClampedToWhatIsHeldRatherThanThrowing() {
		LogRing ring = new LogRing(10);
		ring.addAll(List.of("a", "b"));

		// The renderer asks for a viewport's worth every frame and the document is
		// usually
		// shorter than the viewport. That is not an error, it is the opening frame.
		assertThat(ring.slice(0, 44)).containsExactly("a", "b");
		assertThat(ring.slice(5, 44)).isEmpty();
		assertThat(ring.slice(-3, 1)).containsExactly("a");
	}

	@Test
	void anEmptyRingHasNothingToShowAndSaysSoRatherThanFailing() {
		LogRing ring = new LogRing(4);

		assertThat(ring.size()).isZero();
		assertThat(ring.slice(0, 4)).isEmpty();
		assertThat(ring.discarded()).isZero();
	}

	/**
	 * A capacity of zero or less is a caller's mistake, not a ring that holds nothing:
	 * one line is the smallest useful answer and it keeps every index calculation valid.
	 */
	@Test
	void aNonsenseCapacityBecomesOneRatherThanZero() {
		LogRing ring = new LogRing(0);
		ring.addAll(List.of("a", "b"));

		assertThat(ring.capacity()).isEqualTo(1);
		assertThat(ring.slice(0, 1)).containsExactly("b");
	}

}

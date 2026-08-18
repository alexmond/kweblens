package org.alexmond.kweblens.tui.log;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The buffer half of GH#369's done-when 2 and 4.</b>
 *
 * <p>
 * A burst arrives, and nothing happens until somebody drains it — no repaint, no
 * projection, no work posted anywhere. That is what makes "at most one repaint per flush
 * period" true at the screen: there is nothing else for a line to trigger.
 *
 * <p>
 * The second half is that the buffer itself is bounded. Bounding only the document would
 * leave the heap unbounded between two ticks, which is exactly the input the bound exists
 * for.
 */
class LogBufferTest {

	/**
	 * 157, the same number the list's coalescing gate uses — the real ReplicaSet count
	 * that once froze a tab. One drain, one batch, nothing in between.
	 */
	@Test
	void aBurstIsOneDrainAndNotOneNotificationPerLine() {
		LogBuffer buffer = new LogBuffer();

		for (int i = 0; i < 157; i++) {
			buffer.offer("line-" + i);
		}

		assertThat(buffer.buffered()).as("all of it is waiting; none of it has been applied").isEqualTo(157);
		List<String> batch = buffer.drain();
		assertThat(batch).hasSize(157).startsWith("line-0").endsWith("line-156");
		assertThat(buffer.buffered()).isZero();
		assertThat(buffer.received()).isEqualTo(157);
		assertThat(buffer.dropped()).isZero();
	}

	@Test
	void aDrainWithNothingWaitingCostsADequeCheckAndReturnsNothing() {
		LogBuffer buffer = new LogBuffer();

		assertThat(buffer.drain()).isEmpty();
		assertThat(buffer.received()).isZero();
	}

	/**
	 * Done-when 4, at the seam the document's bound does not cover: a pod that outruns
	 * the tick. The oldest pending line goes, and it is counted — a line thrown away
	 * before anybody saw it is a fact about the run, and {@code LogModel.status()} says
	 * so.
	 */
	@Test
	void aPodThatOutrunsTheTickDropsTheOldestPendingLineAndCountsIt() {
		LogBuffer buffer = new LogBuffer(10);

		for (int i = 0; i < 1_000; i++) {
			buffer.offer("line-" + i);
		}

		assertThat(buffer.buffered()).as("bounded between ticks, not only in the document").isEqualTo(10);
		assertThat(buffer.dropped()).isEqualTo(990);
		assertThat(buffer.received()).as("received counts what arrived, not what survived").isEqualTo(1_000);
		assertThat(buffer.drain()).startsWith("line-990").endsWith("line-999");
	}

	@Test
	void aNullLineIsNotABlankLine() {
		LogBuffer buffer = new LogBuffer();

		buffer.offer(null);
		buffer.offer("");

		assertThat(buffer.drain()).containsExactly("");
		assertThat(buffer.received()).isEqualTo(1);
	}

	/**
	 * The reader thread offers while the render thread drains, and nothing is lost or
	 * duplicated. Not a timing assertion: the totals are what is checked, over enough
	 * lines that an unsynchronised deque would have thrown or lost some.
	 */
	@Test
	void offeringWhileDrainingLosesNothing() throws Exception {
		LogBuffer buffer = new LogBuffer(100_000);
		int lines = 20_000;
		Thread writer = new Thread(() -> {
			for (int i = 0; i < lines; i++) {
				buffer.offer("line-" + i);
			}
		});

		int drained = 0;
		writer.start();
		while (writer.isAlive() || buffer.buffered() > 0) {
			drained += buffer.drain().size();
		}
		writer.join();
		drained += buffer.drain().size();

		assertThat(drained).as("every line offered came out of exactly one drain").isEqualTo(lines);
		assertThat(buffer.received()).isEqualTo(lines);
		assertThat(buffer.dropped()).isZero();
	}

}

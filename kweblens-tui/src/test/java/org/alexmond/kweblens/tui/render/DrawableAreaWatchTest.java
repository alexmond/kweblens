package org.alexmond.kweblens.tui.render;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A screen that stops having room to draw says so — in the log, because by then it is the
 * only place a message can be seen (GH#442).
 */
class DrawableAreaWatchTest {

	private final AtomicInteger delegated = new AtomicInteger();

	private final DrawableAreaWatch watch = new DrawableAreaWatch((frame) -> this.delegated.incrementAndGet());

	private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

	private Logger logger;

	@BeforeEach
	void captureTheLog() {
		this.logger = (Logger) LoggerFactory.getLogger(DrawableAreaWatch.class);
		this.lines.start();
		this.logger.addAppender(this.lines);
	}

	@AfterEach
	void releaseTheLog() {
		this.logger.detachAppender(this.lines);
		this.lines.stop();
	}

	@Test
	void aScreenWithRoomToDrawSaysNothingAtAll() {
		render(132, 44);
		render(132, 44);

		assertThat(this.delegated).as("the wrapper draws the screen, it does not replace it").hasValue(2);
		assertThat(messages()).as("an empty kweblens-tui.log is what a healthy run leaves").isEmpty();
	}

	@Test
	void aScreenThatLosesItsAreaNamesTheMeasurementAndSaysItIsNotTheCluster() {
		render(132, 44);

		render(0, 0);

		assertThat(messages()).hasSize(1);
		assertThat(messages().get(0)).contains("0×0 (columns × rows)")
			.contains("nowhere to draw")
			.contains("Nothing is wrong with the cluster");
		assertThat(this.lines.list).allSatisfy(
				(event) -> assertThat(event.getLevel()).as("root is WARN, so anything below it lands nowhere at all")
					.isEqualTo(Level.WARN));
		assertThat(this.watch.blank()).isTrue();
		assertThat(this.delegated).as("the screen is still drawn — a blank frame is not a reason to stop").hasValue(2);
	}

	@Test
	void itSaysItOnceAndThenSaysWhenItComesBack() {
		render(132, 44);
		render(0, 0);
		render(0, 0);
		render(0, 0);

		assertThat(messages()).as("one line per transition, not one per frame").hasSize(1);

		render(132, 44);

		assertThat(messages()).hasSize(2);
		assertThat(messages().get(1)).contains("132×44 (columns × rows)").contains("again");
		assertThat(this.watch.blank()).isFalse();
	}

	@Test
	void columnsWithNoRowsIsAlsoNowhereToDraw() {
		render(132, 0);

		assertThat(messages()).hasSize(1);
		assertThat(messages().get(0)).as("a terminal 132 columns wide and 0 rows tall has as much room as 0×0")
			.contains("132×0 (columns × rows)");
	}

	private void render(int width, int height) {
		this.watch.render(Frame.forTesting(Buffer.empty(Rect.of(width, height))));
	}

	private List<String> messages() {
		return this.lines.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

}

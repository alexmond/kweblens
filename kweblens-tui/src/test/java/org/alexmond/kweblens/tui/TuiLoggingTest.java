package org.alexmond.kweblens.tui;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.FileAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>A log line written to stdout is written onto the screen this module is drawing.</b>
 *
 * <p>
 * This is the first of the two halves described on
 * {@code org.alexmond.kweblens.tui.render.TerminalOutputGuard}: logback, silenced at its
 * source. {@code logback.xml} declares a file appender and no console appender at any
 * level, and the file is named {@code logback.xml} rather than {@code logback-spring.xml}
 * precisely so that it is in force from the first log call — before Spring, before Boot's
 * {@code LoggingSystem}, before anything could have written a line.
 *
 * <p>
 * The GH#361 spike's first "TamboUI rendering bug" was this, so the assertion is not
 * theoretical.
 */
class TuiLoggingTest {

	@Test
	void theProbeCanRecogniseAConsoleAppenderWhenItSeesOne() {
		// The positive control. Without it, "no console appender was found" would pass
		// just as happily on a walker that finds nothing at all.
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		ConsoleAppender<?> console = new ConsoleAppender<>();
		console.setContext(context);
		console.setName("a-console-appender");

		assertThat(isConsole(console)).isTrue();
		assertThat(isConsole(new FileAppender<>())).isFalse();
	}

	@Test
	void nothingInThisModuleLogsToTheScreen() {
		List<Appender<?>> appenders = rootAppenders();

		assertThat(appenders).as("logging must go somewhere, or this test passes by logging nothing").isNotEmpty();
		assertThat(appenders).filteredOn(TuiLoggingTest::isConsole)
			.as("a ConsoleAppender writes onto the frame the renderer believes it owns")
			.isEmpty();
	}

	@Test
	void logbackWritesToAFileInstead() {
		assertThat(rootAppenders()).anySatisfy((appender) -> assertThat(appender).isInstanceOf(FileAppender.class));
	}

	private static List<Appender<?>> rootAppenders() {
		Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		List<Appender<?>> appenders = new ArrayList<>();
		for (Iterator<Appender<ch.qos.logback.classic.spi.ILoggingEvent>> it = root.iteratorForAppenders(); it
			.hasNext();) {
			appenders.add(it.next());
		}
		return appenders;
	}

	private static boolean isConsole(Appender<?> appender) {
		return appender instanceof ConsoleAppender;
	}

}

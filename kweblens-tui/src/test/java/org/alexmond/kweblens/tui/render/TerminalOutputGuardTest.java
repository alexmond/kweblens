package org.alexmond.kweblens.tui.render;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The second half of "nothing but the renderer writes to the screen".
 *
 * <p>
 * logback is handled at its source by {@code logback.xml}. This covers everything that
 * never goes through logback — a library's {@code printStackTrace}, the default handler
 * printing an uncaught exception from a watch thread — every one of which would otherwise
 * land in the middle of a frame.
 */
class TerminalOutputGuardTest {

	@Test
	void bothStreamsGoToTheSinkWhileInstalledAndComeBackAfterwards() {
		PrintStream realOut = System.out;
		PrintStream realErr = System.err;
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		ByteArrayOutputStream screen = new ByteArrayOutputStream();
		System.setOut(new PrintStream(screen, true, StandardCharsets.UTF_8));
		System.setErr(new PrintStream(screen, true, StandardCharsets.UTF_8));
		try {
			try (TerminalOutputGuard guard = TerminalOutputGuard.open(sink).install()) {
				System.out.println("a library logging to stdout");
				System.err.println("a stack trace from a watch thread");
				new IllegalStateException("boom").printStackTrace();
				assertThat(guard.stream()).isNotNull();
			}
			System.out.println("after the screen is down");

			assertThat(sink.toString(StandardCharsets.UTF_8)).contains("a library logging to stdout")
				.contains("a stack trace from a watch thread")
				.contains("boom");
			assertThat(screen.toString(StandardCharsets.UTF_8))
				.as("not one byte of that reached the screen while the guard was installed")
				.doesNotContain("a library logging to stdout")
				.doesNotContain("boom")
				.contains("after the screen is down");
		}
		finally {
			System.setOut(realOut);
			System.setErr(realErr);
		}
	}

	@Test
	void openingDoesNotRedirectUntilInstallIsCalled() {
		PrintStream before = System.out;
		try (TerminalOutputGuard guard = TerminalOutputGuard.open(new ByteArrayOutputStream())) {
			assertThat(System.out).as("open() must be safe to call before the terminal exists").isSameAs(before);
			guard.install();
			assertThat(System.out).isNotSameAs(before);
		}
		assertThat(System.out).isSameAs(before);
	}

	@Test
	void theLogFileIsTheOneLogbackWritesTo() {
		assertThat(TerminalOutputGuard.logFile()).hasFileName("kweblens-tui.log");
		assertThat(TerminalOutputGuard.LOG_FILE_ENV).isEqualTo("KWEBLENS_TUI_LOG_FILE");
	}

	@Test
	void closingTwiceRestoresOnceAndDoesNotSwapTheStreamsBack() {
		PrintStream before = System.out;
		TerminalOutputGuard guard = TerminalOutputGuard.open(new ByteArrayOutputStream()).install();
		guard.close();
		guard.close();

		assertThat(System.out).isSameAs(before);
	}

	@Test
	void aLogFileThatCannotBeOpenedDiscardsRatherThanFallingBackToTheScreen() {
		PrintStream before = System.out;
		String previous = System.getProperty("java.io.tmpdir");
		System.setProperty("java.io.tmpdir", "/proc/self/cannot/create/this");
		try (TerminalOutputGuard guard = TerminalOutputGuard.open().install()) {
			System.out.println("this must not reach the screen");
			assertThat(System.out).isNotSameAs(before);
		}
		finally {
			System.setProperty("java.io.tmpdir", previous);
		}
		assertThat(System.out).isSameAs(before);
	}

}

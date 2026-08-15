package org.alexmond.kweblens.tui.render;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Keeps everything that is not the screen off the screen, for as long as the screen is
 * up.
 *
 * <h2>Why this is not paranoia</h2>
 *
 * A TUI's stdout <b>is</b> its display. A diff-drawing renderer believes it knows what is
 * in every cell, so one stray line does not scroll harmlessly past — it desynchronises
 * the whole frame, and what you see afterwards looks like a rendering bug in the widget
 * library. The GH#361 spike lost an hour to exactly that: its first "TamboUI rendering
 * bug" was logback writing to the pty.
 *
 * <h2>Two halves, and one alone is not enough</h2>
 *
 * <ol>
 * <li><b>logback</b> is silenced at its source by this module's {@code logback.xml},
 * which declares a file appender and no console appender at all. It must be a plain
 * {@code logback.xml} rather than {@code logback-spring.xml}: logback configures itself
 * on the first log call, which happens well before Spring exists, and with only a
 * {@code -spring} file to find it would fall back to its own built-in default — a
 * {@code ConsoleAppender} at DEBUG, onto the screen. Spring Boot 4 applies its logging
 * defaults programmatically and ships no {@code defaults.xml} to {@code <include>}, so
 * the file is self-contained.</li>
 * <li><b>Everything else</b> is caught here. A {@code printStackTrace} inside a client
 * library, a JLine warning, the default handler printing an uncaught exception from a
 * watch thread — none of those go through logback and every one of them lands on the pty.
 * This swaps {@link System#out} and {@link System#err} for a stream that appends to the
 * same log file, and restores the originals on {@link #close()}.</li>
 * </ol>
 *
 * <h2>Open early, install late</h2>
 *
 * {@link #open()} only opens the file — it is safe to call before the terminal exists,
 * and {@link #stream()} is what {@code TuiConfig.errorOutput} should be pointed at (its
 * default captures {@code System.err} at construction time, which is the pty).
 * {@link #install()} performs the swap and must run <b>after</b> the terminal is built:
 * JLine's system terminal captures its output stream when it is constructed, so a
 * redirect installed first might take the screen away from it.
 */
public final class TerminalOutputGuard implements AutoCloseable {

	/** Same env var {@code logback.xml} reads, so both halves write to one file. */
	public static final String LOG_FILE_ENV = "KWEBLENS_TUI_LOG_FILE";

	private static final String DEFAULT_NAME = "kweblens-tui.log";

	private final PrintStream redirect;

	private final Path file;

	private PrintStream originalOut;

	private PrintStream originalErr;

	private TerminalOutputGuard(Path file, OutputStream sink) {
		this.file = file;
		this.redirect = new PrintStream(sink, true, StandardCharsets.UTF_8);
	}

	/**
	 * Open the log sink without touching {@code System.out} yet. If the file cannot be
	 * opened the stream is discarded rather than left pointing at the screen: a lost log
	 * line is a nuisance, a corrupted frame is a bug report about the wrong thing.
	 */
	public static TerminalOutputGuard open() {
		Path target = logFile();
		try {
			Path parent = target.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			return new TerminalOutputGuard(target,
					Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
		}
		catch (IOException ex) {
			return new TerminalOutputGuard(target, OutputStream.nullOutputStream());
		}
	}

	/** Open against {@code sink} instead of the log file — for tests. */
	public static TerminalOutputGuard open(OutputStream sink) {
		return new TerminalOutputGuard(logFile(), sink);
	}

	/** Where this build writes its log, resolved exactly as {@code logback.xml} does. */
	public static Path logFile() {
		String configured = System.getenv(LOG_FILE_ENV);
		if (configured != null && !configured.isBlank()) {
			return Paths.get(configured);
		}
		return Paths.get(System.getProperty("java.io.tmpdir", "."), DEFAULT_NAME);
	}

	/** Take {@code System.out} and {@code System.err} for the duration. */
	public TerminalOutputGuard install() {
		if (this.originalOut == null) {
			this.originalOut = System.out;
			this.originalErr = System.err;
			System.setOut(this.redirect);
			System.setErr(this.redirect);
		}
		return this;
	}

	/** The sink, for anything that has to be told where to write instead of guessing. */
	public PrintStream stream() {
		return this.redirect;
	}

	/** The file this guard writes to, for a line telling the operator where to look. */
	public Path file() {
		return this.file;
	}

	@Override
	public void close() {
		if (this.originalOut != null) {
			System.setOut(this.originalOut);
			System.setErr(this.originalErr);
			this.originalOut = null;
			this.originalErr = null;
		}
		this.redirect.flush();
	}

}

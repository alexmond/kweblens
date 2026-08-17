package org.alexmond.kweblens.tui.render;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.EventHandler;
import dev.tamboui.tui.Renderer;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.tui.screen.Eventually;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What TamboUI actually does with an exception that leaves an {@code EventHandler} —
 * measured here rather than reasoned about, because GH#434's decision turns on it.
 *
 * <h2>The answer</h2>
 *
 * The runner catches it ({@code catch (Throwable t) { handleRenderError(t); continue;
 * }}), so the process does not die and the loop thread stays alive — but the configured
 * {@code RenderErrorHandler} decides what happens next, and this build configures none,
 * so it is TamboUI's default: {@code RenderErrorHandlers.displayAndQuit()}, which returns
 * {@code DISPLAY_AND_QUIT}. That sets {@code inErrorState}, replaces the whole screen
 * with a scrollable stack trace, and <b>never clears it</b> — from then on every event
 * goes to {@code handleErrorModeEvents}, which only scrolls and quits. The handler is
 * never called again and the application's renderer never draws again.
 *
 * <p>
 * So "the app dies" and "it prints and continues" are both wrong, and the practical
 * consequence is the strongest of the three: <b>one refused navigation ends the
 * session.</b> That is why {@code ScreenSession.switchTo} may not let one out — see
 * {@link ScreenSwitchFailureTest}, whose every {@code tickAndSettle} would hang against
 * this behaviour.
 *
 * <h2>Why it is a test and not a comment</h2>
 *
 * It is a claim about a dependency, and a dependency can be bumped. The three things
 * asserted here are the three the fix is designed around, and each would change silently:
 * the loop survives, the handler stops being called, and <b>nothing is written to
 * {@code errorOutput}</b> — {@code displayAndQuit} prints nowhere, so pointing that
 * stream at the log file (which {@code TuiScreen} does, for good reasons of its own) does
 * not make an escaped exception visible in the log either.
 */
class TuiRunnerEscapedExceptionTest {

	private static final Duration POLL = Duration.ofMillis(5);

	@Test
	void anExceptionOutOfTheHandlerReplacesTheScreenForeverInsteadOfKillingTheLoop() throws Exception {
		AtomicInteger entered = new AtomicInteger();
		AtomicInteger rendered = new AtomicInteger();
		ByteArrayOutputStream errors = new ByteArrayOutputStream();

		EventHandler handler = (Event event, TuiRunner runner) -> {
			if (!(event instanceof KeyEvent)) {
				return false;
			}
			if (entered.incrementAndGet() == 2) {
				throw new IllegalStateException("the second key press could not be served");
			}
			// Never ask for a repaint, so the renderer count below can only move when
			// TamboUI itself decides to draw.
			return false;
		};
		Renderer renderer = (Frame frame) -> rendered.incrementAndGet();

		TuiConfig config = TuiConfig.builder()
			.backend(new FakeBackend(ScreenHarness.WIDTH, ScreenHarness.HEIGHT))
			.noTick()
			.rawMode(false)
			.alternateScreen(false)
			.hideCursor(false)
			.shutdownHook(false)
			.pollTimeout(POLL)
			.errorOutput(new PrintStream(errors, true, StandardCharsets.UTF_8))
			.build();

		try (TuiRunner runner = TuiRunner.create(config)) {
			Thread loop = new Thread(() -> {
				try {
					runner.run(handler, renderer);
				}
				catch (Exception ex) {
					throw new IllegalStateException("the loop threw out of run()", ex);
				}
			}, "escaped-exception-loop");
			loop.setDaemon(true);
			loop.start();
			Eventually.await(() -> rendered.get() >= 1, "the initial draw");

			// The positive control: this key reaches the handler and comes back.
			runner.dispatch(KeyEvent.ofChar('a'));
			Eventually.await(() -> entered.get() == 1, "the first key press to be handled");
			int drawnBeforeTheFailure = rendered.get();

			// The second one throws out of handle(). Then escape — which this handler
			// would simply count, and which the runner's error mode quits on.
			runner.dispatch(KeyEvent.ofChar('b'));
			runner.dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
			Eventually.await(() -> !loop.isAlive(), "the runner to quit on a key the handler never saw");

			assertThat(entered.get())
				.as("the escape was consumed by the runner's error mode, not by the handler — which is over")
				.isEqualTo(2);
			assertThat(rendered.get()).as("and the application's renderer never drew again")
				.isEqualTo(drawnBeforeTheFailure);
			assertThat(errors.toString(StandardCharsets.UTF_8))
				.as("nothing reaches errorOutput either: the default handler draws, it does not print")
				.isEmpty();
		}
	}

}

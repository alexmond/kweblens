package org.alexmond.kweblens.tui.render;

import java.io.PrintWriter;
import java.util.function.Consumer;

import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.tui.data.ClusterDataSource;
import org.alexmond.kweblens.tui.data.ResourceQuery;
import org.alexmond.kweblens.tui.kind.KindCatalog;
import org.alexmond.kweblens.tui.screen.TickRate;

/**
 * Starts the screen and takes it down again — the lifecycle, and nothing about what is
 * drawn.
 *
 * <h2>The order these four things happen in is the whole class</h2>
 *
 * <ol>
 * <li><b>Open the log sink.</b> Before anything, because {@code TuiConfig}'s default
 * {@code errorOutput} is {@code System.err} captured at construction time — i.e. the
 * screen.</li>
 * <li><b>Create the runner.</b> This builds JLine's system terminal, which captures the
 * real streams while they are still the real streams.</li>
 * <li><b>Install the redirect.</b> Now {@code System.out}/{@code System.err} go to the
 * log file and cannot corrupt a frame.</li>
 * <li><b>Subscribe, then list, then run.</b> The watch buffers from before the first page
 * arrives, so nothing that happens during the load is missed — see
 * {@link ScreenSession}.</li>
 * </ol>
 *
 * <p>
 * Every one of those has a {@code finally}: a watch left open holds a connection on the
 * cluster, and a redirect left installed makes the shell the operator returns to silent.
 */
@Service
@RequiredArgsConstructor
public class TuiScreen implements Screen {

	/** stdout is not a terminal, so there is nowhere to draw. */
	static final int EXIT_NO_TERMINAL = 4;

	/** The screen could not be started or died. */
	static final int EXIT_SCREEN_FAILED = 5;

	private final ClusterDataSource cluster;

	private final KindCatalog catalog;

	@Override
	public int run(ResourceQuery query, int chunkSize, TickRate tick, PrintWriter out) {
		if (System.console() == null) {
			out.println("Not a terminal — kweblens-tui's screen needs a tty. "
					+ "Run it from a terminal, or use --once for a plain listing.");
			out.flush();
			return EXIT_NO_TERMINAL;
		}
		return run(query, chunkSize, tick, null, out, (session) -> {
		});
	}

	/**
	 * The same run with an explicit {@link TuiConfig} and a hook that sees the session
	 * once it is live — which is how a test drives the real event loop against a fake
	 * backend, with no terminal and no cluster.
	 * @param config the TamboUI configuration, or null for this build's own
	 * @param started called on the calling thread after the model is loaded and the watch
	 * is open, before the loop starts
	 */
	int run(ResourceQuery query, int chunkSize, TickRate tick, TuiConfig config, PrintWriter out,
			Consumer<ScreenSession> started) {
		try (ScreenSession session = new ScreenSession(this.cluster, query, tick)
			.kinds(this.catalog.of(query.clusterId())); TerminalOutputGuard guard = TerminalOutputGuard.open()) {
			TuiConfig effective = (config != null) ? config : defaults(tick, guard);
			try (TuiRunner runner = TuiRunner.create(effective)) {
				guard.install();
				return loop(runner, session, chunkSize, started);
			}
		}
		catch (Exception ex) {
			out.println("The screen could not run: " + ex);
			out.flush();
			return EXIT_SCREEN_FAILED;
		}
	}

	private int loop(TuiRunner runner, ScreenSession session, int chunkSize, Consumer<ScreenSession> started)
			throws Exception {
		// Subscribe BEFORE the load, so nothing that happens during it is missed; the
		// session owns the watch from here and releasing it is what closing the session
		// means. See ScreenSession.
		session.subscribe();
		session.load(chunkSize);
		started.accept(session);
		runner.run(session.screen(), session.screen());
		return 0;
	}

	/**
	 * This build's TamboUI configuration. The tick rate is the coalescing period — that
	 * is the whole of #364 in one setter — and {@code errorOutput} is pointed at the log
	 * file, because its default is the terminal the app is drawing on.
	 */
	private static TuiConfig defaults(TickRate tick, TerminalOutputGuard guard) {
		return TuiConfig.builder().tickRate(tick.period()).errorOutput(guard.stream()).build();
	}

}

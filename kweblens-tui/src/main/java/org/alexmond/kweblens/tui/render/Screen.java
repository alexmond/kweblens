package org.alexmond.kweblens.tui.render;

import java.io.PrintWriter;

import org.alexmond.kweblens.tui.data.ResourceQuery;
import org.alexmond.kweblens.tui.screen.TickRate;

/**
 * The interactive surface, as the argv seam sees it: run until the operator quits, and
 * return an exit code.
 *
 * <p>
 * An interface with one implementation, for the same reason
 * {@code org.alexmond.kweblens.tui.data.ClusterDataSource} is: it keeps
 * {@code TuiCommand}'s decision — screen or plain listing — testable without a terminal,
 * and it keeps every TamboUI type on this side of the boundary. {@code TuiCommand}
 * imports nothing from {@code dev.tamboui}.
 */
@FunctionalInterface
public interface Screen {

	/**
	 * Draw {@code query}'s kind until the operator quits.
	 * @param query what to show
	 * @param chunkSize objects per list request
	 * @param tick how often the screen may repaint
	 * @param out where to explain a refusal — never the screen itself, because if this
	 * returns without drawing there is no screen
	 * @return the process exit code
	 */
	int run(ResourceQuery query, int chunkSize, TickRate tick, PrintWriter out);

}

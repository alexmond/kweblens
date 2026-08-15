package org.alexmond.kweblens.tui.render;

import java.time.Clock;

import org.alexmond.kweblens.tui.data.ClusterDataSource;
import org.alexmond.kweblens.tui.data.ResourceQuery;
import org.alexmond.kweblens.tui.data.Subscription;
import org.alexmond.kweblens.tui.screen.CoreRowBatch;
import org.alexmond.kweblens.tui.screen.ResourceModel;
import org.alexmond.kweblens.tui.screen.RowBatch;
import org.alexmond.kweblens.tui.screen.TickRate;
import org.alexmond.kweblens.tui.screen.WatchCoalescer;

/**
 * One kind, on one cluster, for as long as the screen is up: the model, the coalescer,
 * the watch and the screen that draws them.
 *
 * <h2>Subscribe first, list second</h2>
 *
 * {@link #subscribe()} is called <em>before</em> {@link #load(int)}, which looks
 * backwards and is not. A watch started after the list loses every change that happened
 * in between; a watch started before it simply buffers, and because the buffer is keyed
 * by {@code namespace/name} the first tick lands those changes on top of the listed rows.
 * Nothing is drawn in between either way — the buffer is not the model.
 *
 * <h2>Pages, not a list</h2>
 *
 * {@link #load(int)} goes through {@code listRawChunked} and projects each page as it
 * arrives, dropping the objects. Nothing here ever holds the whole kind; a terminal has
 * no more heap than a browser (#292/#293). One projection per page means one
 * {@code StatusContext} per page — holding every page to get a single open would spend
 * exactly the heap the paging exists to bound.
 */
public class ScreenSession implements AutoCloseable {

	private final ClusterDataSource cluster;

	private final ResourceQuery query;

	private final ResourceModel model = new ResourceModel();

	private final RowBatch projection;

	private final WatchCoalescer coalescer;

	private final ResourceScreen screen;

	private Subscription watch;

	public ScreenSession(ClusterDataSource cluster, ResourceQuery query, TickRate tick) {
		this(cluster, query, tick, new CoreRowBatch(cluster, query, Clock.systemUTC()));
	}

	/**
	 * With an explicit projection, so a test needs neither core services nor a cluster.
	 */
	public ScreenSession(ClusterDataSource cluster, ResourceQuery query, TickRate tick, RowBatch projection) {
		this.cluster = cluster;
		this.query = query;
		this.projection = projection;
		this.coalescer = new WatchCoalescer(this.model, projection);
		this.screen = new ResourceScreen(this.model, this.coalescer, query, tick);
	}

	/**
	 * Start buffering changes. The session owns the handle from here, and
	 * {@link #close()} is what releases it — a watch that outlives the screen holds a
	 * connection open on the cluster for nobody.
	 */
	public void subscribe() {
		this.watch = this.cluster.watch(this.query, this.coalescer::offer);
	}

	/** Fill the model from the cluster, a page at a time. */
	public void load(int chunkSize) {
		this.cluster.list(this.query, chunkSize, (page) -> this.model.upsert(this.projection.project(page)));
	}

	public ResourceScreen screen() {
		return this.screen;
	}

	public ResourceModel model() {
		return this.model;
	}

	public WatchCoalescer coalescer() {
		return this.coalescer;
	}

	@Override
	public void close() {
		if (this.watch != null) {
			this.watch.close();
			this.watch = null;
		}
	}

}

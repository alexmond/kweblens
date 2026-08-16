package org.alexmond.kweblens.tui.render;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.alexmond.kweblens.tui.data.ClusterDataSource;
import org.alexmond.kweblens.tui.data.ResourceQuery;
import org.alexmond.kweblens.tui.data.Subscription;
import org.alexmond.kweblens.tui.screen.CoreRowBatch;
import org.alexmond.kweblens.tui.screen.ResourceModel;
import org.alexmond.kweblens.tui.screen.ResourceRow;
import org.alexmond.kweblens.tui.screen.RowBatch;
import org.alexmond.kweblens.tui.screen.TickRate;
import org.alexmond.kweblens.tui.screen.WatchCoalescer;
import org.alexmond.kweblens.tui.screen.WatchLease;
import org.alexmond.kweblens.tui.screen.WatchSupervisor;

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
 *
 * <h2>A watch that dies is re-established here</h2>
 *
 * {@link #reconnect} is the {@code WatchRestart} the {@link WatchSupervisor} runs: same
 * order — subscribe, then list — because a reconnect has the same gap as a start. It
 * closes the previous handle first, which is what keeps a cluster that refuses ten times
 * in a row from leaving ten watches open. Unlike {@link #load(int)} it accumulates the
 * projected rows and hands them back rather than upserting them, because the supervisor
 * replaces the model with them on the render thread: a row deleted while the screen was
 * blind disappears only if the re-list is a <em>replacement</em>, and the objects are
 * still dropped a page at a time.
 *
 * <p>
 * <b>And the replaced watch is not still being listened to.</b> Both subscriptions are
 * opened through {@link #open}, which points the buffer at the new one before opening it,
 * so the dying watch cannot put a row back after the re-list has corrected it (GH#417).
 */
public class ScreenSession implements AutoCloseable {

	private final ClusterDataSource cluster;

	private final ResourceQuery query;

	private final ResourceModel model = new ResourceModel();

	private final RowBatch projection;

	private final WatchCoalescer coalescer;

	private final WatchSupervisor supervisor;

	private final ResourceScreen screen;

	/**
	 * Written by the render thread and by the recovery thread — see {@link #reconnect}.
	 */
	private final AtomicReference<Subscription> watch = new AtomicReference<>();

	/**
	 * Raised before {@link #close()} releases anything, so a reconnect in flight cleans
	 * up after itself.
	 */
	private final AtomicBoolean closed = new AtomicBoolean();

	/** Remembered from {@link #load(int)}, so a reconnect re-lists the same way. */
	private int chunkSize;

	public ScreenSession(ClusterDataSource cluster, ResourceQuery query, TickRate tick) {
		this(cluster, query, tick, Clock.systemUTC());
	}

	/**
	 * With an explicit clock, which is what makes "stopped updating 12s ago" assertable.
	 */
	public ScreenSession(ClusterDataSource cluster, ResourceQuery query, TickRate tick, Clock clock) {
		this(cluster, query, tick, clock, new CoreRowBatch(cluster, query, clock));
	}

	/**
	 * With an explicit projection, so a test needs neither core services nor a cluster.
	 */
	public ScreenSession(ClusterDataSource cluster, ResourceQuery query, TickRate tick, Clock clock,
			RowBatch projection) {
		this.cluster = cluster;
		this.query = query;
		this.projection = projection;
		this.coalescer = new WatchCoalescer(this.model, projection);
		this.supervisor = new WatchSupervisor(clock, this.model, this::reconnect);
		this.screen = new ResourceScreen(this.model, this.coalescer, this.supervisor, query, tick);
	}

	/**
	 * Start buffering changes. The session owns the handle from here, and
	 * {@link #close()} is what releases it — a watch that outlives the screen holds a
	 * connection open on the cluster for nobody.
	 */
	public void subscribe() {
		this.watch.set(open(this.supervisor.lease()));
	}

	/**
	 * Point the buffer at this subscription and open it, in that order.
	 *
	 * <p>
	 * The rebase comes first because it is what makes the previous subscription's events
	 * unwelcome — both the ones it already left in the buffer, which the rebase throws
	 * away, and the ones it delivers on its way out, which are stamped with a generation
	 * the buffer now refuses. Both are older than the list this subscription is about to
	 * take, and applying either on top of that list is GH#417.
	 */
	private Subscription open(WatchLease lease) {
		this.coalescer.rebase(lease.generation());
		return this.cluster.watch(this.query, this.coalescer.sink(lease.generation()), lease.onEnd());
	}

	/** Fill the model from the cluster, a page at a time. */
	public void load(int chunkSize) {
		this.chunkSize = chunkSize;
		this.cluster.list(this.query, chunkSize, (page) -> this.model.upsert(this.projection.project(page)));
	}

	/**
	 * Re-establish the watch and re-read the kind, on the supervisor's recovery thread.
	 *
	 * <p>
	 * <b>Nothing here touches the model</b>, which is the render thread's alone. If the
	 * subscribe succeeds and the list then fails, the exception propagates and the screen
	 * stays NOT LIVE with a watch open — understating liveness rather than overstating
	 * it, and the next attempt closes that watch before opening another.
	 */
	private List<ResourceRow> reconnect(WatchLease lease) {
		Subscription previous = this.watch.getAndSet(null);
		if (previous != null) {
			previous.close();
		}
		Subscription opened = open(lease);
		this.watch.set(opened);
		if (this.closed.get()) {
			// close() ran while this attempt was on the network. Nobody else will release
			// this handle, so it releases itself.
			opened.close();
		}
		List<ResourceRow> fresh = new ArrayList<>();
		this.cluster.list(this.query, this.chunkSize, (page) -> fresh.addAll(this.projection.project(page)));
		return fresh;
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

	public WatchSupervisor supervisor() {
		return this.supervisor;
	}

	@Override
	public void close() {
		this.closed.set(true);
		this.supervisor.close();
		Subscription open = this.watch.getAndSet(null);
		if (open != null) {
			open.close();
		}
	}

}

package org.alexmond.kweblens.tui.render;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import org.alexmond.kweblens.tui.data.ClusterDataSource;
import org.alexmond.kweblens.tui.data.ResourceQuery;
import org.alexmond.kweblens.tui.data.Subscription;
import org.alexmond.kweblens.tui.data.WatchEnd;
import org.alexmond.kweblens.tui.kind.KindIndex;
import org.alexmond.kweblens.tui.screen.CoreRowBatch;
import org.alexmond.kweblens.tui.screen.Navigation;
import org.alexmond.kweblens.tui.screen.ResourceModel;
import org.alexmond.kweblens.tui.screen.ResourceRow;
import org.alexmond.kweblens.tui.screen.RetargetableRowBatch;
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
 *
 * <p>
 * <b>Nor is the replacement heard before its own list.</b> The same rebase holds the new
 * subscription's events until the rows it took are in the model, and {@link #install} is
 * the one place that installs them and releases the hold — in that order, so a change
 * newer than the list lands on top of it instead of being wiped by it (GH#420). The two
 * paths that list on the calling thread release the same hold at the end of
 * {@link #load(int)}.
 *
 * <p>
 * <b>And a reconnect the operator navigated past installs nothing.</b> {@link #switchTo}
 * runs on the render thread while {@link #reconnect} may be mid-flight on the recovery
 * thread, reading a query that has since been retargeted; the rows it hands back
 * therefore belong to a kind or a scope nobody is showing. {@link WatchSupervisor}
 * discards them by generation, and {@link #load(int)} — which is exactly the set of paths
 * that subscribe and list on the calling thread — is what tells it the screen is live
 * again on its own subscription instead (GH#431).
 *
 * <p>
 * <b>And a switch the cluster refuses is a message, not an exception.</b> Those same two
 * calls are the ones that can fail, on the render thread, inside a key press;
 * {@link #switchTo} therefore catches both, empties the model, reports the subscription
 * lost so the header leads with NOT LIVE and the retry loop takes the new kind up, and
 * hands the sentence back for the footer (GH#434).
 */
public class ScreenSession implements Navigation, AutoCloseable {

	private final ClusterDataSource cluster;

	/**
	 * The kind and scope being shown. Mutable because the view stack is: {@code :svc}, a
	 * namespace favourite and a drill-down all change it, and every one of them runs on
	 * the render thread inside {@link #switchTo}. It is an {@link AtomicReference} rather
	 * than a plain field because the recovery thread reads it in {@link #reconnect}.
	 */
	private final AtomicReference<ResourceQuery> query;

	private final ResourceModel model = new ResourceModel();

	/**
	 * Builds the projection for a query — one {@code StatusContext} per page, per kind.
	 */
	private final Function<ResourceQuery, RowBatch> projections;

	private final RetargetableRowBatch projection;

	private final WatchCoalescer coalescer;

	private final WatchSupervisor supervisor;

	private final ResourceScreen screen;

	/**
	 * Written by the render thread and by the recovery thread — see {@link #reconnect}.
	 */
	private final AtomicReference<Subscription> watch = new AtomicReference<>();

	/**
	 * The generation of the subscription {@link #open} most recently minted — what
	 * {@link #load(int)} names when it says that subscription's list has landed. Written
	 * by the render thread and by the recovery thread, for the same reason {@link #watch}
	 * is.
	 */
	private final AtomicLong subscription = new AtomicLong();

	/**
	 * Raised before {@link #close()} releases anything, so a reconnect in flight cleans
	 * up after itself.
	 */
	private final AtomicBoolean closed = new AtomicBoolean();

	/** Remembered from {@link #load(int)}, so a reconnect re-lists the same way. */
	private int chunkSize;

	/**
	 * What the command line can name. Empty until {@link #kinds(KindIndex)} is called, so
	 * a session built without discovery draws perfectly well and answers {@code :}
	 * honestly rather than pretending it knows nothing exists.
	 */
	private KindIndex kinds = KindIndex.empty();

	public ScreenSession(ClusterDataSource cluster, ResourceQuery query, TickRate tick) {
		this(cluster, query, tick, Clock.systemUTC());
	}

	/**
	 * With an explicit clock, which is what makes "stopped updating 12s ago" assertable.
	 */
	public ScreenSession(ClusterDataSource cluster, ResourceQuery query, TickRate tick, Clock clock) {
		this(cluster, query, tick, clock, (target) -> new CoreRowBatch(cluster, target, clock));
	}

	/**
	 * With a projection <em>factory</em>, so a test needs neither core services nor a
	 * cluster — and, more to the point, because a view stack needs it: switching to
	 * another kind has to open that kind's {@code StatusContext}, and a projection fixed
	 * at construction would hand Pod verdicts to a page of Services.
	 */
	public ScreenSession(ClusterDataSource cluster, ResourceQuery query, TickRate tick, Clock clock,
			Function<ResourceQuery, RowBatch> projections) {
		this.cluster = cluster;
		this.query = new AtomicReference<>(query);
		this.projections = projections;
		this.projection = new RetargetableRowBatch(projections.apply(query));
		this.coalescer = new WatchCoalescer(this.model, this.projection);
		this.supervisor = new WatchSupervisor(clock, this::install, this::reconnect);
		this.screen = new ResourceScreen(this.model, this.coalescer, this.supervisor, query, tick, this);
		// The first kind's columns, from the same place every later kind's come from, so
		// the opening frame is not the one screen state with no headings.
		this.screen.retarget(query, this.projection.columns());
	}

	/**
	 * Start buffering changes. The session owns the handle from here, and
	 * {@link #close()} is what releases it — a watch that outlives the screen holds a
	 * connection open on the cluster for nobody.
	 *
	 * <p>
	 * <b>{@link #load(int)} must follow.</b> The subscription's events are held until its
	 * own list is in the model (GH#420), so a subscribe with no list is a table that
	 * never moves; the two are one operation everywhere they are used.
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
	 *
	 * <p>
	 * The rebase also holds this subscription's own events back until its list has
	 * landed, which is GH#420 — so every call here is paired with an {@code installed}
	 * once the rows are in the model: {@link #load(int)} for the two paths that list on
	 * this thread, {@link #install} for the reconnect, which does not.
	 */
	private Subscription open(WatchLease lease) {
		this.subscription.set(lease.generation());
		this.coalescer.rebase(lease.generation());
		return this.cluster.watch(this.query.get(), this.coalescer.sink(lease.generation()), lease.onEnd());
	}

	/**
	 * Fill the model from the cluster, a page at a time, and let the watch opened just
	 * before it start being applied.
	 *
	 * <p>
	 * This is the paired half of {@link #open}'s hold for the two paths that list on the
	 * calling thread — the first load and {@link #switchTo} — where no tick can run in
	 * between and the hold is therefore invisible. The reconnect is the path where it is
	 * not, and it releases the hold in {@link #install} instead.
	 *
	 * <p>
	 * <b>The supervisor is told the same thing, in the same place.</b> Reaching this line
	 * means the screen has a subscription of its own <em>and</em> that subscription's
	 * rows, which is the whole content of "the table is live" — so a switch typed while a
	 * reconnect was in flight ends the loss here rather than waiting for a recovery whose
	 * rows will be discarded (GH#431). Pairing it with the release rather than putting it
	 * in {@link #switchTo} is deliberate: {@link #load(int)} is exactly the set of paths
	 * that subscribe and list on the calling thread, so there is no second place to
	 * remember. If the list throws, neither half runs — so a loss already on the header
	 * stays there, which is true, and {@link #switchTo} reports the refusal for the case
	 * where there was no loss to leave standing (GH#434).
	 */
	public void load(int chunkSize) {
		this.chunkSize = chunkSize;
		this.cluster.list(this.query.get(), chunkSize, (page) -> this.model.upsert(this.projection.project(page)));
		this.coalescer.installed(this.subscription.get());
		this.supervisor.watching(this.subscription.get());
	}

	/**
	 * Put a finished reconnect on screen, on the render thread: the rows replace the
	 * model, and only then may the subscription that took them be heard.
	 *
	 * <p>
	 * The order is the whole of GH#420. The replacement is what makes a re-list honest
	 * (#413) and it is also what wipes anything already applied, so the events this
	 * subscription delivered while its list was on the network have to arrive
	 * <em>after</em> it — which is why they were held rather than flushed, and why the
	 * release is here rather than one line earlier.
	 */
	private void install(long generation, List<ResourceRow> rows) {
		this.model.replaceAll(rows);
		this.coalescer.installed(generation);
	}

	/**
	 * Show another kind, scope or filter: the whole of what a {@code :} command, a
	 * namespace favourite, a drill-down and an {@code esc} do to the data.
	 *
	 * <p>
	 * The order is the same one a reconnect keeps, and for the same reason. Close the old
	 * watch; point the projection at the new kind; empty the model, because rows of the
	 * old kind are not rows of the new one; open the new subscription, which
	 * <em>rebases</em> the buffer so anything the old watch is still delivering is
	 * refused rather than projected against the wrong kind; then list.
	 *
	 * <p>
	 * <b>This blocks the render thread for one list.</b> That is deliberate for a
	 * navigation the operator just asked for — they are waiting for the new view either
	 * way, and doing it on the recovery thread would mean drawing the old kind's rows
	 * under the new kind's title for as long as the list took. A watch that dies is the
	 * opposite case (nobody asked, and it can take thirty seconds), which is why
	 * {@link WatchSupervisor} does that one off-thread.
	 *
	 * <p>
	 * <b>Which is why this can land in the middle of one.</b> A recovery already in
	 * flight reads {@link #query} on its own thread and will hand back rows for whatever
	 * it read; the lease taken here moves the generation past it, so those rows are
	 * discarded rather than installed, and {@link #load(int)} tells the supervisor the
	 * screen is live on this subscription (GH#431). Nothing here waits for the recovery
	 * thread — a navigation the operator asked for must not be held up by a network call
	 * nobody asked for.
	 *
	 * <p>
	 * <b>And a switch the cluster refuses stays switched</b> (GH#434). Both of the last
	 * two steps can throw — {@code watch} before any subscription exists, {@code list}
	 * after one does — and letting either leave this method takes the exception out of
	 * {@code ViewController.key}, out of {@code ResourceScreen.handle} and into TamboUI's
	 * runner, which catches it, replaces the whole screen with a scrollable stack trace
	 * and <em>never leaves that state</em> (measured against 0.4.0: {@code inErrorState}
	 * is set and never cleared, and the default {@code RenderErrorHandler} is
	 * {@code displayAndQuit}). So it is caught here, and the screen is left in one
	 * coherent state rather than half of two:
	 * <ul>
	 * <li><b>on the kind the operator asked for, and empty.</b> The view stack was pushed
	 * before this was called, so rolling the data back would put the previous kind's rows
	 * under the new kind's title and breadcrumbs — the shape of GH#431, arrived at from
	 * the other side. Rolling the stack back too would need a re-subscribe and a re-list
	 * of the old kind, which is the same pair of calls that has just been refused.</li>
	 * <li><b>with nothing kept from a list that did not finish.</b> Pages already
	 * projected are dropped, because a partial page count reads exactly like a
	 * collection's size.</li>
	 * <li><b>saying so, in both places.</b> The supervisor is told the subscription is
	 * lost, so the header leads with NOT LIVE and the retry loop that exists for a dead
	 * watch keeps trying this kind until it answers — an RBAC refusal will simply keep
	 * refusing, and the notice says which. The sentence returned lands in the
	 * controller's footer message.</li>
	 * </ul>
	 * @return what could not be done, or empty when the view was filled
	 */
	public String switchTo(ResourceQuery next, Predicate<ResourceRow> filter) {
		Subscription previous = this.watch.getAndSet(null);
		if (previous != null) {
			previous.close();
		}
		this.query.set(next);
		this.projection.retarget(this.projections.apply(next));
		this.model.clear();
		this.model.applyFilter(filter);
		this.screen.retarget(next, this.projection.columns());
		WatchLease lease = this.supervisor.lease();
		try {
			this.watch.set(open(lease));
		}
		catch (RuntimeException ex) {
			return refused(lease, next, WatchEnd.notSubscribed(ex), "watch");
		}
		try {
			load(this.chunkSize);
		}
		catch (RuntimeException ex) {
			return refused(lease, next, WatchEnd.notListed(ex), "list");
		}
		return "";
	}

	/**
	 * Record a navigation the cluster would not serve, and say what it was.
	 *
	 * <p>
	 * The ending is reported through the switch's <em>own</em> lease, which is the
	 * newest, so {@code WatchSupervisor} believes it — and believing it is the whole
	 * point: {@link #load(int)} never reached {@code watching()}, but on a screen that
	 * was live a moment ago there is no loss to leave standing either, so without this
	 * the header would go on calling an empty table live. Reporting it also puts the
	 * recovery loop behind the new kind, so the screen heals itself when the cluster
	 * does.
	 */
	private String refused(WatchLease lease, ResourceQuery next, WatchEnd end, String step) {
		this.model.clear();
		lease.onEnd().accept(end);
		return "Could not " + step + " " + next.kind() + " (" + scopeOf(next) + "): " + end.reason()
				+ " — the table is empty and NOT LIVE while it retries.";
	}

	private static String scopeOf(ResourceQuery query) {
		if (!query.descriptor().namespaced()) {
			return "cluster-scoped";
		}
		return (query.namespace() == null || query.namespace().isBlank()) ? "all namespaces" : query.namespace();
	}

	/** The kind and scope on screen. */
	public ResourceQuery query() {
		return this.query.get();
	}

	/** Hand the session the cluster's discovered kinds, so {@code :} can name them. */
	public ScreenSession kinds(KindIndex index) {
		this.kinds = (index != null) ? index : KindIndex.empty();
		return this;
	}

	@Override
	public KindIndex kinds() {
		return this.kinds;
	}

	@Override
	public String clusterId() {
		return this.query.get().clusterId();
	}

	@Override
	public GenericKubernetesResource object(String namespace, String name) {
		return this.cluster.get(this.query.get().inNamespace(namespace), name);
	}

	@Override
	public String show(ResourceQuery target, Predicate<ResourceRow> filter) {
		return switchTo(target, filter);
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
		this.cluster.list(this.query.get(), this.chunkSize, (page) -> fresh.addAll(this.projection.project(page)));
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

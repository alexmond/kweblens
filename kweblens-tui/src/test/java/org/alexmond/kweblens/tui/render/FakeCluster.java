package org.alexmond.kweblens.tui.render;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;

import org.alexmond.kweblens.health.ObjectState;
import org.alexmond.kweblens.resource.DiscoveredKind;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.WellKnownKinds;
import org.alexmond.kweblens.tui.data.ClusterDataSource;
import org.alexmond.kweblens.tui.data.ExecSession;
import org.alexmond.kweblens.tui.data.LogStream;
import org.alexmond.kweblens.tui.data.PodTarget;
import org.alexmond.kweblens.tui.data.ResourceQuery;
import org.alexmond.kweblens.tui.data.Subscription;
import org.alexmond.kweblens.tui.data.WatchEnd;

/**
 * A cluster the test drives by hand: a fixed list, and a watch it can fire into on
 * demand.
 *
 * <p>
 * The real adapter is covered by {@code CoreClusterDataSourceListTest} against the in-JVM
 * API server. What the loop tests need instead is a source whose <em>timing</em> is
 * theirs — 157 events with nothing in between — which no API server double will give you.
 */
public class FakeCluster implements ClusterDataSource {

	private final List<GenericKubernetesResource> initial = new ArrayList<>();

	/**
	 * Per-kind answers, by descriptor id; {@link #initial} is what a kind without one
	 * gets.
	 */
	private final Map<String, List<GenericKubernetesResource>> byKind = new ConcurrentHashMap<>();

	private final AtomicBoolean watchClosed = new AtomicBoolean();

	private final AtomicInteger opened = new AtomicInteger();

	private final AtomicInteger closed = new AtomicInteger();

	private final AtomicInteger lists = new AtomicInteger();

	/**
	 * Every sink ever handed to {@link #watch}, in the order they were opened, including
	 * the ones that have since been replaced — see {@link #fireFromWatch}.
	 */
	private final List<BiConsumer<String, GenericKubernetesResource>> sinks = new CopyOnWriteArrayList<>();

	/** Set while a {@link #list} is parked on {@link #holdNextList()}. */
	private final AtomicBoolean listHeld = new AtomicBoolean();

	/** The gate the <em>next</em> list will take, or null when no hold is armed. */
	private final AtomicReference<CountDownLatch> nextGate = new AtomicReference<>();

	/** The gate a list is parked on right now, so {@link #releaseList()} can find it. */
	private final AtomicReference<CountDownLatch> heldGate = new AtomicReference<>();

	private volatile BiConsumer<String, GenericKubernetesResource> subscriber;

	private volatile Consumer<WatchEnd> ender;

	/**
	 * When set, {@link #watch} throws it instead of subscribing — a cluster that refuses.
	 */
	private volatile RuntimeException refuseWatch;

	/** When set, {@link #list} throws it — see {@link #refuseList}. */
	private volatile RuntimeException refuseList;

	/** How many pages {@link #list} delivers before {@link #refuseList} is thrown. */
	private volatile int refuseListAfterPages;

	/** A Pod-shaped object with the given name in namespace {@code ns}. */
	public static GenericKubernetesResource object(String name) {
		return object("Pod", name);
	}

	/**
	 * The same, of another kind — so a test that switches kinds can tell which list's
	 * rows are on screen from the rows themselves rather than from a count.
	 */
	public static GenericKubernetesResource object(String kind, String name) {
		return new GenericKubernetesResourceBuilder().withApiVersion("v1")
			.withKind(kind)
			.withNewMetadata()
			.withNamespace("ns")
			.withName(name)
			.withCreationTimestamp("2026-08-01T00:00:00Z")
			.endMetadata()
			.build();
	}

	/** Seed the list this source returns. */
	public FakeCluster withObjects(int count) {
		for (int i = 0; i < count; i++) {
			this.initial.add(object(String.format("obj-%05d", i)));
		}
		return this;
	}

	/** Replace what the next list returns, so a re-list can differ from the first one. */
	public FakeCluster setObjects(List<GenericKubernetesResource> objects) {
		this.initial.clear();
		this.initial.addAll(objects);
		return this;
	}

	/**
	 * What a list of <em>this kind</em> returns, leaving every other kind alone.
	 *
	 * <p>
	 * Without it every list here answers the same objects, and a test about a kind switch
	 * would be asserting on rows that are correct for both kinds — which is precisely the
	 * defect it is trying to see (GH#431).
	 */
	public FakeCluster setObjects(ResourceDescriptor kind, List<GenericKubernetesResource> objects) {
		this.byKind.put(kind.id(), List.copyOf(objects));
		return this;
	}

	/** Deliver one watch event to whoever subscribed. */
	public void fire(String action, GenericKubernetesResource object) {
		BiConsumer<String, GenericKubernetesResource> target = this.subscriber;
		if (target != null) {
			target.accept(action, object);
		}
	}

	/**
	 * Deliver one event through the sink a <em>particular</em> subscription was given,
	 * including one that has since been closed and replaced.
	 *
	 * <p>
	 * That is not a simulation of anything: it is the same consumer object {@link #watch}
	 * was handed, called the way fabric8 calls it. A watch does not stop delivering the
	 * instant it is closed — the ending and the close race with whatever the client had
	 * already read — and an event that arrives from a dead handle is what GH#417 is
	 * about.
	 * @param watch which subscription, in the order they were opened, from zero
	 */
	public void fireFromWatch(int watch, String action, GenericKubernetesResource object) {
		this.sinks.get(watch).accept(action, object);
	}

	/**
	 * Park the next {@link #list} until {@link #releaseList()}, so a test can hold a
	 * reconnect open between its re-subscribe and its re-list — the window GH#417 lives
	 * in — instead of racing it.
	 *
	 * <p>
	 * <b>The next one, and only that one.</b> The gate is taken by the first list that
	 * reaches it, so a list the test issues <em>while</em> the reconnect is parked — a
	 * {@code switchTo} typed inside the window, which is GH#431 — runs straight through
	 * instead of deadlocking behind it. Holding every list until the release would make
	 * the render thread wait on a background thread the test is deliberately holding
	 * still.
	 */
	public FakeCluster holdNextList() {
		this.nextGate.set(new CountDownLatch(1));
		return this;
	}

	/** Whether a list is parked right now. */
	public boolean listHeld() {
		return this.listHeld.get();
	}

	/** Let the parked list finish, or disarm a hold no list ever reached. */
	public void releaseList() {
		CountDownLatch gate = this.heldGate.getAndSet(null);
		if (gate == null) {
			gate = this.nextGate.getAndSet(null);
		}
		if (gate != null) {
			gate.countDown();
		}
	}

	/**
	 * Kill the watch the way the API server does: report the ending and stop delivering.
	 * @param clean a stream that ended, rather than one that broke
	 */
	public void killWatch(boolean clean) {
		Consumer<WatchEnd> target = this.ender;
		this.subscriber = null;
		if (target != null) {
			this.ender = null;
			target.accept((clean) ? WatchEnd.completed()
					: WatchEnd.failed(new IllegalStateException("410: too old resource version")));
		}
	}

	/**
	 * Make the next {@code watch} calls throw, as a cluster that is not answering would.
	 */
	public FakeCluster refuseWatch(RuntimeException failure) {
		this.refuseWatch = failure;
		return this;
	}

	/**
	 * Make {@code list} throw, from now until {@link #serveAgain()}.
	 *
	 * <p>
	 * <b>After {@code afterPages} pages have been delivered</b>, because the two are
	 * different failures: a list refused outright leaves the model empty by itself, while
	 * one that dies mid-collection has already put rows in it — and a partial page count
	 * on a header reads exactly like the size of the collection. Pass zero for the first
	 * shape, one or more for the second.
	 */
	public FakeCluster refuseList(int afterPages, RuntimeException failure) {
		this.refuseListAfterPages = afterPages;
		this.refuseList = failure;
		return this;
	}

	/** Stop refusing, so a test can watch the screen recover from what it just broke. */
	public FakeCluster serveAgain() {
		this.refuseWatch = null;
		this.refuseList = null;
		return this;
	}

	/** Whether the subscription was released — a watch left open leaks a connection. */
	public boolean watchClosed() {
		return this.watchClosed.get();
	}

	/** How many watches have ever been opened. One per subscribe, one per reconnect. */
	public int watchesOpened() {
		return this.opened.get();
	}

	/**
	 * How many of them were closed. The gap is how many are still holding a connection.
	 */
	public int watchesClosed() {
		return this.closed.get();
	}

	/** How many times the kind was listed. */
	public int lists() {
		return this.lists.get();
	}

	/**
	 * What the command line can name here. Deliberately small and deliberately
	 * <b>including a CRD-shaped kind no catalog knows</b>, so a navigation test proves
	 * the screen reaches a kind by discovery rather than by a built-in list.
	 */
	private final List<DiscoveredKind> kinds = new ArrayList<>(
			List.of(new DiscoveredKind(WellKnownKinds.PODS, "pod", List.of("po")),
					new DiscoveredKind(WellKnownKinds.NAMESPACES, "namespace", List.of("ns")),
					new DiscoveredKind(WellKnownKinds.EVENTS, "event", List.of("ev")),
					new DiscoveredKind(ResourceDescriptor.namespaced("apps.deployments", "Deployment", "Deployment",
							"apps", "v1", "deployments"), "deployment", List.of("deploy")),
					new DiscoveredKind(ResourceDescriptor.namespaced("traefik.io.ingressroutes", "IngressRoute",
							"IngressRoute", "traefik.io", "v1alpha1", "ingressroutes"), "ingressroute", List.of())));

	/** The object {@link #get} hands back, for a drill-down. */
	private volatile GenericKubernetesResource object;

	@Override
	public List<String> clusters() {
		return List.of("fake");
	}

	@Override
	public List<DiscoveredKind> kinds(String clusterId) {
		return List.copyOf(this.kinds);
	}

	/**
	 * What the next {@link #get} returns — the object a drill-down reads a selector off.
	 */
	public FakeCluster withObject(GenericKubernetesResource single) {
		this.object = single;
		return this;
	}

	@Override
	public void list(ResourceQuery query, int chunkSize, Consumer<List<GenericKubernetesResource>> onPage) {
		hold();
		List<GenericKubernetesResource> snapshot = this.byKind.getOrDefault(query.descriptor().id(),
				List.copyOf(this.initial));
		int size = (chunkSize > 0) ? chunkSize : Math.max(1, snapshot.size());
		RuntimeException refusal = this.refuseList;
		int stopAfter = this.refuseListAfterPages;
		int delivered = 0;
		for (int from = 0; from < snapshot.size(); from += size) {
			if (refusal != null && delivered >= stopAfter) {
				throw refusal;
			}
			onPage.accept(List.copyOf(snapshot.subList(from, Math.min(from + size, snapshot.size()))));
			delivered++;
		}
		if (refusal != null) {
			// A refusal armed for more pages than the kind has is still a refusal: a list
			// that ends without delivering the collection has failed either way.
			throw refusal;
		}
		// Counted when the pages have been delivered, not when the request starts, so a
		// test that waits on lists() is waiting for rows and not for an intention. Same
		// lesson as GH#423's tick counter.
		this.lists.incrementAndGet();
	}

	@Override
	public List<Optional<ObjectState>> states(ResourceQuery query, List<GenericKubernetesResource> objects) {
		return objects.stream().map((object) -> Optional.<ObjectState>empty()).toList();
	}

	@Override
	public GenericKubernetesResource get(ResourceQuery query, String name) {
		return this.object;
	}

	@Override
	public Subscription watch(ResourceQuery query, BiConsumer<String, GenericKubernetesResource> onEvent,
			Consumer<WatchEnd> onEnd) {
		RuntimeException refusal = this.refuseWatch;
		if (refusal != null) {
			throw refusal;
		}
		this.opened.incrementAndGet();
		this.sinks.add(onEvent);
		this.subscriber = onEvent;
		this.ender = onEnd;
		return () -> {
			this.closed.incrementAndGet();
			this.watchClosed.set(true);
		};
	}

	private void hold() {
		CountDownLatch gate = this.nextGate.getAndSet(null);
		if (gate == null) {
			return;
		}
		this.heldGate.set(gate);
		this.listHeld.set(true);
		try {
			gate.await();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("the held list was interrupted", ex);
		}
		finally {
			this.listHeld.set(false);
		}
	}

	@Override
	public LogStream logs(PodTarget target) {
		throw new UnsupportedOperationException("not used by the screen");
	}

	@Override
	public ExecSession exec(PodTarget target, OutputStream output) {
		throw new UnsupportedOperationException("not used by the screen");
	}

}

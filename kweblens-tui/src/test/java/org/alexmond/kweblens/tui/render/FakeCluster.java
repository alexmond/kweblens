package org.alexmond.kweblens.tui.render;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

	private final AtomicReference<CountDownLatch> listGate = new AtomicReference<>();

	private volatile BiConsumer<String, GenericKubernetesResource> subscriber;

	private volatile Consumer<WatchEnd> ender;

	/**
	 * When set, {@link #watch} throws it instead of subscribing — a cluster that refuses.
	 */
	private volatile RuntimeException refuseWatch;

	/** A Pod-shaped object with the given name in namespace {@code ns}. */
	public static GenericKubernetesResource object(String name) {
		return new GenericKubernetesResourceBuilder().withApiVersion("v1")
			.withKind("Pod")
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
	 */
	public FakeCluster holdNextList() {
		this.listGate.set(new CountDownLatch(1));
		return this;
	}

	/** Whether a list is parked right now. */
	public boolean listHeld() {
		return this.listHeld.get();
	}

	/** Let the parked list finish. */
	public void releaseList() {
		CountDownLatch gate = this.listGate.getAndSet(null);
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

	@Override
	public List<String> clusters() {
		return List.of("fake");
	}

	@Override
	public void list(ResourceQuery query, int chunkSize, Consumer<List<GenericKubernetesResource>> onPage) {
		hold();
		this.lists.incrementAndGet();
		List<GenericKubernetesResource> snapshot = List.copyOf(this.initial);
		int size = (chunkSize > 0) ? chunkSize : Math.max(1, snapshot.size());
		for (int from = 0; from < snapshot.size(); from += size) {
			onPage.accept(List.copyOf(snapshot.subList(from, Math.min(from + size, snapshot.size()))));
		}
	}

	@Override
	public List<Optional<ObjectState>> states(ResourceQuery query, List<GenericKubernetesResource> objects) {
		return objects.stream().map((object) -> Optional.<ObjectState>empty()).toList();
	}

	@Override
	public GenericKubernetesResource get(ResourceQuery query, String name) {
		throw new UnsupportedOperationException("not used by the screen");
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
		CountDownLatch gate = this.listGate.get();
		if (gate == null) {
			return;
		}
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

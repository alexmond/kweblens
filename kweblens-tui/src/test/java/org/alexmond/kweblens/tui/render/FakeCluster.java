package org.alexmond.kweblens.tui.render;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
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

	private volatile BiConsumer<String, GenericKubernetesResource> subscriber;

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

	/** Deliver one watch event to whoever subscribed. */
	public void fire(String action, GenericKubernetesResource object) {
		BiConsumer<String, GenericKubernetesResource> target = this.subscriber;
		if (target != null) {
			target.accept(action, object);
		}
	}

	/** Whether the subscription was released — a watch left open leaks a connection. */
	public boolean watchClosed() {
		return this.watchClosed.get();
	}

	@Override
	public List<String> clusters() {
		return List.of("fake");
	}

	@Override
	public void list(ResourceQuery query, int chunkSize, Consumer<List<GenericKubernetesResource>> onPage) {
		int size = (chunkSize > 0) ? chunkSize : this.initial.size();
		for (int from = 0; from < this.initial.size(); from += size) {
			onPage.accept(List.copyOf(this.initial.subList(from, Math.min(from + size, this.initial.size()))));
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
	public Subscription watch(ResourceQuery query, BiConsumer<String, GenericKubernetesResource> onEvent) {
		this.subscriber = onEvent;
		return () -> this.watchClosed.set(true);
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

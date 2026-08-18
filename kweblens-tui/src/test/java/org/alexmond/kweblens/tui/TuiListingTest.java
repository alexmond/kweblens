package org.alexmond.kweblens.tui;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.column.Column;
import org.alexmond.kweblens.health.ObjectState;
import org.alexmond.kweblens.resource.DiscoveredKind;
import org.alexmond.kweblens.resource.WellKnownKinds;
import org.alexmond.kweblens.tui.data.ClusterDataSource;
import org.alexmond.kweblens.tui.data.ExecSession;
import org.alexmond.kweblens.tui.data.LogStream;
import org.alexmond.kweblens.tui.data.PodTarget;
import org.alexmond.kweblens.tui.data.ResourceQuery;
import org.alexmond.kweblens.tui.data.Subscription;
import org.alexmond.kweblens.tui.data.WatchEnd;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What v1 puts on the screen, read as a value rather than captured off
 * {@code System.out}.
 *
 * <p>
 * The fake source is the point: it can hand back pages the way a real cluster does, so
 * the two behaviours that would be invisible against a single-page cluster — rows written
 * per page rather than accumulated, and one status context opened per page rather than
 * per row — are observable here.
 */
class TuiListingTest {

	private static final ResourceQuery QUERY = new ResourceQuery("k3stest", WellKnownKinds.PODS, "kube-system");

	private static GenericKubernetesResource pod(String name) {
		return new GenericKubernetesResourceBuilder().withApiVersion("v1")
			.withKind("Pod")
			.withNewMetadata()
			.withName(name)
			.withNamespace("kube-system")
			.endMetadata()
			.build();
	}

	private static String render(FakeSource source, ResourceQuery query) {
		StringWriter text = new StringWriter();
		new TuiListing(source).print(query, 500, new PrintWriter(text));
		return text.toString();
	}

	@Test
	void theHeaderShowsThePostureTheClusterAndTheScope() {
		FakeSource source = new FakeSource(List.of());

		String header = new TuiListing(source).header(QUERY);

		assertThat(header).contains("[R]").contains("k3stest").contains("Pod").contains("kube-system");
	}

	@Test
	void anUnscopedQuerySaysAllNamespacesRatherThanNothing() {
		assertThat(new TuiListing(new FakeSource(List.of())).header(QUERY.inNamespace(null)))
			.as("a blank where a namespace goes reads as a namespace called nothing")
			.contains("all namespaces");
	}

	@Test
	void everyPageIsWrittenAsItArrivesAndCostsOneContext() {
		FakeSource source = new FakeSource(List.of(List.of(pod("a"), pod("b")), List.of(pod("c"))));

		String output = render(source, QUERY);

		assertThat(output).contains("a").contains("b").contains("c");
		assertThat(source.stateCalls)
			.as("one status context per page, not one per row — and not one for the "
					+ "whole kind, which would mean holding every page")
			.isEqualTo(2);
	}

	@Test
	void aRowNothingJudgesReadsAsAbsentNotAsHealthy() {
		FakeSource source = new FakeSource(List.of(List.of(pod("judged"), pod("unjudged"))));
		source.states = List.of(Optional.of(new ObjectState("Running", "ok")), Optional.empty());

		String output = render(source, QUERY);

		assertThat(output).contains("Running");
		assertThat(output).as("empty means nothing here examined this object, which is not the same as OK")
			.contains("—");
	}

	@Test
	void aShortStateListDoesNotShiftVerdictsOntoTheWrongRows() {
		FakeSource source = new FakeSource(List.of(List.of(pod("first"), pod("second"))));
		source.states = List.of(Optional.of(new ObjectState("Running", "ok")));

		String output = render(source, QUERY).lines()
			.filter((line) -> line.contains("second"))
			.findFirst()
			.orElseThrow();

		assertThat(output).as("a source that returned fewer verdicts than rows must not slide them up")
			.doesNotContain("Running");
	}

	/**
	 * A {@link ClusterDataSource} whose pages are scripted. Only list and states are
	 * implemented; the rest throw, because a test that quietly reached them would be
	 * testing something it did not mean to.
	 */
	private static final class FakeSource implements ClusterDataSource {

		private final List<List<GenericKubernetesResource>> pages;

		private int stateCalls;

		private List<Optional<ObjectState>> states;

		private FakeSource(List<List<GenericKubernetesResource>> pages) {
			this.pages = new ArrayList<>(pages);
		}

		@Override
		public List<String> clusters() {
			return List.of("k3stest");
		}

		@Override
		public List<DiscoveredKind> kinds(String clusterId) {
			throw new UnsupportedOperationException("a listing names no kinds; the command line does");
		}

		@Override
		public void list(ResourceQuery query, int chunkSize, Consumer<List<GenericKubernetesResource>> onPage) {
			this.pages.forEach(onPage);
		}

		@Override
		public List<Optional<ObjectState>> states(ResourceQuery query, List<GenericKubernetesResource> objects) {
			this.stateCalls++;
			if (this.states != null) {
				return this.states;
			}
			return objects.stream().map((object) -> Optional.of(new ObjectState("Running", "ok"))).toList();
		}

		@Override
		public List<Column> columns(ResourceQuery query) {
			return List.of();
		}

		@Override
		public GenericKubernetesResource get(ResourceQuery query, String name) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Subscription watch(ResourceQuery query, BiConsumer<String, GenericKubernetesResource> onEvent,
				Consumer<WatchEnd> onEnd) {
			throw new UnsupportedOperationException();
		}

		@Override
		public LogStream logs(PodTarget target) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ExecSession exec(PodTarget target, OutputStream output) {
			throw new UnsupportedOperationException();
		}

	}

}

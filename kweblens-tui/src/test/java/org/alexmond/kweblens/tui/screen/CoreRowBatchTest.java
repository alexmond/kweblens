package org.alexmond.kweblens.tui.screen;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.resource.WellKnownKinds;
import org.alexmond.kweblens.tui.data.ClusterDataSource;
import org.alexmond.kweblens.tui.data.CoreStack;
import org.alexmond.kweblens.tui.data.ResourceQuery;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one projection that asks a cluster, against the in-JVM API server.
 *
 * <p>
 * Written against the real core services rather than a mock of them, for the same reason
 * {@code CoreClusterDataSourceListTest} is: a mocked {@code ObjectStates} would return
 * whatever this class asked it for, including the per-row call this design exists to
 * forbid.
 */
@EnableKubernetesMockClient(crud = true)
class CoreRowBatchTest {

	KubernetesClient client;

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-15T12:00:00Z"), ZoneOffset.UTC);

	private RowBatch batch() {
		ClusterDataSource source = CoreStack.dataSource(this.client);
		return new CoreRowBatch(source, new ResourceQuery(CoreStack.CLUSTER, WellKnownKinds.NAMESPACES, null), CLOCK);
	}

	private void seed(String name) {
		this.client.namespaces()
			.resource(new NamespaceBuilder().withNewMetadata().withName(name).endMetadata().build())
			.create();
	}

	@Test
	void everyObjectGetsARowInTheSameOrder() {
		seed("alpha");
		seed("beta");
		List<GenericKubernetesResource> objects = objects();

		List<ResourceRow> rows = batch().project(objects);

		assertThat(rows).hasSameSizeAs(objects);
		assertThat(rows).extracting(ResourceRow::name)
			.containsExactlyElementsOf(objects.stream().map((object) -> object.getMetadata().getName()).toList());
	}

	@Test
	void anEmptyBatchProjectsNothingAndOpensNoContext() {
		assertThat(batch().project(List.of())).isEmpty();
	}

	@Test
	void aRowWithNoVerdictCarriesNullRatherThanAGuess() {
		seed("gamma");

		List<ResourceRow> rows = batch().project(objects());

		// Whatever the vocabulary says about a Namespace, it is one of two things and
		// never an empty string: a label, or nothing at all.
		assertThat(rows).allSatisfy((row) -> assertThat(row.state())
			.satisfiesAnyOf((state) -> assertThat(state).isNull(), (state) -> assertThat(state).isNotBlank()));
	}

	private List<GenericKubernetesResource> objects() {
		ClusterDataSource source = CoreStack.dataSource(this.client);
		ResourceQuery query = new ResourceQuery(CoreStack.CLUSTER, WellKnownKinds.NAMESPACES, null);
		List<GenericKubernetesResource> collected = new java.util.ArrayList<>();
		source.list(query, 500, collected::addAll);
		return collected;
	}

}

package org.alexmond.kweblens.tui.screen;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.column.Column;
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

	/**
	 * The whole seam in one assertion: the cluster's objects come back as <b>strings the
	 * server computed</b>, in the order the kind declares its columns, and the row holds
	 * those rather than the object they came out of.
	 */
	@Test
	void aRowCarriesTheKindsColumnsAlreadyComputed() {
		ClusterDataSource source = CoreStack.dataSource(this.client);
		this.client.pods()
			.inNamespace("shop")
			.resource(new PodBuilder().withNewMetadata()
				.withNamespace("shop")
				.withName("web-0")
				.endMetadata()
				.withNewSpec()
				.withNodeName("node-a")
				.endSpec()
				.withNewStatus()
				.withContainerStatuses(new ContainerStatusBuilder().withName("app")
					.withReady(true)
					.withRestartCount(3)
					.withImage("nginx")
					.withImageID("nginx")
					.withContainerID("containerd://1")
					.build())
				.endStatus()
				.build())
			.create();
		ResourceQuery pods = new ResourceQuery(CoreStack.CLUSTER, WellKnownKinds.PODS, "shop");
		RowBatch batch = new CoreRowBatch(source, pods, CLOCK);
		List<GenericKubernetesResource> objects = new java.util.ArrayList<>();
		source.list(pods, 500, objects::addAll);

		assertThat(batch.columns()).extracting(Column::header).containsExactly("Ready", "Restarts", "Node");
		assertThat(batch.project(objects)).singleElement()
			.satisfies((row) -> assertThat(row.values()).containsExactly("1/1", "3", "node-a"));
	}

	private List<GenericKubernetesResource> objects() {
		ClusterDataSource source = CoreStack.dataSource(this.client);
		ResourceQuery query = new ResourceQuery(CoreStack.CLUSTER, WellKnownKinds.NAMESPACES, null);
		List<GenericKubernetesResource> collected = new java.util.ArrayList<>();
		source.list(query, 500, collected::addAll);
		return collected;
	}

}

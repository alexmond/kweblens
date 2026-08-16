package org.alexmond.kweblens.tui.data;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.resource.DiscoveredKind;
import org.alexmond.kweblens.tui.kind.KindCatalog;
import org.alexmond.kweblens.tui.kind.KindIndex;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The port really asks the API server what it serves, and the catalog really remembers
 * the answer.
 */
@EnableKubernetesMockClient
class CoreClusterDataSourceDiscoveryTest {

	KubernetesClient client;

	KubernetesMockServer server;

	@Test
	void kindsComesFromTheApiServerAndIncludesACrdGroup() {
		CoreStack.stubDiscovery(this.server);

		var kinds = CoreStack.dataSource(this.client).kinds(CoreStack.CLUSTER);

		assertThat(kinds).extracting(DiscoveredKind::plural).contains("pods", "deployments", "ingressroutes");
		assertThat(kinds).filteredOn((kind) -> "ingressroutes".equals(kind.plural()))
			.singleElement()
			.satisfies((kind) -> assertThat(kind.group()).isEqualTo("traefik.io"))
			.satisfies((kind) -> assertThat(kind.groupVersion()).isEqualTo("traefik.io/v1alpha1"));
	}

	@Test
	void theCatalogDiscoversOncePerClusterAndForgettingMakesItAskAgain() {
		CoreStack.stubDiscovery(this.server);
		KindCatalog catalog = new KindCatalog(CoreStack.dataSource(this.client));

		KindIndex first = catalog.of(CoreStack.CLUSTER);
		assertThat(catalog.of(CoreStack.CLUSTER)).as("a keystroke must not cost thirty round trips").isSameAs(first);

		catalog.forget(CoreStack.CLUSTER);

		assertThat(catalog.of(CoreStack.CLUSTER)).isNotSameAs(first);
	}

	@Test
	void aClusterWhoseDiscoveryFailsYieldsAnIndexThatNamesNothing() {
		this.server.expect().get().withPath("/api/v1").andReturn(503, "").always();
		this.server.expect().get().withPath("/apis").andReturn(503, "").always();

		assertThat(new KindCatalog(CoreStack.dataSource(this.client)).of(CoreStack.CLUSTER).size()).isZero();
	}

}

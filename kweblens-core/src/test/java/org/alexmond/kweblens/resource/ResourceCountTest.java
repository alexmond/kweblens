package org.alexmond.kweblens.resource;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two cheap probes the nav is built on: {@link ResourceService#count}, the number
 * behind a badge, and {@link ResourceService#hasAny}, the yes/no behind a category that
 * is only offered when the cluster has something to put in it.
 *
 * <p>
 * The mock is deliberately <b>not</b> in crud mode here. The fabric8 CRUD dispatcher
 * ignores {@code limit} outright, so a test that seeded three objects and asserted "3"
 * would pass whether or not the request carried the flag — the same trap the apply
 * dry-run test calls out. Every response below is stubbed against an <b>exact query
 * string</b>, so the assertion that {@code limit=1} is sent is the stubbed path itself: a
 * request without it matches nothing.
 *
 * <p>
 * The three branches exist because {@code metadata.remainingItemCount} is best-effort —
 * the API server is permitted to omit it, and a count that silently becomes wrong is
 * worse than one that is slow.
 */
@EnableKubernetesMockClient
class ResourceCountTest {

	KubernetesClient client;

	KubernetesMockServer server;

	private static final ResourceDescriptor CONFIG_MAPS = ResourceDescriptor.coreNamespaced("configmaps", "Config Maps",
			"ConfigMap", "configmaps");

	private static final ResourceDescriptor NODES = ResourceDescriptor.coreCluster("nodes", "Nodes", "Node", "nodes");

	private ResourceService service() {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register("mock", "mock", client);
		return new ResourceService(registry);
	}

	private static String list(String items, String meta) {
		return "{\"apiVersion\":\"v1\",\"kind\":\"ConfigMapList\",\"metadata\":{" + meta + "},\"items\":[" + items
				+ "]}";
	}

	private static String configMap(String name) {
		return "{\"apiVersion\":\"v1\",\"kind\":\"ConfigMap\",\"metadata\":{\"name\":\"" + name
				+ "\",\"namespace\":\"default\"}}";
	}

	@Test
	void countAddsRemainingItemCountToWhatCameBack() {
		server.expect()
			.get()
			.withPath("/api/v1/namespaces/default/configmaps?limit=1")
			.andReturn(200, list(configMap("cm1"), "\"remainingItemCount\":149,\"continue\":\"tok\""))
			.always();

		assertThat(service().count("mock", CONFIG_MAPS, "default")).isEqualTo(150);
	}

	@Test
	void countIsExactWhenThePageIsTheWholeCollection() {
		// No continue token: this page IS everything, so its size is the answer even
		// though remainingItemCount is missing. This is also what a server that ignores
		// `limit` looks like — ComponentStatus on a real cluster does exactly that, and
		// still counts correctly.
		server.expect()
			.get()
			.withPath("/api/v1/namespaces/default/configmaps?limit=1")
			.andReturn(200, list(configMap("cm1") + "," + configMap("cm2") + "," + configMap("cm3"), ""))
			.always();

		assertThat(service().count("mock", CONFIG_MAPS, "default")).isEqualTo(3);
	}

	@Test
	void countFallsBackToAFullListWhenTheServerOmitsRemainingItemCount() {
		// Truncated (there is a continue token) and no remainingItemCount: the only
		// genuinely unknown case. Answering from the page would report 1 of 2.
		server.expect()
			.get()
			.withPath("/api/v1/namespaces/default/configmaps?limit=1")
			.andReturn(200, list(configMap("cm1"), "\"continue\":\"tok\""))
			.always();
		server.expect()
			.get()
			.withPath("/api/v1/namespaces/default/configmaps")
			.andReturn(200, list(configMap("cm1") + "," + configMap("cm2"), ""))
			.always();

		assertThat(service().count("mock", CONFIG_MAPS, "default")).isEqualTo(2);
	}

	@Test
	void countAcrossAllNamespacesAlsoSendsLimitOne() {
		server.expect()
			.get()
			.withPath("/api/v1/configmaps?limit=1")
			.andReturn(200, list(configMap("cm1"), "\"remainingItemCount\":6,\"continue\":\"tok\""))
			.always();

		assertThat(service().count("mock", CONFIG_MAPS, null)).isEqualTo(7);
	}

	@Test
	void countOfAClusterScopedKindIgnoresTheNamespace() {
		server.expect()
			.get()
			.withPath("/api/v1/nodes?limit=1")
			.andReturn(200, list(configMap("node-1"), "\"remainingItemCount\":3,\"continue\":\"tok\""))
			.always();

		assertThat(service().count("mock", NODES, "default")).isEqualTo(4);
	}

	@Test
	void countIsZeroForAnEmptyKind() {
		server.expect()
			.get()
			.withPath("/api/v1/namespaces/default/configmaps?limit=1")
			.andReturn(200, list("", ""))
			.always();

		assertThat(service().count("mock", CONFIG_MAPS, "default")).isZero();
	}

	@Test
	void hasAnyAsksAcrossAllNamespacesForOneItem() {
		// Cluster-wide and limit=1: the nav asks "does this cluster autoscale anything",
		// which is not a per-namespace question and does not need a total.
		server.expect()
			.get()
			.withPath("/api/v1/configmaps?limit=1")
			.andReturn(200, list(configMap("cm1"), "\"remainingItemCount\":149,\"continue\":\"tok\""))
			.always();

		assertThat(service().hasAny("mock", CONFIG_MAPS)).isTrue();
	}

	@Test
	void hasAnyIsFalseForAnEmptyKind() {
		server.expect().get().withPath("/api/v1/configmaps?limit=1").andReturn(200, list("", "")).always();

		assertThat(service().hasAny("mock", CONFIG_MAPS)).isFalse();
	}

	@Test
	void hasAnyNeverFallsBackToAFullList() {
		// The truncated-without-remainingItemCount case, where count() lists everything.
		// A yes/no question must not: only the limit=1 path is stubbed, so a second
		// request would fail the test rather than quietly cost a full list.
		server.expect()
			.get()
			.withPath("/api/v1/configmaps?limit=1")
			.andReturn(200, list(configMap("cm1"), "\"continue\":\"tok\""))
			.always();

		assertThat(service().hasAny("mock", CONFIG_MAPS)).isTrue();
	}

}

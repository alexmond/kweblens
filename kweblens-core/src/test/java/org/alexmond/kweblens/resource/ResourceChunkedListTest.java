package org.alexmond.kweblens.resource;

import java.util.ArrayList;
import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * {@link ResourceService#listRawChunked} — the fetch that bounds a list's heap (#293).
 *
 * <p>
 * The mock is deliberately <b>not</b> in crud mode, for the same reason
 * {@link ResourceCountTest} avoids it and more sharply: the fabric8 CRUD dispatcher
 * ignores {@code limit} outright, so a test that seeded 100 objects and asserted it got
 * 100 back would pass against an implementation that never chunked at all. Every response
 * here is stubbed against an <b>exact query string</b>, so "the request carried
 * {@code limit=2}, then {@code continue=tok2}" is the stubbed path itself — a request
 * without it matches nothing and the test fails.
 *
 * <p>
 * That also means this file is the only place the chunking is verified anywhere: the
 * simulator is the same CRUD mock, so it cannot exercise it either, and a live cluster is
 * not available to the build. The behaviour against a real API server was confirmed by
 * hand against k3s 1.35 (see {@code docs/design/scale-measurements.md}).
 */
@EnableKubernetesMockClient
class ResourceChunkedListTest {

	KubernetesClient client;

	KubernetesMockServer server;

	private static final ResourceDescriptor CONFIG_MAPS = ResourceDescriptor.coreNamespaced("configmaps", "Config Maps",
			"ConfigMap", "configmaps");

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

	private static List<String> namesOf(List<List<GenericKubernetesResource>> chunks) {
		return chunks.stream().flatMap(List::stream).map((r) -> r.getMetadata().getName()).toList();
	}

	private List<List<GenericKubernetesResource>> collect(int chunkSize) {
		List<List<GenericKubernetesResource>> chunks = new ArrayList<>();
		service().listRawChunked("mock", CONFIG_MAPS, "default", chunkSize, chunks::add);
		return chunks;
	}

	@Test
	void followsTheContinueTokenUntilTheCollectionIsExhausted() {
		// fabric8 sorts list-option params (it converts ListOptions through a TreeMap),
		// so
		// `continue` precedes `limit` in the query string of every page after the first.
		server.expect()
			.get()
			.withPath("/api/v1/namespaces/default/configmaps?limit=2")
			.andReturn(200, list(configMap("cm1") + "," + configMap("cm2"), "\"continue\":\"tok2\""))
			.always();
		server.expect()
			.get()
			.withPath("/api/v1/namespaces/default/configmaps?continue=tok2&limit=2")
			.andReturn(200, list(configMap("cm3") + "," + configMap("cm4"), "\"continue\":\"tok3\""))
			.always();
		server.expect()
			.get()
			.withPath("/api/v1/namespaces/default/configmaps?continue=tok3&limit=2")
			.andReturn(200, list(configMap("cm5"), ""))
			.always();

		List<List<GenericKubernetesResource>> chunks = collect(2);

		// Three pages, and — the point of the whole change — the caller saw them one at a
		// time rather than as one collection.
		assertThat(chunks).hasSize(3);
		assertThat(namesOf(chunks)).containsExactly("cm1", "cm2", "cm3", "cm4", "cm5");
	}

	@Test
	void stopsAfterOnePageWhenTheServerIgnoresLimit() {
		// What ComponentStatus does on a real cluster, and what the CRUD mock does for
		// every kind: the whole collection comes back with no continue token. One chunk,
		// every object, no infinite loop.
		server.expect()
			.get()
			.withPath("/api/v1/namespaces/default/configmaps?limit=2")
			.andReturn(200, list(configMap("cm1") + "," + configMap("cm2") + "," + configMap("cm3"), ""))
			.always();

		assertThat(namesOf(collect(2))).containsExactly("cm1", "cm2", "cm3");
	}

	@Test
	void aBlankContinueTokenEndsTheScan() {
		// An API server may answer with an empty string rather than omitting the field.
		// Treating "" as a token would re-request the same page forever.
		server.expect()
			.get()
			.withPath("/api/v1/namespaces/default/configmaps?limit=2")
			.andReturn(200, list(configMap("cm1"), "\"continue\":\"\""))
			.always();

		assertThat(collect(2)).hasSize(1);
	}

	@Test
	void chunkSizeOfZeroSendsNoLimitAtAll() {
		// The escape hatch (kweblens.list.chunk-size=0): exactly the pre-#293 request,
		// for
		// an API server that mishandles continue tokens.
		server.expect()
			.get()
			.withPath("/api/v1/namespaces/default/configmaps")
			.andReturn(200, list(configMap("cm1") + "," + configMap("cm2"), ""))
			.always();

		assertThat(namesOf(collect(0))).containsExactly("cm1", "cm2");
	}

	@Test
	void anExpiredSnapshotIsRaisedAsSomethingTheCallerCanRestart() {
		server.expect()
			.get()
			.withPath("/api/v1/namespaces/default/configmaps?limit=2")
			.andReturn(200, list(configMap("cm1"), "\"continue\":\"tok2\""))
			.always();
		server.expect()
			.get()
			.withPath("/api/v1/namespaces/default/configmaps?continue=tok2&limit=2")
			.andReturn(410, "{\"kind\":\"Status\",\"code\":410,\"reason\":\"Expired\"}")
			.always();

		assertThatExceptionOfType(ListChunkExpiredException.class).isThrownBy(() -> collect(2))
			.withMessageContaining("configmaps");
	}

	@Test
	void a410OnTheFirstPageIsNotAnExpiredSnapshot() {
		// Nothing was pinned yet, so 410 here means something else entirely. Translating
		// it
		// would send the caller into a restart loop over a request that cannot succeed.
		server.expect()
			.get()
			.withPath("/api/v1/namespaces/default/configmaps?limit=2")
			.andReturn(410, "{\"kind\":\"Status\",\"code\":410,\"reason\":\"Gone\"}")
			.always();

		assertThatExceptionOfType(KubernetesClientException.class).isThrownBy(() -> collect(2));
	}

}

package org.alexmond.kweblens.tui.data;

import java.util.ArrayList;
import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.resource.WellKnownKinds;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The port's {@code list} really pages, and its {@code get} really asks for one object.
 *
 * <p>
 * The mock is deliberately <b>not</b> in crud mode. The fabric8 CRUD dispatcher ignores
 * {@code limit} outright, so a test that seeded objects and counted them back would pass
 * against an adapter that never chunked at all — which is exactly the thing this module
 * must not do, because one unchunked list of a big kind was 22.1 MB against a live
 * cluster. Every response here is stubbed against an <b>exact query string</b>, so "the
 * request carried {@code limit=2}, then {@code continue=tok2}" is the stubbed path
 * itself: a request without it matches nothing and the test fails.
 */
@EnableKubernetesMockClient
class CoreClusterDataSourceListTest {

	KubernetesClient client;

	KubernetesMockServer server;

	private static String configMap(String name) {
		return "{\"apiVersion\":\"v1\",\"kind\":\"ConfigMap\",\"metadata\":{\"name\":\"" + name
				+ "\",\"namespace\":\"kube-system\"}}";
	}

	private static String list(String items, String meta) {
		return "{\"apiVersion\":\"v1\",\"kind\":\"ConfigMapList\",\"metadata\":{" + meta + "},\"items\":[" + items
				+ "]}";
	}

	private ResourceQuery query() {
		return new ResourceQuery(CoreStack.CLUSTER, WellKnownKinds.CONFIG_MAPS, "kube-system");
	}

	@Test
	void listFollowsTheContinueTokenAndHandsBackOnePageAtATime() {
		// fabric8 sorts list-option params, so `continue` precedes `limit` after page
		// one.
		this.server.expect()
			.get()
			.withPath("/api/v1/namespaces/kube-system/configmaps?limit=2")
			.andReturn(200, list(configMap("a") + "," + configMap("b"), "\"continue\":\"tok2\""))
			.once();
		this.server.expect()
			.get()
			.withPath("/api/v1/namespaces/kube-system/configmaps?continue=tok2&limit=2")
			.andReturn(200, list(configMap("c"), ""))
			.once();

		List<List<GenericKubernetesResource>> pages = new ArrayList<>();
		CoreStack.dataSource(this.client).list(query(), 2, pages::add);

		assertThat(pages).as("the caller is handed one page per request, not one assembled list").hasSize(2);
		assertThat(pages.stream().flatMap(List::stream).map((r) -> r.getMetadata().getName())).containsExactly("a", "b",
				"c");
	}

	@Test
	void getAsksForOneObjectByName() {
		this.server.expect()
			.get()
			.withPath("/api/v1/namespaces/kube-system/configmaps/a")
			.andReturn(200, configMap("a"))
			.once();

		GenericKubernetesResource found = CoreStack.dataSource(this.client).get(query(), "a");

		assertThat(found).isNotNull();
		assertThat(found.getMetadata().getName()).isEqualTo("a");
	}

	@Test
	void getIsNullWhenTheObjectDoesNotExist() {
		this.server.expect().get().withPath("/api/v1/namespaces/kube-system/configmaps/gone").andReturn(404, "").once();

		assertThat(CoreStack.dataSource(this.client).get(query(), "gone")).isNull();
	}

}

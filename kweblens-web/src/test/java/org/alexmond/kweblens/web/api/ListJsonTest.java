package org.alexmond.kweblens.web.api;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.health.StatusContext;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ListJson} — the list payload, assembled a chunk at a time.
 *
 * <p>
 * The claim this file exists to hold is that <b>nothing about the response changed</b>
 * when #293 stopped building it with one {@code Serialization.asJson} over the whole
 * collection. So every assertion compares against a real {@code Serialization.asJson} of
 * the same objects, byte for byte, rather than against a hand-written expected string
 * that could drift with a fabric8 upgrade — the mapper is the specification here, not
 * this test.
 *
 * <p>
 * The multi-chunk case is the one that could plausibly break: an array written across
 * three server pages has to place its commas as if it had never been split. It is driven
 * through a real {@link ResourceService} against a mock API server stubbed per exact
 * query string, because the CRUD mock ignores {@code limit} and would collapse the case
 * being tested into one page.
 */
@EnableKubernetesMockClient
class ListJsonTest {

	KubernetesClient client;

	KubernetesMockServer server;

	private static final ResourceDescriptor SECRETS = ResourceDescriptor.coreNamespaced("secrets", "Secrets", "Secret",
			"secrets");

	private static final String PATH = "/api/v1/namespaces/app/secrets";

	private ResourceService service() {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register("mock", "mock", client);
		return new ResourceService(registry);
	}

	/**
	 * A Secret with managedFields and values — i.e. carrying everything the list drops.
	 */
	private static String secret(String name) {
		return """
				{"apiVersion":"v1","kind":"Secret","type":"Opaque",
				 "metadata":{"name":"%s","namespace":"app",
				   "managedFields":[{"manager":"kubectl","operation":"Update"}],
				   "annotations":{"note":"a value with \\"quotes\\", a ünïcode ß, and a \\n newline"}},
				 "data":{"password":"c3VwZXJzZWNyZXQ=","username":"YWRtaW4="}}
				""".formatted(name);
	}

	private static String page(String items, String meta) {
		return "{\"apiVersion\":\"v1\",\"kind\":\"SecretList\",\"metadata\":{" + meta + "},\"items\":[" + items + "]}";
	}

	private static GenericKubernetesResource parse(String json) {
		return Serialization.unmarshal(json, GenericKubernetesResource.class);
	}

	/**
	 * What the endpoint used to return: one projection over one collection, serialised
	 * once. Parsed from the same JSON rather than reusing the objects, because
	 * {@link ListProjection} mutates what it is given.
	 */
	private static byte[] theOldWay(String... secrets) {
		List<GenericKubernetesResource> objects = java.util.Arrays.stream(secrets)
			.map(ListJsonTest::parse)
			.collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
		return Serialization.asJson(ListProjection.forList(objects, StatusContext.none()))
			.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void anArrayAssembledFromThreePagesIsByteIdenticalToOneWholeListSerialisation() {
		server.expect()
			.get()
			.withPath(PATH + "?limit=2")
			.andReturn(200, page(secret("s1") + "," + secret("s2"), "\"continue\":\"t2\""))
			.always();
		server.expect()
			.get()
			.withPath(PATH + "?continue=t2&limit=2")
			.andReturn(200, page(secret("s3") + "," + secret("s4"), "\"continue\":\"t3\""))
			.always();
		server.expect().get().withPath(PATH + "?continue=t3&limit=2").andReturn(200, page(secret("s5"), "")).always();

		byte[] chunked = ListJson.chunked(service(), "mock", SECRETS, "app", 2, StatusContext.none());

		assertThat(chunked).isEqualTo(theOldWay(secret("s1"), secret("s2"), secret("s3"), secret("s4"), secret("s5")));
		// ...and the projection is still what makes those bytes small.
		String json = new String(chunked, StandardCharsets.UTF_8);
		assertThat(json).doesNotContain("managedFields").doesNotContain("c3VwZXJzZWNyZXQ=");
		assertThat(json).contains("\"password\":null").contains("s5");
	}

	@Test
	void anEmptyCollectionIsAnEmptyArray() {
		server.expect().get().withPath(PATH + "?limit=2").andReturn(200, page("", "")).always();

		assertThat(ListJson.chunked(service(), "mock", SECRETS, "app", 2, StatusContext.none()))
			.isEqualTo("[]".getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void aCollectionAlreadyInHandSerialisesTheSameWay() {
		// The podsOnNode path: nothing to chunk, but it must produce the same bytes.
		List<GenericKubernetesResource> objects = List.of(parse(secret("s1")), parse(secret("s2")));

		assertThat(ListJson.of(objects, StatusContext.none())).isEqualTo(theOldWay(secret("s1"), secret("s2")));
	}

}

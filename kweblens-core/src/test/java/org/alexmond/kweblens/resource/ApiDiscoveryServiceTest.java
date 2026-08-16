package org.alexmond.kweblens.resource;

import java.util.List;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the cluster says it serves, read from the cluster.
 *
 * <p>
 * The mock is <b>not</b> in CRUD mode: the CRUD dispatcher serves objects, not the
 * catalogue of kinds, so every discovery document here is stubbed against its exact path.
 * That is also what makes the negative cases assertable — a group whose resource list
 * 500s is a real thing (an aggregated API whose backing service is down) and it must cost
 * that group's kinds and nothing else.
 */
@EnableKubernetesMockClient
class ApiDiscoveryServiceTest {

	KubernetesClient client;

	KubernetesMockServer server;

	private static final String CORE = """
			{"kind":"APIResourceList","groupVersion":"v1","resources":[
			 {"name":"pods","singularName":"pod","namespaced":true,"kind":"Pod",\
			"verbs":["list","get","watch"],"shortNames":["po"]},
			 {"name":"pods/log","singularName":"","namespaced":true,"kind":"Pod","verbs":["get"]},
			 {"name":"bindings","singularName":"","namespaced":true,"kind":"Binding","verbs":["create"]},
			 {"name":"nodes","singularName":"node","namespaced":false,"kind":"Node",\
			"verbs":["list","get"],"shortNames":["no"]}]}""";

	private static final String GROUPS = """
			{"kind":"APIGroupList","groups":[
			 {"name":"apps","versions":[{"groupVersion":"apps/v1beta1","version":"v1beta1"},\
			{"groupVersion":"apps/v1","version":"v1"}],\
			"preferredVersion":{"groupVersion":"apps/v1","version":"v1"}},
			 {"name":"broken.example.com","versions":[{"groupVersion":"broken.example.com/v1","version":"v1"}],\
			"preferredVersion":{"groupVersion":"broken.example.com/v1","version":"v1"}}]}""";

	private static final String APPS = """
			{"kind":"APIResourceList","groupVersion":"apps/v1","resources":[
			 {"name":"deployments","singularName":"deployment","namespaced":true,"kind":"Deployment",\
			"verbs":["list","get"],"shortNames":["deploy"]}]}""";

	private ApiDiscoveryService service() {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register("mock", "mock", this.client);
		return new ApiDiscoveryService(registry);
	}

	private void stub() {
		this.server.expect().get().withPath("/api/v1").andReturn(200, CORE).always();
		this.server.expect().get().withPath("/apis").andReturn(200, GROUPS).always();
		this.server.expect().get().withPath("/apis/apps/v1").andReturn(200, APPS).always();
	}

	@Test
	void everyServedKindArrivesWithItsSingularAndShortNames() {
		stub();
		this.server.expect()
			.get()
			.withPath("/apis/broken.example.com/v1")
			.andReturn(200,
					"{\"kind\":\"APIResourceList\",\"groupVersion\":\"broken.example.com/v1\",\"resources\":[]}")
			.always();

		List<DiscoveredKind> kinds = service().kinds("mock");

		assertThat(kinds).extracting(DiscoveredKind::plural).containsExactly("nodes", "pods", "deployments");
		assertThat(kinds).filteredOn((kind) -> "pods".equals(kind.plural()))
			.singleElement()
			.satisfies((kind) -> assertThat(kind.singular()).isEqualTo("pod"))
			.satisfies((kind) -> assertThat(kind.shortNames()).containsExactly("po"))
			.satisfies((kind) -> assertThat(kind.descriptor().namespaced()).isTrue());
		assertThat(kinds).filteredOn((kind) -> "nodes".equals(kind.plural()))
			.singleElement()
			.satisfies((kind) -> assertThat(kind.descriptor().namespaced()).isFalse());
	}

	@Test
	void aSubresourceIsNotAKindAndNeitherIsSomethingThatCannotBeListed() {
		stub();
		this.server.expect()
			.get()
			.withPath("/apis/broken.example.com/v1")
			.andReturn(200,
					"{\"kind\":\"APIResourceList\",\"groupVersion\":\"broken.example.com/v1\",\"resources\":[]}")
			.always();

		List<DiscoveredKind> kinds = service().kinds("mock");

		assertThat(kinds).extracting(DiscoveredKind::plural)
			.as("pods/log cannot be listed and bindings has no list verb")
			.doesNotContain("pods/log", "bindings");
	}

	@Test
	void theGroupsOwnPreferredVersionIsTakenRatherThanTheHighestSortingOne() {
		stub();
		this.server.expect()
			.get()
			.withPath("/apis/broken.example.com/v1")
			.andReturn(200,
					"{\"kind\":\"APIResourceList\",\"groupVersion\":\"broken.example.com/v1\",\"resources\":[]}")
			.always();

		assertThat(service().kinds("mock")).filteredOn((kind) -> "deployments".equals(kind.plural()))
			.singleElement()
			.satisfies((kind) -> assertThat(kind.groupVersion()).isEqualTo("apps/v1"));
	}

	@Test
	void oneGroupThatIsDownCostsItsOwnKindsAndNotTheOtherForty() {
		stub();
		this.server.expect().get().withPath("/apis/broken.example.com/v1").andReturn(503, "").always();

		List<DiscoveredKind> kinds = service().kinds("mock");

		assertThat(kinds).extracting(DiscoveredKind::plural).contains("pods", "deployments");
	}

	@Test
	void aClusterThatIsNotRegisteredDiscoversNothingRatherThanThrowingIntoAKeystroke() {
		assertThat(service().kinds("no-such-cluster")).isEmpty();
	}

}

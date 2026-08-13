package org.alexmond.kweblens.health;

import java.util.List;

import io.fabric8.kubernetes.api.model.EndpointAddress;
import io.fabric8.kubernetes.api.model.EndpointsBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.resource.ResourceService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "The Service exists and nothing answers it" — the breakage this check exists for.
 *
 * <p>
 * The two failure causes must stay distinguishable, because their fixes are different: a
 * wrong selector is a manifest edit, while pods that match but never go ready is a
 * readiness-probe or application problem. Collapsing both into "broken" would throw away
 * the useful half of the answer.
 *
 * <p>
 * Deliberately NOT static: a static mock client shares one API server across the class,
 * so Services seeded by an earlier test would leak into later tallies.
 */
@EnableKubernetesMockClient(crud = true)
class NetworkHealthServiceTest {

	KubernetesClient client;

	private NetworkHealthService service() {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register("mock", "mock", this.client);
		return new NetworkHealthService(registry, new ResourceService(registry));
	}

	private void service(String name, String type) {
		this.client.services()
			.inNamespace("app")
			.resource(new ServiceBuilder().withNewMetadata()
				.withName(name)
				.withNamespace("app")
				.endMetadata()
				.withNewSpec()
				.withType(type)
				.endSpec()
				.build())
			.create();
	}

	private void endpoints(String name, List<EndpointAddress> ready, List<EndpointAddress> notReady) {
		this.client.endpoints()
			.inNamespace("app")
			.resource(new EndpointsBuilder().withNewMetadata()
				.withName(name)
				.withNamespace("app")
				.endMetadata()
				.addNewSubset()
				.withAddresses(ready)
				.withNotReadyAddresses(notReady)
				.endSubset()
				.build())
			.create();
	}

	private EndpointAddress address(String ip) {
		EndpointAddress out = new EndpointAddress();
		out.setIp(ip);
		return out;
	}

	private KindHealth summary() {
		return service().summarise("mock", "app").get(0);
	}

	@Test
	void reportsAServiceWithReadyEndpointsAsHealthy() {
		service("web", "ClusterIP");
		endpoints("web", List.of(address("192.0.2.10")), List.of());
		KindHealth health = summary();
		assertThat(health.total()).isEqualTo(1);
		assertThat(health.ok()).isEqualTo(1);
		assertThat(health.attention()).isZero();
	}

	@Test
	void namesAServiceWithNoEndpointsAtAll() {
		service("orphan", "ClusterIP");
		KindHealth health = summary();
		assertThat(health.attention()).isEqualTo(1);
		assertThat(health.needsAttention()).singleElement().satisfies((item) -> {
			assertThat(item.name()).isEqualTo("orphan");
			assertThat(item.namespace()).isEqualTo("app");
			assertThat(item.reason()).isEqualTo("no endpoints");
		});
	}

	@Test
	void distinguishesPodsThatMatchButAreNotReady() {
		// The selector is right and the workload is deployed — this is a readiness
		// problem, and saying "no endpoints" here would send someone to edit a correct
		// manifest.
		service("starting", "ClusterIP");
		endpoints("starting", List.of(), List.of(address("192.0.2.20"), address("192.0.2.21")));
		KindHealth health = summary();
		assertThat(health.needsAttention()).singleElement()
			.satisfies((item) -> assertThat(item.reason()).isEqualTo("2 pods matched, none ready"));
	}

	@Test
	void treatsExternalNameServicesAsHealthyBecauseTheyHaveNoEndpointsByDesign() {
		// Flagging a correctly configured object is the false alarm that teaches people
		// to
		// ignore the screen.
		service("db", "ExternalName");
		KindHealth health = summary();
		assertThat(health.ok()).isEqualTo(1);
		assertThat(health.attention()).isZero();
	}

	@Test
	void doesNotMatchAnEndpointsObjectFromAnotherNamespace() {
		// Endpoints share their Service's NAME, so the join has to be on namespace/name;
		// keying on name alone would report a broken Service as healthy.
		service("web", "ClusterIP");
		this.client.endpoints()
			.inNamespace("other")
			.resource(new EndpointsBuilder().withNewMetadata()
				.withName("web")
				.withNamespace("other")
				.endMetadata()
				.addNewSubset()
				.withAddresses(address("192.0.2.30"))
				.endSubset()
				.build())
			.create();
		assertThat(summary().attention()).isEqualTo(1);
	}

}

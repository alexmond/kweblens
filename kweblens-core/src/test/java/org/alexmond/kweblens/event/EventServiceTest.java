package org.alexmond.kweblens.event;

import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.resource.ResourceService;

import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class EventServiceTest {

	// Non-static: a fresh CRUD server per test so seeded events don't collide across
	// tests.
	KubernetesClient client;

	private static final ResourceDefinitionContext EVENTS = new ResourceDefinitionContext.Builder().withGroup("")
		.withVersion("v1")
		.withKind("Event")
		.withPlural("events")
		.withNamespaced(true)
		.build();

	private EventService serviceFor(String clusterId) {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register(clusterId, clusterId, client);
		return new EventService(new ResourceService(registry));
	}

	private void createEvent(String name, String type, String reason, String objectKind, String objectName) {
		GenericKubernetesResource event = new GenericKubernetesResourceBuilder().withApiVersion("v1")
			.withKind("Event")
			.withNewMetadata()
			.withName(name)
			.withNamespace("web")
			.endMetadata()
			.addToAdditionalProperties("type", type)
			.addToAdditionalProperties("reason", reason)
			.addToAdditionalProperties("message", reason + " happened")
			.addToAdditionalProperties("involvedObject", Map.of("kind", objectKind, "name", objectName))
			.addToAdditionalProperties("lastTimestamp", "2024-06-01T12:00:00Z")
			.build();
		client.genericKubernetesResources(EVENTS).inNamespace("web").resource(event).create();
	}

	@Test
	void listsEventsAsSummaries() {
		createEvent("evt1", "Warning", "BackOff", "Pod", "nginx");

		EventService service = serviceFor("mock");

		assertThat(service.list("mock", "web")).singleElement().satisfies((e) -> {
			assertThat(e.type()).isEqualTo("Warning");
			assertThat(e.reason()).isEqualTo("BackOff");
			assertThat(e.object()).isEqualTo("Pod/nginx");
			assertThat(e.namespace()).isEqualTo("web");
		});
	}

	@Test
	void filtersEventsByInvolvedObject() {
		createEvent("evt1", "Normal", "Started", "Pod", "nginx");
		createEvent("evt2", "Normal", "Scheduled", "Pod", "redis");

		EventService service = serviceFor("mock");

		assertThat(service.listForObject("mock", "web", "Pod", "redis")).singleElement()
			.satisfies((e) -> assertThat(e.reason()).isEqualTo("Scheduled"));
	}

}

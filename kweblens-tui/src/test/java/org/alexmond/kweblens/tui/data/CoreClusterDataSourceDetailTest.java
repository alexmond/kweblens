package org.alexmond.kweblens.tui.data;

import java.util.Map;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.event.EventSummary;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.WellKnownKinds;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The detail port against the in-JVM API server — <b>and the place the ticket's second
 * "done when" is actually checked</b>: that a Service's pane carries {@code endpoints},
 * {@code routedBy} and {@code selectedPods}, and a Pod's carries {@code serviceAccount},
 * {@code ownedBy} and {@code disruptionBudgets}, <em>from {@code RelationService}</em>.
 *
 * <p>
 * A fake source could not test this at all: the whole property is that the joins are the
 * server's, so the only meaningful assertion runs the real {@code RelationService} over a
 * real API server. Hermetic, as every test in this module is.
 *
 * <p>
 * <b>One correction to the ticket.</b> It lists {@code mountedBy} among a Pod's
 * relations. It is not one: {@code RelationService} registers {@code mountedBy} for the
 * CONSUMABLE kinds — Secret, ConfigMap, PersistentVolumeClaim — and it answers "which
 * pods use this", the reverse direction, because a pod already names what it consumes and
 * the consumed object names nothing. So it is asserted here on a ConfigMap, where it
 * exists.
 */
@EnableKubernetesMockClient(crud = true)
class CoreClusterDataSourceDetailTest {

	private static final ResourceDefinitionContext EVENTS = new ResourceDefinitionContext.Builder().withGroup("")
		.withVersion("v1")
		.withKind("Event")
		.withPlural("events")
		.withNamespaced(true)
		.build();

	private static final ResourceDescriptor SERVICES = WellKnownKinds.SERVICES;

	private static final ResourceDescriptor CONFIG_MAPS = WellKnownKinds.CONFIG_MAPS;

	KubernetesClient client;

	private ResourceQuery query(ResourceDescriptor kind) {
		return new ResourceQuery(CoreStack.CLUSTER, kind, "default");
	}

	@Test
	void aServiceCarriesTheThreeRelationsTheServerComputesForOne() {
		this.client.services()
			.inNamespace("default")
			.resource(new ServiceBuilder().withNewMetadata()
				.withName("web")
				.withNamespace("default")
				.endMetadata()
				.withNewSpec()
				.withSelector(Map.of("app", "web"))
				.endSpec()
				.build())
			.create();

		ObjectDetail detail = CoreStack.dataSource(this.client).detail(query(SERVICES), "web");

		assertThat(detail.available()).isTrue();
		assertThat(detail.relations()).containsKeys("endpoints", "routedBy", "selectedPods");
		assertThat(detail.yaml()).contains("kind: \"Service\"").contains("name: \"web\"");
	}

	@Test
	void aPodCarriesTheRelationsTheServerComputesForOne() {
		this.client.pods()
			.inNamespace("default")
			.resource(new PodBuilder().withNewMetadata()
				.withName("web-abc")
				.withNamespace("default")
				.addNewOwnerReference()
				.withApiVersion("apps/v1")
				.withKind("ReplicaSet")
				.withName("web")
				.withUid("uid-1")
				.endOwnerReference()
				.endMetadata()
				.withNewSpec()
				.withServiceAccountName("web-runner")
				.endSpec()
				.build())
			.create();

		ObjectDetail detail = CoreStack.dataSource(this.client).detail(query(WellKnownKinds.PODS), "web-abc");

		assertThat(detail.relations()).containsKeys("serviceAccount", "disruptionBudgets", "ownedBy");
		assertThat(detail.relations()).as("mountedBy belongs to what a pod CONSUMES, not to the pod")
			.doesNotContainKey("mountedBy");
	}

	@Test
	void aConfigMapCarriesMountedBy_whichIsTheKindItActuallyBelongsTo() {
		this.client.configMaps()
			.inNamespace("default")
			.resource(new ConfigMapBuilder().withNewMetadata()
				.withName("settings")
				.withNamespace("default")
				.endMetadata()
				.addToData("key", "value")
				.build())
			.create();

		ObjectDetail detail = CoreStack.dataSource(this.client).detail(query(CONFIG_MAPS), "settings");

		assertThat(detail.relations()).containsKey("mountedBy");
	}

	@Test
	void theObjectsOwnEventsComeBackAndOtherObjectsDoNot() {
		this.client.pods()
			.inNamespace("default")
			.resource(
					new PodBuilder().withNewMetadata().withName("noisy").withNamespace("default").endMetadata().build())
			.create();
		seedEvent("evt-mine", "noisy", "BackOff");
		seedEvent("evt-theirs", "quiet", "Scheduled");

		ObjectDetail detail = CoreStack.dataSource(this.client).detail(query(WellKnownKinds.PODS), "noisy");

		assertThat(detail.events()).extracting(EventSummary::reason).containsExactly("BackOff");
	}

	/**
	 * A row is listed and the object is deleted before the key is pressed. That is an
	 * ordinary outcome and it is reported in words — an empty pane would assert that an
	 * object with no relations and no events still exists.
	 */
	@Test
	void anObjectThatIsGoneIsASentence_notAnEmptyPane() {
		ObjectDetail detail = CoreStack.dataSource(this.client).detail(query(WellKnownKinds.PODS), "never-existed");

		assertThat(detail.available()).isFalse();
		assertThat(detail.error()).contains("never-existed").contains("no Pod");
		assertThat(detail.relations()).isEmpty();
		assertThat(detail.events()).isEmpty();
	}

	/**
	 * A kind with no relations at all costs the cluster nothing — {@code RelationService}
	 * registers a join only where the kind can have one — and the pane still has YAML and
	 * events to draw.
	 */
	@Test
	void aKindWithNoRelationsStillHasYamlAndEvents() {
		this.client.namespaces()
			.resource(new io.fabric8.kubernetes.api.model.NamespaceBuilder().withNewMetadata()
				.withName("team-a")
				.endMetadata()
				.build())
			.create();

		ObjectDetail detail = CoreStack.dataSource(this.client)
			.detail(new ResourceQuery(CoreStack.CLUSTER, WellKnownKinds.NAMESPACES, null), "team-a");

		assertThat(detail.available()).isTrue();
		assertThat(detail.relations()).isEmpty();
		assertThat(detail.yaml()).contains("kind: \"Namespace\"");
	}

	private void seedEvent(String name, String involved, String reason) {
		GenericKubernetesResource event = new GenericKubernetesResourceBuilder().withApiVersion("v1")
			.withKind("Event")
			.withNewMetadata()
			.withName(name)
			.withNamespace("default")
			.endMetadata()
			.addToAdditionalProperties("type", "Warning")
			.addToAdditionalProperties("reason", reason)
			.addToAdditionalProperties("message", reason + " happened")
			.addToAdditionalProperties("involvedObject", Map.of("kind", "Pod", "name", involved))
			.addToAdditionalProperties("lastTimestamp", "2026-08-01T12:00:00Z")
			.build();
		this.client.genericKubernetesResources(EVENTS).inNamespace("default").resource(event).create();
	}

}

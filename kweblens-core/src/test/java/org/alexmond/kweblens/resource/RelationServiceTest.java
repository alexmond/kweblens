package org.alexmond.kweblens.resource;

import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.EndpointsBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relational joins a detail view needs but cannot express from one object.
 *
 * <p>
 * Deliberately NOT static: a static mock client shares one API server across the whole
 * class, so pods seeded by an earlier test survive into later ones and the reverse
 * "mountedBy" scan — which lists a whole namespace — becomes order-dependent.
 */
@EnableKubernetesMockClient(crud = true)
class RelationServiceTest {

	KubernetesClient client;

	private static final ResourceDescriptor SERVICES = ResourceDescriptor.coreNamespaced("services", "Services",
			"Service", "services");

	private static final ResourceDescriptor SECRETS = ResourceDescriptor.coreNamespaced("secrets", "Secrets", "Secret",
			"secrets");

	private static final ResourceDescriptor CONFIG_MAPS = ResourceDescriptor.coreNamespaced("configmaps", "Config Maps",
			"ConfigMap", "configmaps");

	private RelationService service() {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register("mock", "mock", this.client);
		return new RelationService(registry);
	}

	/** Round-trip a typed object into the generic form the service actually receives. */
	private GenericKubernetesResource generic(Object typed) {
		return Serialization.unmarshal(Serialization.asJson(typed), GenericKubernetesResource.class);
	}

	@Test
	void resolvesTheEndpointsBackingAService() {
		this.client.endpoints()
			.inNamespace("app")
			.resource(new EndpointsBuilder().withNewMetadata()
				.withName("web")
				.withNamespace("app")
				.endMetadata()
				.addNewSubset()
				.addNewAddress()
				.withIp("192.0.2.11")
				.endAddress()
				.endSubset()
				.build())
			.create();
		GenericKubernetesResource svc = generic(new ServiceBuilder().withNewMetadata()
			.withName("web")
			.withNamespace("app")
			.endMetadata()
			.withNewSpec()
			.withSelector(Map.of("app", "web"))
			.endSpec()
			.build());

		Map<String, Relation> relations = service().relationsFor("mock", SERVICES, svc);

		assertThat(relations).containsKeys("endpoints", "selectedPods");
		assertThat(relations.get("endpoints").items()).hasSize(1);
		assertThat(relations.get("endpoints").error()).isNull();
	}

	@Test
	void reportsAnEmptyEndpointsListRatherThanAnErrorWhenNothingBacksTheService() {
		// "Nothing is backing this Service" is a real answer and the most common Service
		// misconfiguration — it must not look like a failure.
		GenericKubernetesResource svc = generic(new ServiceBuilder().withNewMetadata()
			.withName("orphan")
			.withNamespace("app")
			.endMetadata()
			.withNewSpec()
			.withSelector(Map.of("app", "nothing"))
			.endSpec()
			.build());

		Relation endpoints = service().relationsFor("mock", SERVICES, svc).get("endpoints");

		assertThat(endpoints.items()).isEmpty();
		assertThat(endpoints.error()).isNull();
		assertThat(endpoints.notPermitted()).isFalse();
	}

	@Test
	void resolvesPodsMatchedByAFlatServiceSelector() {
		this.client.pods()
			.inNamespace("app")
			.resource(new PodBuilder().withNewMetadata()
				.withName("web-1")
				.withNamespace("app")
				.withLabels(Map.of("app", "web"))
				.endMetadata()
				.build())
			.create();
		this.client.pods()
			.inNamespace("app")
			.resource(new PodBuilder().withNewMetadata()
				.withName("other-1")
				.withNamespace("app")
				.withLabels(Map.of("app", "other"))
				.endMetadata()
				.build())
			.create();
		GenericKubernetesResource svc = generic(new ServiceBuilder().withNewMetadata()
			.withName("web")
			.withNamespace("app")
			.endMetadata()
			.withNewSpec()
			.withSelector(Map.of("app", "web"))
			.endSpec()
			.build());

		Relation pods = service().relationsFor("mock", SERVICES, svc).get("selectedPods");

		// Server-side label selector: the non-matching pod must not come back.
		assertThat(pods.items()).hasSize(1);
	}

	@Test
	void findsPodsMountingASecretAsAVolume() {
		this.client.secrets()
			.inNamespace("app")
			.resource(new SecretBuilder().withNewMetadata()
				.withName("db-creds")
				.withNamespace("app")
				.endMetadata()
				.build())
			.create();
		this.client.pods()
			.inNamespace("app")
			.resource(new PodBuilder().withNewMetadata()
				.withName("mounts-it")
				.withNamespace("app")
				.endMetadata()
				.withNewSpec()
				.addNewVolume()
				.withName("creds")
				.withNewSecret()
				.withSecretName("db-creds")
				.endSecret()
				.endVolume()
				.addNewContainer()
				.withName("c")
				.endContainer()
				.endSpec()
				.build())
			.create();
		this.client.pods()
			.inNamespace("app")
			.resource(new PodBuilder().withNewMetadata()
				.withName("unrelated")
				.withNamespace("app")
				.endMetadata()
				.withNewSpec()
				.addNewContainer()
				.withName("c")
				.endContainer()
				.endSpec()
				.build())
			.create();
		GenericKubernetesResource secret = generic(
				new SecretBuilder().withNewMetadata().withName("db-creds").withNamespace("app").endMetadata().build());

		Relation mountedBy = service().relationsFor("mock", SECRETS, secret).get("mountedBy");

		assertThat(mountedBy.items()).hasSize(1);
		assertThat(Serialization.asJson(mountedBy.items().get(0))).contains("mounts-it");
	}

	@Test
	void findsPodsReferencingAConfigMapViaEnvFromAndValueFrom() {
		// The three reference styles are easy to miss individually; a ConfigMap "nothing
		// uses"
		// that is actually consumed via envFrom is exactly the wrong answer to give.
		this.client.pods()
			.inNamespace("app")
			.resource(new PodBuilder().withNewMetadata()
				.withName("env-from")
				.withNamespace("app")
				.endMetadata()
				.withNewSpec()
				.addNewContainer()
				.withName("c")
				.addNewEnvFrom()
				.withNewConfigMapRef()
				.withName("app-config")
				.endConfigMapRef()
				.endEnvFrom()
				.endContainer()
				.endSpec()
				.build())
			.create();
		this.client.pods()
			.inNamespace("app")
			.resource(new PodBuilder().withNewMetadata()
				.withName("value-from-init")
				.withNamespace("app")
				.endMetadata()
				.withNewSpec()
				.addNewInitContainer()
				.withName("i")
				.addNewEnv()
				.withName("K")
				.withNewValueFrom()
				.withNewConfigMapKeyRef()
				.withName("app-config")
				.withKey("k")
				.endConfigMapKeyRef()
				.endValueFrom()
				.endEnv()
				.endInitContainer()
				.addNewContainer()
				.withName("c")
				.endContainer()
				.endSpec()
				.build())
			.create();
		GenericKubernetesResource cm = generic(new ConfigMapBuilder().withNewMetadata()
			.withName("app-config")
			.withNamespace("app")
			.endMetadata()
			.build());

		Relation mountedBy = service().relationsFor("mock", CONFIG_MAPS, cm).get("mountedBy");

		// Both styles found, including the reference inside an INIT container.
		assertThat(mountedBy.items()).hasSize(2);
	}

	@Test
	void offersNoRelationsForAKindThatHasNone() {
		// A kind with no relations must cost nothing — no speculative lookups.
		GenericKubernetesResource cm = generic(
				new ConfigMapBuilder().withNewMetadata().withName("lonely").withNamespace("app").endMetadata().build());
		ResourceDescriptor nodes = WellKnownKinds.NODES;
		GenericKubernetesResource clusterScoped = generic(
				new ConfigMapBuilder().withNewMetadata().withName("no-namespace").endMetadata().build());

		// ConfigMap does get mountedBy; a cluster-scoped object with no namespace gets
		// nothing.
		assertThat(service().relationsFor("mock", CONFIG_MAPS, cm)).containsOnlyKeys("mountedBy");
		assertThat(service().relationsFor("mock", nodes, clusterScoped)).isEmpty();
	}

	@Test
	void stripsManagedFieldsFromRelatedObjects() {
		// Relation items exist to be displayed; nothing reads their field-management
		// bookkeeping, and on a real Service it was 35% of the whole detail payload —
		// which
		// then multiplies by the 100-item cap.
		this.client.pods()
			.inNamespace("app")
			.resource(new PodBuilder().withNewMetadata()
				.withName("noisy")
				.withNamespace("app")
				.withLabels(Map.of("app", "web"))
				.addNewManagedField()
				.withManager("kubectl")
				.withOperation("Apply")
				.endManagedField()
				.endMetadata()
				.build())
			.create();
		GenericKubernetesResource svc = generic(new ServiceBuilder().withNewMetadata()
			.withName("web")
			.withNamespace("app")
			.endMetadata()
			.withNewSpec()
			.withSelector(Map.of("app", "web"))
			.endSpec()
			.build());

		Relation pods = service().relationsFor("mock", SERVICES, svc).get("selectedPods");

		assertThat(pods.items()).hasSize(1);
		assertThat(Serialization.asJson(pods.items().get(0))).doesNotContain("managedFields");
	}

	@Test
	void relationHelpersClassifyFailuresRatherThanReturningBareEmptyLists() {
		// The contract the UI depends on: an error is distinguishable from "there are
		// none",
		// and a 403 is distinguishable from a malfunction.
		assertThat(Relation.of(List.of()).error()).isNull();
		assertThat(Relation.failed("boom").error()).isEqualTo("boom");
		assertThat(Relation.failed("boom").notPermitted()).isFalse();
		assertThat(Relation.notPermitted("nope").notPermitted()).isTrue();
		assertThat(Relation.of(List.of(), true).truncated()).isTrue();
	}

}

package org.alexmond.kweblens.resource;

import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudgetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The controller-side relations added beyond the original three: what created an object,
 * what it created, what scales it, what protects it, and what routes to it.
 *
 * <p>
 * Deliberately NOT static, for the same reason as {@link RelationServiceTest}: a static
 * mock client shares one API server across the class, and several of these joins list a
 * whole namespace, so seeded objects would leak between tests and make them
 * order-dependent.
 */
@EnableKubernetesMockClient(crud = true)
class RelationBreadthTest {

	KubernetesClient client;

	private static final ResourceDescriptor PODS = WellKnownKinds.PODS;

	private static final ResourceDescriptor DEPLOYMENTS = ResourceDescriptor.namespaced("deployments", "Deployments",
			"Deployment", "apps", "v1", "deployments");

	private static final ResourceDescriptor SERVICES = ResourceDescriptor.coreNamespaced("services", "Services",
			"Service", "services");

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
	void walksThePodToReplicaSetToDeploymentChain() {
		// The whole point: the immediate owner is a ReplicaSet nobody edits, and the
		// object
		// the operator actually needs is one level further up.
		this.client.apps()
			.deployments()
			.inNamespace("app")
			.resource(new DeploymentBuilder().withNewMetadata()
				.withName("web")
				.withNamespace("app")
				.endMetadata()
				.build())
			.create();
		this.client.apps()
			.replicaSets()
			.inNamespace("app")
			.resource(new ReplicaSetBuilder().withNewMetadata()
				.withName("web-abc")
				.withNamespace("app")
				.addNewOwnerReference()
				.withApiVersion("apps/v1")
				.withKind("Deployment")
				.withName("web")
				.withController(true)
				.endOwnerReference()
				.endMetadata()
				.build())
			.create();
		GenericKubernetesResource pod = generic(new PodBuilder().withNewMetadata()
			.withName("web-abc-1")
			.withNamespace("app")
			.addNewOwnerReference()
			.withApiVersion("apps/v1")
			.withKind("ReplicaSet")
			.withName("web-abc")
			.withController(true)
			.endOwnerReference()
			.endMetadata()
			.build());

		Relation owners = service().relationsFor("mock", PODS, pod).get("ownedBy");

		assertThat(owners.error()).isNull();
		assertThat(owners.items()).hasSize(2);
		assertThat(Serialization.asJson(owners.items().get(0))).contains("web-abc");
		assertThat(Serialization.asJson(owners.items().get(1))).contains("\"Deployment\"");
	}

	@Test
	void offersNoOwnerRelationForAnObjectNothingCreated() {
		// A bare pod is a real and diagnostic state — nothing will reschedule it. The
		// relation must be absent rather than an empty table, so it costs no API call.
		GenericKubernetesResource pod = generic(
				new PodBuilder().withNewMetadata().withName("bare").withNamespace("app").endMetadata().build());

		assertThat(service().relationsFor("mock", PODS, pod)).doesNotContainKey("ownedBy");
	}

	@Test
	void stopsAtADanglingOwnerReferenceRatherThanFailing() {
		// --cascade=orphan leaves this behind. It is a state, not a malfunction.
		GenericKubernetesResource pod = generic(new PodBuilder().withNewMetadata()
			.withName("orphan")
			.withNamespace("app")
			.addNewOwnerReference()
			.withApiVersion("apps/v1")
			.withKind("ReplicaSet")
			.withName("gone")
			.withController(true)
			.endOwnerReference()
			.endMetadata()
			.build());

		Relation owners = service().relationsFor("mock", PODS, pod).get("ownedBy");

		assertThat(owners.items()).isEmpty();
		assertThat(owners.error()).isNull();
	}

	@Test
	void listsTheReplicaSetsADeploymentOwnsAndNotAStrangersWithTheSameLabels() {
		seedReplicaSet("web-1", "web");
		seedReplicaSet("web-2", "web");
		seedReplicaSet("impostor", "something-else");
		GenericKubernetesResource deployment = generic(new DeploymentBuilder().withNewMetadata()
			.withName("web")
			.withNamespace("app")
			.endMetadata()
			.withNewSpec()
			.withNewSelector()
			.withMatchLabels(Map.of("app", "web"))
			.endSelector()
			.endSpec()
			.build());

		Relation sets = service().relationsFor("mock", DEPLOYMENTS, deployment).get("replicaSets");

		// The selector is not proof of ownership — two Deployments can share labels.
		assertThat(sets.items()).hasSize(2);
	}

	private void seedReplicaSet(String name, String owner) {
		this.client.apps()
			.replicaSets()
			.inNamespace("app")
			.resource(new ReplicaSetBuilder().withNewMetadata()
				.withName(name)
				.withNamespace("app")
				.withLabels(Map.of("app", "web"))
				.addNewOwnerReference()
				.withApiVersion("apps/v1")
				.withKind("Deployment")
				.withName(owner)
				.withController(true)
				.endOwnerReference()
				.endMetadata()
				.build())
			.create();
	}

	@Test
	void findsTheAutoscalerThatKeepsResettingTheReplicaCount() {
		this.client.autoscaling()
			.v2()
			.horizontalPodAutoscalers()
			.inNamespace("app")
			.resource(new HorizontalPodAutoscalerBuilder().withNewMetadata()
				.withName("web-hpa")
				.withNamespace("app")
				.endMetadata()
				.withNewSpec()
				.withNewScaleTargetRef()
				.withApiVersion("apps/v1")
				.withKind("Deployment")
				.withName("web")
				.endScaleTargetRef()
				.withMaxReplicas(10)
				.endSpec()
				.build())
			.create();
		this.client.autoscaling()
			.v2()
			.horizontalPodAutoscalers()
			.inNamespace("app")
			.resource(new HorizontalPodAutoscalerBuilder().withNewMetadata()
				.withName("other-hpa")
				.withNamespace("app")
				.endMetadata()
				.withNewSpec()
				.withNewScaleTargetRef()
				.withApiVersion("apps/v1")
				.withKind("Deployment")
				.withName("other")
				.endScaleTargetRef()
				.withMaxReplicas(3)
				.endSpec()
				.build())
			.create();
		GenericKubernetesResource deployment = generic(
				new DeploymentBuilder().withNewMetadata().withName("web").withNamespace("app").endMetadata().build());

		Relation hpas = service().relationsFor("mock", DEPLOYMENTS, deployment).get("autoscaledBy");

		assertThat(hpas.items()).hasSize(1);
		assertThat(Serialization.asJson(hpas.items().get(0))).contains("web-hpa");
	}

	@Test
	void matchesADisruptionBudgetAgainstThePodTemplateLabelsNotTheWorkloadsOwn() {
		// The trap: a Deployment's own labels and its template's labels routinely differ,
		// and reading the wrong one reports a workload as unprotected when it is not.
		seedBudget("guards-pods", Map.of("app", "web"));
		seedBudget("guards-nothing-here", Map.of("app", "other"));
		GenericKubernetesResource deployment = generic(new DeploymentBuilder().withNewMetadata()
			.withName("web")
			.withNamespace("app")
			.withLabels(Map.of("app", "other"))
			.endMetadata()
			.withNewSpec()
			.withNewTemplate()
			.withNewMetadata()
			.withLabels(Map.of("app", "web"))
			.endMetadata()
			.endTemplate()
			.endSpec()
			.build());

		Relation budgets = service().relationsFor("mock", DEPLOYMENTS, deployment).get("disruptionBudgets");

		assertThat(budgets.items()).hasSize(1);
		assertThat(Serialization.asJson(budgets.items().get(0))).contains("guards-pods");
	}

	@Test
	void treatsAnEmptyDisruptionSelectorAsCoveringEverything() {
		// The API's own rule. Getting it backwards would hide the one budget that blocks
		// every drain in the namespace.
		seedBudget("namespace-wide", Map.of());
		GenericKubernetesResource pod = generic(new PodBuilder().withNewMetadata()
			.withName("web-1")
			.withNamespace("app")
			.withLabels(Map.of("app", "web"))
			.endMetadata()
			.build());

		Relation budgets = service().relationsFor("mock", PODS, pod).get("disruptionBudgets");

		assertThat(budgets.items()).hasSize(1);
	}

	private void seedBudget(String name, Map<String, String> selector) {
		this.client.policy()
			.v1()
			.podDisruptionBudget()
			.inNamespace("app")
			.resource(new PodDisruptionBudgetBuilder().withNewMetadata()
				.withName(name)
				.withNamespace("app")
				.endMetadata()
				.withNewSpec()
				.withNewSelector()
				.withMatchLabels(selector)
				.endSelector()
				.endSpec()
				.build())
			.create();
	}

	@Test
	void findsTheIngressThatRoutesToAServiceIncludingViaTheDefaultBackend() {
		this.client.network()
			.v1()
			.ingresses()
			.inNamespace("app")
			.resource(new IngressBuilder().withNewMetadata()
				.withName("by-rule")
				.withNamespace("app")
				.endMetadata()
				.withNewSpec()
				.addNewRule()
				.withNewHttp()
				.addNewPath()
				.withPath("/")
				.withPathType("Prefix")
				.withNewBackend()
				.withNewService()
				.withName("web")
				.endService()
				.endBackend()
				.endPath()
				.endHttp()
				.endRule()
				.endSpec()
				.build())
			.create();
		this.client.network()
			.v1()
			.ingresses()
			.inNamespace("app")
			.resource(new IngressBuilder().withNewMetadata()
				.withName("by-default-backend")
				.withNamespace("app")
				.endMetadata()
				.withNewSpec()
				.withNewDefaultBackend()
				.withNewService()
				.withName("web")
				.endService()
				.endDefaultBackend()
				.endSpec()
				.build())
			.create();
		this.client.network()
			.v1()
			.ingresses()
			.inNamespace("app")
			.resource(new IngressBuilder().withNewMetadata()
				.withName("elsewhere")
				.withNamespace("app")
				.endMetadata()
				.withNewSpec()
				.withNewDefaultBackend()
				.withNewService()
				.withName("other")
				.endService()
				.endDefaultBackend()
				.endSpec()
				.build())
			.create();
		GenericKubernetesResource svc = generic(
				new ServiceBuilder().withNewMetadata().withName("web").withNamespace("app").endMetadata().build());

		Relation routes = service().relationsFor("mock", SERVICES, svc).get("routedBy");

		assertThat(routes.items()).hasSize(2);
	}

}

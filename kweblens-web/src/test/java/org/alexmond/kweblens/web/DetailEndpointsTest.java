package org.alexmond.kweblens.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.EndpointsBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.PersistentVolumeBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudgetBuilder;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.resource.Relation;
import org.alexmond.kweblens.resource.RelationService;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.resource.WellKnownKinds;
import org.alexmond.kweblens.web.api.DetailApiController;
import org.alexmond.kweblens.web.api.ResourceActionApiController;
import org.alexmond.kweblens.web.nav.ClusterNavService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of {@code GET .../detail/{resourceId}/{namespace}/{name}} — the
 * envelope, the wire encoding of a {@code Relation}, the error statuses, and that all
 * twelve relations are reachable and correctly ADDRESSED through the route.
 *
 * <p>
 * The joins themselves are covered in core ({@code RelationServiceTest},
 * {@code RelationBreadthTest}, {@code RelationStorageAccessTest}); what was untested
 * until now is everything between the URL and the bytes: resolving {@code resourceId} to
 * a descriptor, binding namespace/name, and fabric8's serializer's treatment of the
 * envelope. GH#148, GH#143 and roadmap D3 all build on those bytes.
 *
 * <p>
 * Every relation here is seeded WITH A DECOY — an object that a join filtering on the
 * wrong thing would pick up — so an assertion cannot pass just because something came
 * back. The PodDisruptionBudget case is the sharpest: the Deployment's own labels and its
 * pod template's labels are deliberately different and each has a budget, so a join
 * reading the wrong one returns exactly one item, of the wrong name.
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
@EnableKubernetesMockClient(crud = true)
class DetailEndpointsTest {

	/**
	 * Every relation {@code RelationService} can register. The roadmap, the controller
	 * and the SPA all quote "twelve"; this set is what keeps that number honest — a
	 * thirteenth relation with no test fails
	 * {@link #everyRelationIsReachableThroughTheRoute()} rather than shipping unpinned.
	 */
	private static final Set<String> ALL_RELATIONS = Set.of("endpoints", "routedBy", "selectedPods", "mountedBy",
			"ownedBy", "replicaSets", "autoscaledBy", "disruptionBudgets", "boundVolume", "boundClaim",
			"serviceAccount", "grantedBy");

	private static final String NS = "ns1";

	private static final String WEB = "web";

	private static final String DB = "db";

	private static final String APP = "app";

	private static final String DEPLOYMENT = "Deployment";

	KubernetesClient client;

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private ClusterRegistry registry;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).build();
		this.registry.register("test", "Test cluster", this.client);
		seedWorkloads();
		seedNetwork();
		seedConfigAndStorage();
		seedAccess();
	}

	// --- fixtures -----------------------------------------------------------------

	private void seedWorkloads() {
		// Own labels app=db, POD TEMPLATE labels app=web: a budget matched against the
		// wrong one of those two picks the decoy budget, which is the point.
		this.client.apps()
			.deployments()
			.inNamespace(NS)
			.resource(new DeploymentBuilder().withNewMetadata()
				.withName("dep")
				.withNamespace(NS)
				.withLabels(Map.of(APP, DB))
				.endMetadata()
				.withNewSpec()
				.withNewSelector()
				.withMatchLabels(Map.of(APP, WEB))
				.endSelector()
				.withNewTemplate()
				.withNewMetadata()
				.withLabels(Map.of(APP, WEB))
				.endMetadata()
				.endTemplate()
				.endSpec()
				.build())
			.create();
		seedReplicaSet("dep-abc", "dep");
		// Same labels, a different owner — the selector alone is not proof of ownership.
		seedReplicaSet("rogue", "someone-else");
		seedAutoscaler("hpa-dep", "dep");
		seedAutoscaler("hpa-other", "someone-else");
		seedBudget("pdb-web", WEB);
		seedBudget("pdb-db", DB);
		seedPod("web-1", WEB, true);
		seedPod("db-1", DB, false);
	}

	private void seedReplicaSet(String name, String owner) {
		this.client.apps()
			.replicaSets()
			.inNamespace(NS)
			.resource(new ReplicaSetBuilder().withNewMetadata()
				.withName(name)
				.withNamespace(NS)
				.withLabels(Map.of(APP, WEB))
				.addNewOwnerReference()
				.withKind(DEPLOYMENT)
				.withApiVersion("apps/v1")
				.withName(owner)
				.withController(true)
				.endOwnerReference()
				.endMetadata()
				.build())
			.create();
	}

	private void seedAutoscaler(String name, String target) {
		this.client.autoscaling()
			.v2()
			.horizontalPodAutoscalers()
			.inNamespace(NS)
			.resource(new HorizontalPodAutoscalerBuilder().withNewMetadata()
				.withName(name)
				.withNamespace(NS)
				.endMetadata()
				.withNewSpec()
				.withNewScaleTargetRef()
				.withApiVersion("apps/v1")
				.withKind(DEPLOYMENT)
				.withName(target)
				.endScaleTargetRef()
				.withMaxReplicas(5)
				.endSpec()
				.build())
			.create();
	}

	private void seedBudget(String name, String app) {
		this.client.policy()
			.v1()
			.podDisruptionBudget()
			.inNamespace(NS)
			.resource(new PodDisruptionBudgetBuilder().withNewMetadata()
				.withName(name)
				.withNamespace(NS)
				.endMetadata()
				.withNewSpec()
				.withNewSelector()
				.withMatchLabels(Map.of(APP, app))
				.endSelector()
				.endSpec()
				.build())
			.create();
	}

	/** {@code consumer} pods mount the Secret, the ConfigMap and the claim. */
	private void seedPod(String name, String app, boolean consumer) {
		PodBuilder pod = new PodBuilder().withNewMetadata()
			.withName(name)
			.withNamespace(NS)
			.withLabels(Map.of(APP, app))
			.addNewOwnerReference()
			.withKind("ReplicaSet")
			.withApiVersion("apps/v1")
			.withName("dep-abc")
			.withController(true)
			.endOwnerReference()
			.endMetadata()
			.withNewSpec()
			.withServiceAccountName(consumer ? "sa1" : "other-sa")
			.addNewContainer()
			.withName("c")
			.endContainer()
			.endSpec();
		if (consumer) {
			pod = pod.editSpec()
				.addNewVolume()
				.withName("creds")
				.withNewSecret()
				.withSecretName("sec1")
				.endSecret()
				.endVolume()
				.addNewVolume()
				.withName("conf")
				.withNewConfigMap()
				.withName("cm1")
				.endConfigMap()
				.endVolume()
				.addNewVolume()
				.withName("data")
				.withNewPersistentVolumeClaim()
				.withClaimName("pvc1")
				.endPersistentVolumeClaim()
				.endVolume()
				.endSpec();
		}
		this.client.pods().inNamespace(NS).resource(pod.build()).create();
	}

	private void seedNetwork() {
		this.client.services()
			.inNamespace(NS)
			.resource(new ServiceBuilder().withNewMetadata()
				.withName("svc")
				.withNamespace(NS)
				.endMetadata()
				.withNewSpec()
				.withSelector(Map.of(APP, WEB))
				.endSpec()
				.build())
			.create();
		seedEndpoints("svc");
		// Endpoints are addressed by the Service's own name; a namespace-mate must not
		// satisfy the join.
		seedEndpoints("unrelated");
		seedIngress("ing", "svc");
		seedIngress("ing-other", "some-other-service");
	}

	private void seedEndpoints(String name) {
		this.client.endpoints()
			.inNamespace(NS)
			.resource(new EndpointsBuilder().withNewMetadata()
				.withName(name)
				.withNamespace(NS)
				.endMetadata()
				.addNewSubset()
				.addNewAddress()
				.withIp("192.0.2.11")
				.endAddress()
				.endSubset()
				.build())
			.create();
	}

	private void seedIngress(String name, String backend) {
		this.client.network()
			.v1()
			.ingresses()
			.inNamespace(NS)
			.resource(new IngressBuilder().withNewMetadata()
				.withName(name)
				.withNamespace(NS)
				.endMetadata()
				.withNewSpec()
				.addNewRule()
				.withNewHttp()
				.addNewPath()
				.withPath("/")
				.withPathType("Prefix")
				.withNewBackend()
				.withNewService()
				.withName(backend)
				.withNewPort()
				.withNumber(80)
				.endPort()
				.endService()
				.endBackend()
				.endPath()
				.endHttp()
				.endRule()
				.endSpec()
				.build())
			.create();
	}

	private void seedConfigAndStorage() {
		this.client.secrets()
			.inNamespace(NS)
			.resource(new SecretBuilder().withNewMetadata().withName("sec1").withNamespace(NS).endMetadata().build())
			.create();
		this.client.configMaps()
			.inNamespace(NS)
			.resource(new ConfigMapBuilder().withNewMetadata()
				.withName("cm1")
				.withNamespace(NS)
				.endMetadata()
				.addToData("k", "only-in-the-full-object")
				.build())
			.create();
		// spec.selector on a claim selects VOLUMES, never pods — a generic "has a
		// selector" rule would attach selectedPods here and render a confidently wrong
		// table.
		this.client.persistentVolumeClaims()
			.inNamespace(NS)
			.resource(new PersistentVolumeClaimBuilder().withNewMetadata()
				.withName("pvc1")
				.withNamespace(NS)
				.endMetadata()
				.withNewSpec()
				.withVolumeName("pv1")
				.withNewSelector()
				.withMatchLabels(Map.of(APP, WEB))
				.endSelector()
				.endSpec()
				.build())
			.create();
		seedVolume("pv1", "pvc1");
		seedVolume("pv2", null);
	}

	private void seedVolume(String name, String claim) {
		PersistentVolumeBuilder volume = new PersistentVolumeBuilder().withNewMetadata()
			.withName(name)
			.endMetadata()
			.withNewSpec()
			.endSpec();
		if (claim != null) {
			volume = volume.editSpec().withNewClaimRef().withNamespace(NS).withName(claim).endClaimRef().endSpec();
		}
		this.client.persistentVolumes().resource(volume.build()).create();
	}

	private void seedAccess() {
		this.client.serviceAccounts()
			.inNamespace(NS)
			.resource(new ServiceAccountBuilder().withNewMetadata()
				.withName("sa1")
				.withNamespace(NS)
				.endMetadata()
				.build())
			.create();
		this.client.rbac()
			.roleBindings()
			.inNamespace(NS)
			.resource(new RoleBindingBuilder().withNewMetadata()
				.withName("rb-sa1")
				.withNamespace(NS)
				.endMetadata()
				.addNewSubject()
				.withKind("ServiceAccount")
				.withName("sa1")
				.withNamespace(NS)
				.endSubject()
				.build())
			.create();
		this.client.rbac()
			.roleBindings()
			.inNamespace(NS)
			.resource(new RoleBindingBuilder().withNewMetadata()
				.withName("rb-other")
				.withNamespace(NS)
				.endMetadata()
				.addNewSubject()
				.withKind("ServiceAccount")
				.withName("other-sa")
				.withNamespace(NS)
				.endSubject()
				.build())
			.create();
		seedClusterBinding("crb-group", "system:serviceaccounts:" + NS);
		// Every identity in the cluster is in system:authenticated, so reporting its
		// grants would bury the ones that say something about THIS account.
		seedClusterBinding("crb-everyone", "system:authenticated");
	}

	private void seedClusterBinding(String name, String group) {
		this.client.rbac()
			.clusterRoleBindings()
			.resource(new ClusterRoleBindingBuilder().withNewMetadata()
				.withName(name)
				.endMetadata()
				.addNewSubject()
				.withKind("Group")
				.withName(group)
				.endSubject()
				.build())
			.create();
	}

	// --- helpers ------------------------------------------------------------------

	private String detail(String resourceId, String namespace, String name) throws Exception {
		return this.mvc.perform(get("/api/v1/clusters/test/detail/{id}/{ns}/{name}", resourceId, namespace, name))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private JsonNode relations(String resourceId, String namespace, String name) throws Exception {
		return Serialization.jsonMapper().readTree(detail(resourceId, namespace, name)).get("relations");
	}

	/**
	 * The {@code metadata.name} of every item in one relation, in wire order. Reads the
	 * TOP-LEVEL metadata of each item deliberately — a recursive search would also find a
	 * workload's {@code spec.template.metadata} and turn a wrong result into a passing
	 * one.
	 */
	private List<String> names(JsonNode relations, String relation) {
		List<String> out = new ArrayList<>();
		relations.get(relation).get("items").forEach((item) -> out.add(item.get("metadata").get("name").asText()));
		return out;
	}

	// --- the envelope -------------------------------------------------------------

	@Test
	void theEnvelopeIsExactlyObjectAndRelations() throws Exception {
		JsonNode body = Serialization.jsonMapper().readTree(detail("services", NS, "svc"));

		Set<String> keys = new LinkedHashSet<>();
		body.fieldNames().forEachRemaining(keys::add);
		// Pinned exactly: three clients are about to read this envelope, so a key added
		// or renamed here is a contract change and has to be a deliberate one.
		assertThat(keys).containsExactly("object", "relations");
		assertThat(body.get("object").get("kind").asText()).isEqualTo("Service");
		assertThat(body.get("object").get("apiVersion").asText()).isEqualTo("v1");
		assertThat(body.get("object").get("metadata").get("name").asText()).isEqualTo("svc");
		assertThat(body.get("relations").isObject()).isTrue();
	}

	@Test
	void relationsIsAlwaysAnObjectEvenWhenTheKindHasNone() throws Exception {
		// Endpoints have no relations at all. The key must still be there and must be an
		// empty OBJECT — absent or null would make a client branch on "did the server
		// even try", which is a different question from "are there none".
		this.mvc.perform(get("/api/v1/clusters/test/detail/endpoints/{ns}/unrelated", NS))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.relations").exists())
			.andExpect(jsonPath("$.relations").value(Matchers.anEmptyMap()));
	}

	@Test
	void theObjectIsShippedWholeUnlikeAListRow() throws Exception {
		// The list endpoint ships ConfigMap keys with NULL values (ListProjection); the
		// drawer's refetch predicate exists because of that. The detail envelope is the
		// other side of the same contract: it carries the values, so a client that opened
		// a detail does not need a third request.
		this.mvc.perform(get("/api/v1/clusters/test/detail/configmaps/{ns}/cm1", NS))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.object.data.k").value("only-in-the-full-object"))
			.andExpect(content().string(Matchers.containsString("only-in-the-full-object")));
	}

	// --- Relation's three-state wire shape ----------------------------------------

	@Test
	void aResolvedRelationOmitsErrorEntirelyRatherThanSendingNull() throws Exception {
		this.mvc.perform(get("/api/v1/clusters/test/detail/services/{ns}/svc", NS))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.relations.endpoints.items").isArray())
			.andExpect(jsonPath("$.relations.endpoints.truncated").value(false))
			.andExpect(jsonPath("$.relations.endpoints.notPermitted").value(false))
			// ABSENT, not null: the SPA's `error?: string | null` is written against
			// this.
			.andExpect(jsonPath("$.relations.endpoints.error").doesNotExist());
	}

	/**
	 * All three states of a {@code Relation} in ONE response, so the omission above is
	 * proved to be about null rather than about the field.
	 *
	 * <p>
	 * Without a failing relation in the same body, {@code error.doesNotExist()} would
	 * also pass against a serializer that dropped {@code error} unconditionally — i.e.
	 * against a build that could never report a failed relation at all. A real 403 cannot
	 * be provoked through the CRUD mock, so the joins are stubbed here and only the
	 * controller's own encoding is under test; the joins themselves are covered above.
	 */
	@Test
	void theThreeStatesOfARelationArriveDistinguishable() throws Exception {
		ResourceService resources = mock(ResourceService.class);
		RelationService relations = mock(RelationService.class);
		ClusterNavService nav = mock(ClusterNavService.class);
		GenericKubernetesResource pod = Serialization.unmarshal(
				Serialization
					.asJson(new PodBuilder().withNewMetadata().withName("p").withNamespace(NS).endMetadata().build()),
				GenericKubernetesResource.class);
		given(nav.find("c", "pods")).willReturn(Optional.of(WellKnownKinds.PODS));
		given(resources.getRaw("c", WellKnownKinds.PODS, NS, "p")).willReturn(pod);
		Map<String, Relation> stubbed = new LinkedHashMap<>();
		stubbed.put("resolved", Relation.of(List.of(pod), true));
		stubbed.put("broken", Relation.failed("boom"));
		stubbed.put("refused", Relation.notPermitted("nope"));
		given(relations.relationsFor(eq("c"), eq(WellKnownKinds.PODS), any())).willReturn(stubbed);

		MockMvcBuilders.standaloneSetup(new DetailApiController(resources, relations, nav))
			.build()
			.perform(get("/api/v1/clusters/c/detail/pods/{ns}/p", NS))
			.andExpect(status().isOk())
			// Resolved: items and a truncation flag, and NO error key at all.
			.andExpect(jsonPath("$.relations.resolved.items[0].metadata.name").value("p"))
			.andExpect(jsonPath("$.relations.resolved.truncated").value(true))
			.andExpect(jsonPath("$.relations.resolved.notPermitted").value(false))
			.andExpect(jsonPath("$.relations.resolved.error").doesNotExist())
			// Failed: the reason is carried, and it is NOT a permission problem.
			.andExpect(jsonPath("$.relations.broken.error").value("boom"))
			.andExpect(jsonPath("$.relations.broken.notPermitted").value(false))
			.andExpect(jsonPath("$.relations.broken.items").value(Matchers.empty()))
			// Refused: distinguishable from a malfunction, which is the whole point of
			// the flag — a least-privilege deployment hits this legitimately.
			.andExpect(jsonPath("$.relations.refused.notPermitted").value(true))
			.andExpect(jsonPath("$.relations.refused.error").value("nope"));
	}

	// --- the twelve relations, addressed --------------------------------------------

	@Test
	void everyRelationIsReachableThroughTheRoute() throws Exception {
		Set<String> seen = new LinkedHashSet<>();
		collect(seen, relations("services", NS, "svc"));
		collect(seen, relations("deployments", NS, "dep"));
		collect(seen, relations("pods", NS, "web-1"));
		collect(seen, relations("secrets", NS, "sec1"));
		collect(seen, relations("persistentvolumeclaims", NS, "pvc1"));
		collect(seen, relations("persistentvolumes", "-", "pv1"));
		collect(seen, relations("serviceaccounts", NS, "sa1"));

		assertThat(seen).containsExactlyInAnyOrderElementsOf(ALL_RELATIONS);
	}

	private void collect(Set<String> into, JsonNode relations) {
		relations.fieldNames().forEachRemaining(into::add);
	}

	@Test
	void theServiceDrawerNamesItsOwnEndpointsIngressAndPods() throws Exception {
		JsonNode relations = relations("services", NS, "svc");

		assertThat(names(relations, "endpoints")).containsExactly("svc");
		assertThat(names(relations, "routedBy")).containsExactly("ing");
		assertThat(names(relations, "selectedPods")).containsExactly("web-1");
	}

	@Test
	void theDeploymentDrawerNamesItsOwnReplicaSetAutoscalerAndBudget() throws Exception {
		JsonNode relations = relations("deployments", NS, "dep");

		// `rogue` carries the same labels and is excluded by the owner reference alone.
		assertThat(names(relations, "replicaSets")).containsExactly("dep-abc");
		assertThat(names(relations, "autoscaledBy")).containsExactly("hpa-dep");
		// The budget is matched against the POD TEMPLATE's labels (app=web), not the
		// Deployment's own (app=db) — so `pdb-db` here would mean the wrong labels won.
		assertThat(names(relations, "disruptionBudgets")).containsExactly("pdb-web");
		assertThat(names(relations, "selectedPods")).containsExactly("web-1");
	}

	@Test
	void thePodDrawerWalksTheOwnerChainNearestFirst() throws Exception {
		JsonNode relations = relations("pods", NS, "web-1");

		// Nearest first, and the second hop is the thing an operator actually edits.
		assertThat(names(relations, "ownedBy")).containsExactly("dep-abc", "dep");
		assertThat(names(relations, "serviceAccount")).containsExactly("sa1");
		assertThat(names(relations, "disruptionBudgets")).containsExactly("pdb-web");
	}

	@Test
	void theConsumedKindsNameOnlyThePodsThatActuallyUseThem() throws Exception {
		assertThat(names(relations("secrets", NS, "sec1"), "mountedBy")).containsExactly("web-1");
		assertThat(names(relations("configmaps", NS, "cm1"), "mountedBy")).containsExactly("web-1");
		assertThat(names(relations("persistentvolumeclaims", NS, "pvc1"), "mountedBy")).containsExactly("web-1");
	}

	@Test
	void bothEndsOfAStorageBindingResolveThroughTheRoute() throws Exception {
		JsonNode claim = relations("persistentvolumeclaims", NS, "pvc1");
		assertThat(names(claim, "boundVolume")).containsExactly("pv1");
		// A claim's spec.selector selects VOLUMES; attaching pods to it would be a
		// confidently wrong table, so the relation must not be registered at all.
		assertThat(claim.has("selectedPods")).isFalse();

		JsonNode volume = relations("persistentvolumes", "-", "pv1");
		assertThat(names(volume, "boundClaim")).containsExactly("pvc1");
	}

	@Test
	void theServiceAccountDrawerNamesDirectAndGroupGrantsButNotClusterWideOnes() throws Exception {
		JsonNode relations = relations("serviceaccounts", NS, "sa1");

		// RoleBindings first, then ClusterRoleBindings; `crb-everyone`
		// (system:authenticated)
		// applies to every identity in the cluster and says nothing about this account.
		assertThat(names(relations, "grantedBy")).containsExactly("rb-sa1", "crb-group");
	}

	/**
	 * A cluster-scoped kind still has to put SOMETHING in the {@code {namespace}} segment
	 * — the route has no cluster-scoped form — and the server ignores whatever it is.
	 * Pinned because it is the difference between a client that can open a
	 * PersistentVolume's drawer and one that cannot construct the URL at all.
	 *
	 * <p>
	 * {@code _} is the one the SPA actually sends (#313), and it is mapped back to "no
	 * namespace" deliberately rather than surviving because the segment happens to be
	 * ignored — hence its own case in
	 * {@link #theClusterScopedSentinelIsMappedBackToNoNamespace()}.
	 */
	@Test
	void aClusterScopedKindIgnoresTheNamespaceSegmentRatherThanFailing() throws Exception {
		for (String placeholder : List.of("-", "default", NS, ResourceActionApiController.NO_NAMESPACE)) {
			this.mvc.perform(get("/api/v1/clusters/test/detail/persistentvolumes/{ns}/pv1", placeholder))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.object.metadata.name").value("pv1"))
				.andExpect(jsonPath("$.relations.boundClaim.items[0].metadata.name").value("pvc1"));
		}
	}

	/**
	 * The sentinel reaches {@code ResourceService} as an EMPTY namespace, not as the
	 * literal {@code _}. Asserting only through the CRUD mock could not tell those apart
	 * — a cluster-scoped get ignores the argument either way — so the service is stubbed
	 * and the argument itself is the assertion.
	 */
	@Test
	void theClusterScopedSentinelIsMappedBackToNoNamespace() throws Exception {
		ResourceDescriptor volumes = ResourceDescriptor.coreCluster("persistentvolumes", "Persistent Volumes",
				"PersistentVolume", "persistentvolumes");
		ResourceService resources = mock(ResourceService.class);
		RelationService relations = mock(RelationService.class);
		ClusterNavService nav = mock(ClusterNavService.class);
		GenericKubernetesResource volume = Serialization.unmarshal(
				Serialization
					.asJson(new PersistentVolumeBuilder().withNewMetadata().withName("pv1").endMetadata().build()),
				GenericKubernetesResource.class);
		given(nav.find("c", "persistentvolumes")).willReturn(Optional.of(volumes));
		given(resources.getRaw("c", volumes, "", "pv1")).willReturn(volume);
		given(relations.relationsFor(eq("c"), eq(volumes), any())).willReturn(Map.of());

		MockMvcBuilders.standaloneSetup(new DetailApiController(resources, relations, nav))
			.build()
			.perform(get("/api/v1/clusters/c/detail/persistentvolumes/{ns}/pv1",
					ResourceActionApiController.NO_NAMESPACE))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.object.metadata.name").value("pv1"));

		then(resources).should().getRaw("c", volumes, "", "pv1");
	}

	/**
	 * A real namespace segment is passed through untouched — the sentinel mapping must
	 * not turn into "the namespace is optional everywhere". Addressed with the WRONG
	 * namespace, a namespaced object is not found, which is what proves the segment is
	 * still used.
	 */
	@Test
	void aRealNamespaceSegmentIsStillPassedThrough() throws Exception {
		this.mvc.perform(get("/api/v1/clusters/test/detail/configmaps/{ns}/cm1", NS)).andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/clusters/test/detail/configmaps/{ns}/cm1", "other-ns"))
			.andExpect(status().isBadRequest());
	}

	// --- the error paths ------------------------------------------------------------

	@Test
	void aMissingObjectIs400WithAProblemDetailThatNamesIt() throws Exception {
		this.mvc.perform(get("/api/v1/clusters/test/detail/services/{ns}/nope", NS))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid-request"))
			.andExpect(jsonPath("$.title").value("Invalid request"))
			// The kind and the coordinates, so the message is actionable on its own.
			.andExpect(jsonPath("$.detail").value(Matchers.containsString("Service")))
			.andExpect(jsonPath("$.detail").value(Matchers.containsString(NS + "/nope")));
	}

	@Test
	void aMissingNamespacedObjectIs400AndNot404() throws Exception {
		// 404 would say "no such route"; the route exists and the request was
		// well-formed,
		// so the 400 is the claim "that object is not there". A client distinguishes an
		// unknown KIND (404) from an absent OBJECT (400) on this alone.
		this.mvc.perform(get("/api/v1/clusters/test/detail/pods/{ns}/ghost", NS)).andExpect(status().isBadRequest());
		this.mvc.perform(get("/api/v1/clusters/test/detail/not-a-kind/{ns}/web-1", NS))
			.andExpect(status().isNotFound());
	}

	@Test
	void anUnknownClusterIs404AndSaysSo() throws Exception {
		this.mvc.perform(get("/api/v1/clusters/no-such-cluster/detail/services/{ns}/svc", NS))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("unknown-cluster"));
	}

}

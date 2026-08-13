package org.alexmond.kweblens.web.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.fabric8.kubernetes.api.model.ContainerStateBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.health.HealthService;
import org.alexmond.kweblens.health.KindHealth;
import org.alexmond.kweblens.health.ObjectState;
import org.alexmond.kweblens.health.StateCount;
import org.alexmond.kweblens.health.StatusContext;
import org.alexmond.kweblens.health.StatusVocabulary;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The card's number and the rows a {@code status:} query selects are the same
 * objects.</b>
 *
 * <p>
 * That equality is the whole of GH#337, and it is the thing that cannot be left to
 * inspection: two predicates producing similar words is exactly the defect (#157, #316)
 * this epic was opened to avoid repeating. So this file runs both halves over the
 * <em>same</em> seeded cluster — {@link HealthService}, which is what
 * {@code /overview/workloads} returns, and {@link ListProjection}, which is what every
 * list row and every watch event goes through — and compares the two breakdowns as maps.
 *
 * <p>
 * The population covers one state per tone, because a colour is where a wrong verdict is
 * cheapest to notice and most expensive to have: {@code ok} Running, {@code idle}
 * Completed, {@code warn} Pending, {@code err} CrashLoopBackOff, plus Deployments in
 * their own vocabulary and a ServiceAccount that no rule judges at all.
 *
 * <p>
 * <b>Decoys, and proof the comparison can fail.</b> A map-equality assertion passes
 * trivially when both sides are empty and passes misleadingly when the seeded cluster has
 * only one state in it, so the fleet is deliberately mixed, the expected shape is pinned
 * independently, and {@link #theComparisonIsCapableOfFailing()} perturbs one row's label
 * and shows the assertion going red. Without that, "they agree" would be a claim about
 * this test rather than about the code.
 */
@EnableKubernetesMockClient(crud = true)
class StatusVocabularyEqualityTest {

	private static final String NS = "app";

	private static final ResourceDescriptor PODS = ResourceDescriptor.coreNamespaced("pods", "Pods", "Pod", "pods");

	private static final ResourceDescriptor DEPLOYMENTS = ResourceDescriptor.namespaced("deployments", "Deployments",
			"Deployment", "apps", "v1", "deployments");

	KubernetesClient client;

	private ClusterRegistry registry() {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register("mock", "mock", this.client);
		return registry;
	}

	private ResourceService resources() {
		return new ResourceService(registry());
	}

	// --- seeding ---

	private void pod(String name, String phase, String waitingReason) {
		PodStatusBuilder status = new PodStatusBuilder().withPhase(phase);
		if (waitingReason != null) {
			status.withContainerStatuses(new ContainerStatusBuilder().withName("c")
				.withReady(false)
				.withRestartCount(0)
				.withImage("busybox")
				.withImageID("")
				.withState(new ContainerStateBuilder().withNewWaiting().withReason(waitingReason).endWaiting().build())
				.build());
		}
		this.client.pods()
			.inNamespace(NS)
			.resource(new PodBuilder().withNewMetadata()
				.withName(name)
				.withNamespace(NS)
				.endMetadata()
				.withStatus(status.build())
				.build())
			.create();
	}

	private void deployment(String name, int desired, int ready) {
		this.client.apps()
			.deployments()
			.inNamespace(NS)
			.resource(new DeploymentBuilder().withNewMetadata()
				.withName(name)
				.withNamespace(NS)
				.endMetadata()
				.withNewSpec()
				.withReplicas(desired)
				.endSpec()
				.withNewStatus()
				.withReadyReplicas(ready)
				.endStatus()
				.build())
			.create();
	}

	/**
	 * One state per tone, several objects in some of them, and a kind with no rule at
	 * all.
	 */
	private void seedTheFleet() {
		pod("web-1", "Running", null);
		pod("web-2", "Running", null);
		pod("web-3", "Running", null);
		pod("batch-done", "Succeeded", null);
		pod("sched-1", "Pending", null);
		pod("sched-2", "Pending", null);
		pod("crash-1", "Pending", "CrashLoopBackOff");
		pod("pull-1", "Pending", "ImagePullBackOff");
		deployment("api", 3, 3);
		deployment("worker", 2, 1);
		deployment("archived", 0, 0);
		this.client.serviceAccounts()
			.inNamespace(NS)
			.resource(new io.fabric8.kubernetes.api.model.ServiceAccountBuilder().withNewMetadata()
				.withName("runner")
				.withNamespace(NS)
				.endMetadata()
				.build())
			.create();
	}

	// --- the two halves ---

	/** What the overview card shows: label to count, from the health summary. */
	private Map<String, Integer> cardCounts(ResourceDescriptor descriptor) {
		List<KindHealth> summary = new HealthService(resources()).summarise("mock", List.of(descriptor), NS);
		Map<String, Integer> out = new TreeMap<>();
		assertThat(summary).singleElement().extracting(KindHealth::error).isNull();
		for (StateCount state : summary.get(0).states()) {
			out.put(state.label(), state.count());
		}
		return out;
	}

	/**
	 * What a `status:` query would select: label to row count, from the list projection.
	 */
	private Map<String, Integer> rowCounts(ResourceDescriptor descriptor) {
		Map<String, Integer> out = new TreeMap<>();
		for (GenericKubernetesResource row : rows(descriptor)) {
			ObjectState state = (ObjectState) row.getAdditionalProperties().get(StatusVocabulary.FIELD);
			if (state != null) {
				out.merge(state.label(), 1, Integer::sum);
			}
		}
		return out;
	}

	private List<GenericKubernetesResource> rows(ResourceDescriptor descriptor) {
		// No context: every kind here is judged from the object alone. The kinds that
		// need one have their own equality test — ContextualStatusEqualityTest.
		return ListProjection.forList(resources().listRaw("mock", descriptor, NS), StatusContext.none());
	}

	// --- the assertions ---

	@Test
	void everyStateAPodCardCountsSelectsExactlyThoseRows() {
		seedTheFleet();

		Map<String, Integer> card = cardCounts(PODS);

		// Pinned independently as well as compared, so a bug that emptied both
		// sides could not pass as agreement.
		assertThat(card).containsExactlyInAnyOrderEntriesOf(
				Map.of("Running", 3, "Completed", 1, "Pending", 2, "CrashLoopBackOff", 1, "ImagePullBackOff", 1));
		assertThat(rowCounts(PODS)).isEqualTo(card);
	}

	@Test
	void theSameHoldsForAKindWithItsOwnVocabulary() {
		// Deployments say "Healthy" / "Unavailable" / "Idle" where a pod says "Running".
		// The equality is per kind and per label, not a shared severity scale.
		seedTheFleet();

		Map<String, Integer> card = cardCounts(DEPLOYMENTS);

		assertThat(card).containsExactlyInAnyOrderEntriesOf(Map.of("Healthy", 1, "Unavailable", 1, "Idle", 1));
		assertThat(rowCounts(DEPLOYMENTS)).isEqualTo(card);
	}

	@Test
	void everyToneIsRepresented() {
		// A colour is a claim of its own — "this is broken" versus "this is deliberate" —
		// and the ticket asks for the equality in each one, so the fleet must actually
		// contain all four rather than four labels that happen to be red.
		seedTheFleet();

		Map<String, String> tones = new LinkedHashMap<>();
		for (GenericKubernetesResource row : rows(PODS)) {
			ObjectState state = (ObjectState) row.getAdditionalProperties().get(StatusVocabulary.FIELD);
			tones.put(state.label(), state.tone());
		}
		for (GenericKubernetesResource row : rows(DEPLOYMENTS)) {
			ObjectState state = (ObjectState) row.getAdditionalProperties().get(StatusVocabulary.FIELD);
			tones.put(state.label(), state.tone());
		}

		assertThat(tones).containsEntry("Running", StateCount.OK)
			.containsEntry("Completed", StateCount.IDLE)
			.containsEntry("Pending", StateCount.WARN)
			.containsEntry("CrashLoopBackOff", StateCount.ERR)
			.containsEntry("Idle", StateCount.IDLE);
		// ...and the tone a row carries is the tone the card coloured that state with.
		for (KindHealth kind : new HealthService(resources()).summarise("mock", List.of(PODS, DEPLOYMENTS), NS)) {
			for (StateCount state : kind.states()) {
				assertThat(tones).containsEntry(state.label(), state.tone());
			}
		}
	}

	@Test
	void theComparisonIsCapableOfFailing() {
		// The mutation #312 asks for: change one row's state and the two sides must stop
		// agreeing. Without this, "the maps are equal" could be true of a comparison that
		// never looked at anything.
		seedTheFleet();
		Map<String, Integer> card = cardCounts(PODS);

		Map<String, Integer> perturbed = new TreeMap<>(rowCounts(PODS));
		perturbed.merge("Running", -1, Integer::sum);
		perturbed.merge("Pending", 1, Integer::sum);

		assertThat(perturbed).isNotEqualTo(card);
	}

	@Test
	void aKindNoRuleJudgesCarriesNoStateAtAll() {
		// Not "ok", not an empty label — absent. A row nobody examined must not be
		// selectable as healthy, and must not be counted into a card that does not have
		// it. ServiceAccount rather than ConfigMap: a ConfigMap now HAS a state, it
		// simply needs a context to reach it, which is a different claim (GH#340).
		seedTheFleet();
		ResourceDescriptor serviceAccounts = ResourceDescriptor.coreNamespaced("serviceaccounts", "Service Accounts",
				"ServiceAccount", "serviceaccounts");

		List<GenericKubernetesResource> projected = rows(serviceAccounts);

		assertThat(projected).isNotEmpty();
		assertThat(projected)
			.allSatisfy((row) -> assertThat(row.getAdditionalProperties()).doesNotContainKey(StatusVocabulary.FIELD));
		assertThat(Serialization.asJson(projected)).doesNotContain(StatusVocabulary.FIELD);
	}

	@Test
	void theStateReachesTheWireInTheShapeTheBrowserReads() {
		// objectFilter.ts reads `kweblensState.label`; types.ts declares `{label, tone}`.
		// Asserted on the serialised bytes, because a field that exists on the object and
		// not in the JSON is a filter that silently matches nothing.
		seedTheFleet();

		String json = Serialization.asJson(rows(PODS));

		assertThat(json).contains("\"kweblensState\"")
			.contains("\"label\":\"CrashLoopBackOff\"")
			.contains("\"tone\":\"err\"");
	}

}

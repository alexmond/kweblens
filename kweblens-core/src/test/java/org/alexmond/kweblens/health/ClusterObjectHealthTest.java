package org.alexmond.kweblens.health;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Node and Namespace rules — the states the Cluster overview counts and a
 * {@code status:} term selects on.
 *
 * <p>
 * The load-bearing cases are the ones where a state could have been invented instead of
 * read: a cordoned-but-healthy node (kubectl's {@code Ready,SchedulingDisabled}, not a
 * "Cordoned" of our own), a node whose kubelet went quiet ({@code Ready=Unknown}, which
 * kubectl prints as NotReady and whose distinction belongs in the reason), a node under
 * disk pressure (still {@code Ready} — the pressure conditions are a column, not a
 * state), and a namespace, which has exactly two phases and no third judgement about what
 * is inside it.
 */
class ClusterObjectHealthTest {

	private GenericKubernetesResource obj(String yamlish) {
		return Serialization.unmarshal(yamlish, GenericKubernetesResource.class);
	}

	private WorkloadHealth.Verdict node(String yamlish) {
		return ClusterObjectHealth.verdict("Node", obj(yamlish));
	}

	private WorkloadHealth.Verdict namespace(String phase) {
		return ClusterObjectHealth.verdict("Namespace", obj("""
				apiVersion: v1
				kind: Namespace
				metadata: {name: app}
				status: {phase: %s}
				""".formatted(phase)));
	}

	// --- what it judges at all ---

	@Test
	void judgesNodesAndNamespacesAndNotEvents() {
		// Event is the third kind in the Cluster nav category and is deliberately absent:
		// an event's Warning/Normal is its `type` — a field on a report ABOUT another
		// object — so counting it as a state would be a different thing wearing the same
		// word (GH#339).
		assertThat(ClusterObjectHealth.supports("Node")).isTrue();
		assertThat(ClusterObjectHealth.supports("Namespace")).isTrue();
		assertThat(ClusterObjectHealth.supports("Event")).isFalse();
		assertThat(ClusterObjectHealth.supports("Pod")).isFalse();
	}

	// --- nodes ---

	@Test
	void aReadyNodeIsReady() {
		var verdict = node("""
				apiVersion: v1
				kind: Node
				metadata: {name: n1}
				status: {conditions: [{type: Ready, status: "True"}]}
				""");
		assertThat(verdict.state()).isEqualTo(WorkloadHealth.State.OK);
		assertThat(verdict.label()).isEqualTo("Ready");
		assertThat(verdict.tone()).isEqualTo(StateCount.OK);
	}

	@Test
	void aCordonedNodeIsCountedApartFromTheReadyOnes() {
		// kubectl's own spelling, and the reason it is not simply "Ready": someone
		// draining a node is looking for exactly this row, and a card that folded it into
		// Ready would hide it.
		var verdict = node("""
				apiVersion: v1
				kind: Node
				metadata: {name: n1}
				spec: {unschedulable: true}
				status: {conditions: [{type: Ready, status: "True"}]}
				""");
		assertThat(verdict.label()).isEqualTo("Ready,SchedulingDisabled");
		// Deliberate, like a suspended CronJob — amber, not the red of a broken node.
		assertThat(verdict.state()).isEqualTo(WorkloadHealth.State.SUSPENDED);
		assertThat(verdict.tone()).isEqualTo(StateCount.WARN);
	}

	@Test
	void aNotReadyNodeCarriesTheConditionsOwnReason() {
		var verdict = node("""
				apiVersion: v1
				kind: Node
				metadata: {name: n1}
				status:
				  conditions: [{type: Ready, status: "False", reason: KubeletNotReady}]
				""");
		assertThat(verdict.state()).isEqualTo(WorkloadHealth.State.ATTENTION);
		assertThat(verdict.label()).isEqualTo("NotReady");
		assertThat(verdict.tone()).isEqualTo(StateCount.ERR);
		assertThat(verdict.reason()).isEqualTo("KubeletNotReady");
	}

	@Test
	void aNodeWhoseKubeletWentQuietIsNotReadyWithTheReasonSayingWhy() {
		// Ready=Unknown is NOT a third state on the card: kubectl prints NotReady, and so
		// does the Nodes list's own Status column. The distinction survives in the
		// reason,
		// which is where a per-object detail belongs.
		var verdict = node("""
				apiVersion: v1
				kind: Node
				metadata: {name: n1}
				status:
				  conditions: [{type: Ready, status: Unknown, reason: NodeStatusUnknown}]
				""");
		assertThat(verdict.label()).isEqualTo("NotReady");
		assertThat(verdict.reason()).isEqualTo("NodeStatusUnknown");
	}

	@Test
	void aCordonedNodeThatIsAlsoDownSaysBoth() {
		var verdict = node("""
				apiVersion: v1
				kind: Node
				metadata: {name: n1}
				spec: {unschedulable: true}
				status: {conditions: [{type: Ready, status: "False"}]}
				""");
		assertThat(verdict.label()).isEqualTo("NotReady,SchedulingDisabled");
		assertThat(verdict.tone()).isEqualTo(StateCount.ERR);
		// No reason on the condition, so the status itself is the reason rather than an
		// empty string that would render as a blank cell.
		assertThat(verdict.reason()).isEqualTo("Ready=False");
	}

	@Test
	void diskPressureDoesNotChangeAReadyNodesState() {
		// The pressure conditions are real and already on screen — the Nodes list has a
		// Conditions column. Folding them in here would invent a status kubectl never
		// prints AND split the Ready count into a vocabulary no other tool shares.
		var verdict = node("""
				apiVersion: v1
				kind: Node
				metadata: {name: n1}
				status:
				  conditions:
				    - {type: DiskPressure, status: "True"}
				    - {type: Ready, status: "True"}
				""");
		assertThat(verdict.label()).isEqualTo("Ready");
		assertThat(verdict.state()).isEqualTo(WorkloadHealth.State.OK);
	}

	@Test
	void aNodeWithNoReadyConditionIsUnknownRatherThanEitherVerdict() {
		var verdict = node("""
				apiVersion: v1
				kind: Node
				metadata: {name: n1}
				status: {conditions: [{type: MemoryPressure, status: "False"}]}
				""");
		assertThat(verdict.label()).isEqualTo("Unknown");
		// Amber: we did not learn that it is broken, so red would be a claim — and green
		// would be a worse one.
		assertThat(verdict.tone()).isEqualTo(StateCount.WARN);
		assertThat(verdict.state()).isEqualTo(WorkloadHealth.State.ATTENTION);
	}

	@Test
	void aNodeWithNoStatusAtAllDoesNotThrow() {
		// A projected or half-written object must not take the whole card down with a
		// NullPointerException; "Unknown" is the honest reading of nothing.
		assertThat(node("""
				apiVersion: v1
				kind: Node
				metadata: {name: n1}
				""").label()).isEqualTo("Unknown");
	}

	// --- namespaces ---

	@Test
	void anActiveNamespaceIsActive() {
		var verdict = namespace("Active");
		assertThat(verdict.state()).isEqualTo(WorkloadHealth.State.OK);
		assertThat(verdict.label()).isEqualTo("Active");
		assertThat(verdict.tone()).isEqualTo(StateCount.OK);
	}

	@Test
	void aTerminatingNamespaceIsSurfacedButNotPaintedAsBroken() {
		// Usually a delete in progress; occasionally a finalizer nothing will satisfy.
		// Amber surfaces the second without crying wolf about the first.
		var verdict = namespace("Terminating");
		assertThat(verdict.state()).isEqualTo(WorkloadHealth.State.ATTENTION);
		assertThat(verdict.label()).isEqualTo("Terminating");
		assertThat(verdict.tone()).isEqualTo(StateCount.WARN);
	}

	@Test
	void aNamespaceWithNoPhaseIsUnknown() {
		var verdict = ClusterObjectHealth.verdict("Namespace", obj("""
				apiVersion: v1
				kind: Namespace
				metadata: {name: app}
				"""));
		assertThat(verdict.label()).isEqualTo("Unknown");
		assertThat(verdict.reason()).isEqualTo("no phase reported");
	}

	@Test
	void anUnrecognisedPhaseIsReportedAsThatPhase() {
		// Reporting a value the cluster sent is not inventing a state; collapsing it into
		// "Unknown" would be the one reading nobody could act on.
		assertThat(namespace("Draining").label()).isEqualTo("Draining");
	}

	// --- the seam ---

	@Test
	void theSeamRoutesTheseKindsHereAndStillRoutesWorkloadsToWorkloadHealth() {
		// StatusVocabulary is the single entry point both the card and the list row go
		// through; if it stopped dispatching here, a Node row would silently get
		// WorkloadHealth's default "OK" and every node would count as healthy.
		assertThat(StatusVocabulary.covers("Node")).isTrue();
		assertThat(StatusVocabulary.covers("Namespace")).isTrue();
		assertThat(StatusVocabulary.covers("Event")).isFalse();

		GenericKubernetesResource cordoned = obj("""
				apiVersion: v1
				kind: Node
				metadata: {name: n1}
				spec: {unschedulable: true}
				status: {conditions: [{type: Ready, status: "True"}]}
				""");
		assertThat(StatusVocabulary.state("Node", cordoned))
			.isEqualTo(new ObjectState("Ready,SchedulingDisabled", StateCount.WARN));
		assertThat(StatusVocabulary.state("Event", cordoned)).isNull();
		assertThat(StatusVocabulary.state("Pod", obj("""
				apiVersion: v1
				kind: Pod
				metadata: {name: p}
				status: {phase: Running}
				"""))).isEqualTo(new ObjectState("Running", StateCount.OK));
	}

}

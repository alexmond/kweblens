package org.alexmond.kweblens.health;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The health rules, moved server-side from the SPA.
 *
 * <p>
 * Two of these encode bugs that were live: a RUNNING Job was reported unhealthy, and a
 * CronJob could never be reported as anything but healthy. The in-between states are the
 * whole point — "finished" and "failed" are easy; the failures were in "still running"
 * and "deliberately off".
 */
class WorkloadHealthTest {

	private GenericKubernetesResource obj(String yamlish) {
		return Serialization.unmarshal(yamlish, GenericKubernetesResource.class);
	}

	@Test
	void doesNotFlagARunningJob() {
		// `succeeded` is 0 for the entire duration of a running Job, so the predicate
		// this
		// replaces turned every in-flight Job red.
		var verdict = WorkloadHealth.verdict("Job", obj("""
				apiVersion: batch/v1
				kind: Job
				metadata: {name: j}
				status: {active: 1, succeeded: 0}
				"""));
		assertThat(verdict.state()).isEqualTo(WorkloadHealth.State.OK);
	}

	@Test
	void flagsAFailedJobWithAReason() {
		var verdict = WorkloadHealth.verdict("Job", obj("""
				apiVersion: batch/v1
				kind: Job
				metadata: {name: j}
				status:
				  failed: 2
				  conditions: [{type: Failed, status: "True"}]
				"""));
		assertThat(verdict.state()).isEqualTo(WorkloadHealth.State.ATTENTION);
		assertThat(verdict.reason()).isEqualTo("failed");
	}

	@Test
	void ignoresAConditionThatIsPresentButFalse() {
		var verdict = WorkloadHealth.verdict("Job", obj("""
				apiVersion: batch/v1
				kind: Job
				metadata: {name: j}
				status: {conditions: [{type: Failed, status: "False"}]}
				"""));
		assertThat(verdict.state()).isEqualTo(WorkloadHealth.State.OK);
	}

	@Test
	void reportsASuspendedCronJobAsSuspendedRatherThanBroken() {
		// A suspended CronJob is a deliberate operator choice. Colouring it red beside
		// real
		// failures is how a health signal earns its way into being ignored.
		var verdict = WorkloadHealth.verdict("CronJob", obj("""
				apiVersion: batch/v1
				kind: CronJob
				metadata: {name: c}
				spec: {suspend: true, schedule: "0 3 * * *"}
				"""));
		assertThat(verdict.state()).isEqualTo(WorkloadHealth.State.SUSPENDED);
		assertThat(verdict.reason()).isEqualTo("suspended");
	}

	@Test
	void reportsANormalCronJobAsOk() {
		var verdict = WorkloadHealth.verdict("CronJob", obj("""
				apiVersion: batch/v1
				kind: CronJob
				metadata: {name: c}
				spec: {schedule: "0 3 * * *"}
				"""));
		assertThat(verdict.state()).isEqualTo(WorkloadHealth.State.OK);
	}

	@Test
	void namesTheContainerReasonForAPodRatherThanJustThePhase() {
		// "Pending" is far less useful than "CrashLoopBackOff", which names the fix.
		var verdict = WorkloadHealth.verdict("Pod", obj("""
				apiVersion: v1
				kind: Pod
				metadata: {name: p}
				status:
				  phase: Pending
				  containerStatuses: [{name: c, state: {waiting: {reason: CrashLoopBackOff}}}]
				"""));
		assertThat(verdict.state()).isEqualTo(WorkloadHealth.State.ATTENTION);
		assertThat(verdict.reason()).isEqualTo("CrashLoopBackOff");
	}

	@Test
	void prefersTheInitContainerReasonBecauseItIsWhatIsBlocking() {
		var verdict = WorkloadHealth.verdict("Pod", obj("""
				apiVersion: v1
				kind: Pod
				metadata: {name: p}
				status:
				  phase: Pending
				  initContainerStatuses: [{name: i, state: {waiting: {reason: CrashLoopBackOff}}}]
				  containerStatuses: [{name: c, state: {waiting: {reason: PodInitializing}}}]
				"""));
		assertThat(verdict.reason()).isEqualTo("CrashLoopBackOff");
	}

	@Test
	void doesNotTreatNormalStartupWaitingReasonsAsProblems() {
		// PodInitializing / ContainerCreating are transient. Reporting them would make
		// every
		// starting pod look broken, which is the false-alarm failure mode again.
		var verdict = WorkloadHealth.verdict("Pod", obj("""
				apiVersion: v1
				kind: Pod
				metadata: {name: p}
				status:
				  phase: Pending
				  containerStatuses: [{name: c, state: {waiting: {reason: ContainerCreating}}}]
				"""));
		assertThat(verdict.reason()).isEqualTo("Pending");
	}

	@Test
	void reportsReplicaShortfallAsAReadyRatio() {
		var verdict = WorkloadHealth.verdict("Deployment", obj("""
				apiVersion: apps/v1
				kind: Deployment
				metadata: {name: d}
				spec: {replicas: 3}
				status: {readyReplicas: 2}
				"""));
		assertThat(verdict.state()).isEqualTo(WorkloadHealth.State.ATTENTION);
		assertThat(verdict.reason()).isEqualTo("2/3 ready");
	}

	@Test
	void treatsScaledToZeroAsIdleRatherThanHealthy() {
		// Intentionally scaled down is not failing — flagging it would light up every
		// idle
		// workload — but it is not "3 of 3 ready" either. IDLE keeps it out of the
		// attention count while stopping a card from claiming it is serving.
		var verdict = WorkloadHealth.verdict("Deployment", obj("""
				apiVersion: apps/v1
				kind: Deployment
				metadata: {name: d}
				spec: {replicas: 0}
				status: {}
				"""));
		assertThat(verdict.state()).isEqualTo(WorkloadHealth.State.IDLE);
		assertThat(verdict.state()).isNotEqualTo(WorkloadHealth.State.ATTENTION);
		assertThat(verdict.label()).isEqualTo("Idle");
		assertThat(verdict.tone()).isEqualTo(StateCount.IDLE);
	}

	@Test
	void namesEachStateInTheKindsOwnVocabulary() {
		// A card counts by label, so every object in the same state must produce the SAME
		// word — and the word has to be the one that kind's users say. A deployment is
		// "Unavailable", a daemonset is "Ready", a pod is "Running".
		assertThat(WorkloadHealth.verdict("Deployment", obj("""
				apiVersion: apps/v1
				kind: Deployment
				metadata: {name: d}
				spec: {replicas: 3}
				status: {readyReplicas: 3}
				""")).label()).isEqualTo("Healthy");
		assertThat(WorkloadHealth.verdict("Deployment", obj("""
				apiVersion: apps/v1
				kind: Deployment
				metadata: {name: d}
				spec: {replicas: 3}
				status: {readyReplicas: 1}
				""")).label()).isEqualTo("Unavailable");
		assertThat(WorkloadHealth.verdict("DaemonSet", obj("""
				apiVersion: apps/v1
				kind: DaemonSet
				metadata: {name: ds}
				status: {desiredNumberScheduled: 2, numberReady: 2}
				""")).label()).isEqualTo("Ready");
	}

	@Test
	void usesTheWaitingReasonAsThePodsStateName() {
		// The state a card should show for a broken pod is the reason itself —
		// "CrashLoopBackOff" is what someone scans for, not "attention".
		var verdict = WorkloadHealth.verdict("Pod", obj("""
				apiVersion: v1
				kind: Pod
				metadata: {name: p}
				status:
				  phase: Pending
				  containerStatuses: [{name: c, state: {waiting: {reason: ImagePullBackOff}}}]
				"""));
		assertThat(verdict.label()).isEqualTo("ImagePullBackOff");
		assertThat(verdict.tone()).isEqualTo(StateCount.ERR);
	}

	@Test
	void separatesAFinishedPodFromARunningOne() {
		// Grouping Completed under Running would overstate how much is actually serving.
		var verdict = WorkloadHealth.verdict("Pod", obj("""
				apiVersion: v1
				kind: Pod
				metadata: {name: p}
				status: {phase: Succeeded}
				"""));
		assertThat(verdict.label()).isEqualTo("Completed");
		assertThat(verdict.tone()).isEqualTo(StateCount.IDLE);
	}

	@Test
	void usesTheDaemonSetVocabulary() {
		var verdict = WorkloadHealth.verdict("DaemonSet", obj("""
				apiVersion: apps/v1
				kind: DaemonSet
				metadata: {name: ds}
				status: {desiredNumberScheduled: 4, numberReady: 3}
				"""));
		assertThat(verdict.reason()).isEqualTo("3/4 ready");
	}

	@Test
	void reportsAnUnknownKindAsOkRatherThanGuessing() {
		assertThat(WorkloadHealth.supports("Gateway")).isFalse();
		var verdict = WorkloadHealth.verdict("Gateway", obj("""
				apiVersion: gateway.networking.k8s.io/v1
				kind: Gateway
				metadata: {name: g}
				"""));
		assertThat(verdict.state()).isEqualTo(WorkloadHealth.State.OK);
	}

}

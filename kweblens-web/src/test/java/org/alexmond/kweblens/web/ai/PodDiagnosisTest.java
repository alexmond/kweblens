package org.alexmond.kweblens.web.ai;

import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The diagnosis rules, against the object shapes real broken pods actually have.
 *
 * <p>
 * The fixtures below are trimmed from live objects on a cluster (see
 * {@code docs/design/failure-taxonomy.md}), because the whole point of these rules is to
 * read the fields the API server really fills in — and the ones that matter are not the
 * obvious ones. Before this, a crashloop was reported with the back-off message and an
 * unschedulable pod as "phase=Pending".
 */
class PodDiagnosisTest {

	private GenericKubernetesResource pod(String yaml) {
		return Serialization.unmarshal(yaml, GenericKubernetesResource.class);
	}

	@Test
	void reportsTheExitCodeForACrashloop() {
		// The current state says only "back-off restarting". The diagnosis is in
		// lastState.
		List<Finding> findings = PodDiagnosis.forPod(pod("""
				apiVersion: v1
				kind: Pod
				metadata: {name: web}
				status:
				  phase: Running
				  containerStatuses:
				  - name: app
				    state: {waiting: {reason: CrashLoopBackOff, message: "back-off 5m0s restarting failed container"}}
				    lastState: {terminated: {exitCode: 1, reason: Error}}
				"""));
		assertThat(findings).singleElement().satisfies((f) -> {
			assertThat(f.title()).isEqualTo("CrashLoopBackOff");
			assertThat(f.detail()).contains("last exit code 1").contains("Error");
			assertThat(f.suggestedFix()).contains("PREVIOUS");
		});
	}

	@Test
	void callsOutAKillAsSomethingOtherThanAnApplicationCrash() {
		// Exit 137 is SIGKILL — the fix is a memory limit, not the application bug a bare
		// CrashLoopBackOff sends someone looking for.
		List<Finding> findings = PodDiagnosis.forPod(pod("""
				apiVersion: v1
				kind: Pod
				metadata: {name: hungry}
				status:
				  phase: Running
				  containerStatuses:
				  - name: app
				    state: {waiting: {reason: CrashLoopBackOff}}
				    lastState: {terminated: {exitCode: 137, reason: Error}}
				"""));
		assertThat(findings).singleElement().satisfies((f) -> {
			assertThat(f.title()).contains("killed");
			assertThat(f.suggestedFix()).contains("memory");
		});
	}

	@Test
	void namesOOMKilledExplicitlyWhenKubernetesDoes() {
		List<Finding> findings = PodDiagnosis.forPod(pod("""
				apiVersion: v1
				kind: Pod
				metadata: {name: hungry}
				status:
				  phase: Running
				  containerStatuses:
				  - name: app
				    state: {waiting: {reason: CrashLoopBackOff}}
				    lastState: {terminated: {exitCode: 1, reason: OOMKilled}}
				"""));
		assertThat(findings).singleElement().satisfies((f) -> assertThat(f.title()).isEqualTo("OOMKilled"));
	}

	@Test
	void givesTheSchedulersOwnVerdictForAnUnschedulablePod() {
		// This message IS the diagnosis. Reporting "phase=Pending" and telling someone to
		// go and look is strictly less than what the object already said.
		List<Finding> findings = PodDiagnosis.forPod(pod("""
				apiVersion: v1
				kind: Pod
				metadata: {name: stuck}
				status:
				  phase: Pending
				  conditions:
				  - type: PodScheduled
				    status: "False"
				    reason: Unschedulable
				    message: "0/4 nodes are available: 4 node(s) didn't match Pod's node affinity/selector."
				"""));
		assertThat(findings).singleElement().satisfies((f) -> {
			assertThat(f.title()).isEqualTo("Unschedulable");
			assertThat(f.detail()).contains("0/4 nodes are available").contains("node affinity");
			assertThat(f.severity()).isEqualTo("critical");
		});
	}

	@Test
	void blamesTheInitContainerRatherThanTheAppContainersItBlocks() {
		// The app container says PodInitializing, which reads like "starting" — the init
		// container is what actually failed.
		List<Finding> findings = PodDiagnosis.forPod(pod("""
				apiVersion: v1
				kind: Pod
				metadata: {name: migrating}
				status:
				  phase: Pending
				  initContainerStatuses:
				  - name: migrate
				    state: {terminated: {exitCode: 2, reason: Error}}
				  containerStatuses:
				  - name: app
				    state: {waiting: {reason: PodInitializing}}
				"""));
		assertThat(findings).singleElement().satisfies((f) -> {
			assertThat(f.title()).isEqualTo("Init container failed");
			assertThat(f.object()).contains("init container migrate");
			assertThat(f.detail()).contains("exit code 2");
		});
	}

	@Test
	void producesOneFindingPerBrokenPodNotThree() {
		// A pod used to yield a phase finding AND a container finding AND its events.
		// Three
		// restatements of one problem is how a findings list stops being read.
		List<Finding> findings = PodDiagnosis.forPod(pod("""
				apiVersion: v1
				kind: Pod
				metadata: {name: pulling}
				status:
				  phase: Pending
				  containerStatuses:
				  - name: app
				    state: {waiting: {reason: ImagePullBackOff, message: "Back-off pulling image"}}
				"""));
		assertThat(findings).hasSize(1);
		assertThat(findings.get(0).title()).isEqualTo("ImagePullBackOff");
	}

	@Test
	void reportsAStartingPodAsInfoRatherThanAWarningOrNotAtAll() {
		// ContainerCreating is normal, so a warning would mark every young pod as broken.
		// But dropping it would hide a pod wedged in ContainerCreating for twenty
		// minutes,
		// which is a volume that will not mount wearing a normal-looking state. Info is
		// the
		// only answer that is neither an alarm nor a blind spot.
		assertThat(PodDiagnosis.forPod(pod("""
				apiVersion: v1
				kind: Pod
				metadata: {name: young}
				status:
				  phase: Pending
				  containerStatuses:
				  - name: app
				    state: {waiting: {reason: ContainerCreating}}
				"""))).singleElement().satisfies((f) -> {
			assertThat(f.severity()).isEqualTo("info");
			assertThat(f.title()).isEqualTo("Pod still starting");
		});
	}

	@Test
	void saysNothingAboutAHealthyPod() {
		assertThat(PodDiagnosis.forPod(pod("""
				apiVersion: v1
				kind: Pod
				metadata: {name: fine}
				status:
				  phase: Running
				  containerStatuses:
				  - name: app
				    state: {running: {startedAt: "2026-07-30T00:00:00Z"}}
				"""))).isEmpty();
	}

	@Test
	void fallsBackToThePhaseWhenNothingElseExplainsIt() {
		// Honest last resort: the pod is not running and the object says no more than
		// that.
		assertThat(PodDiagnosis.forPod(pod("""
				apiVersion: v1
				kind: Pod
				metadata: {name: odd}
				status: {phase: Unknown}
				"""))).singleElement().satisfies((f) -> {
			assertThat(f.title()).isEqualTo("Pod not running");
			assertThat(f.detail()).isEqualTo("phase=Unknown");
		});
	}

	@Test
	void toleratesAPodWithNoStatusAtAll() {
		assertThat(PodDiagnosis.forPod(pod("""
				apiVersion: v1
				kind: Pod
				metadata: {name: fresh}
				"""))).singleElement().satisfies((f) -> assertThat(f.title()).isEqualTo("Pod not running"));
	}

}

package org.alexmond.kweblens.web.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.mockwebserver.http.RecordedRequest;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.resource.ResourceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The remediation catalogue, held to its own rule: an action is proposed for the finding
 * it can fix and NOT for the ones it cannot, and nothing is applied without an explicit
 * confirmation.
 *
 * <p>
 * The negative cases are the point. A rollout restart for an ImagePullBackOff, a rollback
 * with no revision to go back to, a scale-up for a Service whose selector matches nothing
 * — each would be a suggestion that costs a confirmation and changes nothing.
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
@EnableKubernetesMockClient(crud = true)
class RemediationActionsTest {

	@Test
	void previewOfAPatchShapedActionAsksTheClusterAndReturnsWhatItSays() {
		// The whole point of #209. The old "preview" was a sentence kweblens wrote about
		// its own intention; this one is the object the API server says would result.
		RemediationPreview preview = remediation.preview("test", NS, "scale-up", "Deployment/idle");

		assertThat(preview.validated()).isTrue();
		assertThat(preview.outcome()).isEqualTo(RemediationPreview.Outcome.SERVER_VALIDATED);
		// It is the real object, not prose: YAML carrying the field being changed.
		assertThat(preview.detail()).contains("replicas");
	}

	@Test
	void previewSendsDryRunAllSoTheServerDiscardsTheResult() throws InterruptedException {
		// What the mock CAN prove: the request carries dryRun=All. It cannot prove the
		// change is discarded, because the CRUD mock does not implement dry-run semantics
		// —
		// it applies the patch regardless, which is a property of the harness and not of
		// the code. The no-mutation invariant was checked against the real cluster
		// instead:
		// a rollout-restart preview on a live Deployment left generation at 1 with no
		// restartedAt annotation.
		//
		// This DRAINS the queue and searches it rather than reading getLastRequest().
		// That
		// is the whole point: getLastRequest() is one slot of shared mock state, so any
		// request arriving after the one under test wins it. It passed locally for a
		// while
		// and then failed in CI on someone else's branch — an order-dependent test that
		// blames whoever happens to be next.
		drain();
		remediation.preview("test", NS, "scale-up", "Deployment/idle");

		assertThat(pathsSince()).anySatisfy((path) -> assertThat(path).contains("dryRun=All"));
	}

	/** Discard whatever the mock has already seen, so the next drain is only ours. */
	private void drain() throws InterruptedException {
		while (this.server.takeRequest(50, TimeUnit.MILLISECONDS) != null) {
			// Discarding on purpose.
		}
	}

	/** Every request path since the last {@link #drain()}. */
	private List<String> pathsSince() throws InterruptedException {
		List<String> paths = new ArrayList<>();
		RecordedRequest request = this.server.takeRequest(200, TimeUnit.MILLISECONDS);
		while (request != null) {
			paths.add(String.valueOf(request.getPath()));
			request = this.server.takeRequest(50, TimeUnit.MILLISECONDS);
		}
		return paths;
	}

	@Test
	void previewSaysPlainlyWhenTheClusterCouldNotBeAsked() {
		// Deleting a pod is not a patch, so there is nothing to dry-run. Reporting that
		// honestly beats emitting a sentence that reads like a server answer — which is
		// exactly what #209 was about.
		RemediationPreview preview = remediation.preview("test", NS, "restart-pod", "web-0");

		assertThat(preview.validated()).isFalse();
		assertThat(preview.outcome()).isEqualTo(RemediationPreview.Outcome.NOT_CHECKED);
		assertThat(preview.detail()).contains("cannot be validated by a server-side dry run");
	}

	@Test
	void aRollbackCannotBeDryRunEither() {
		RemediationPreview preview = remediation.preview("test", NS, "rollback", "Deployment/rolled");

		assertThat(preview.outcome()).isEqualTo(RemediationPreview.Outcome.NOT_CHECKED);
		assertThat(preview.validated()).isFalse();
	}

	@Test
	void noProposalClaimsToBeADryRunAnyMore() {
		// The proposals still describe what would happen — that is useful — but none of
		// them may label itself a dry run, because none of them consulted the cluster.
		assertThat(remediation.propose("test", NS))
			.allSatisfy((proposal) -> assertThat(proposal.preview()).doesNotContain("dry-run:"));
	}

	private static final String NS = "shop";

	KubernetesClient client;

	KubernetesMockServer server;

	@Autowired
	private ClusterRegistry registry;

	@Autowired
	private RemediationService remediation;

	@Autowired
	private ResourceService resources;

	@BeforeEach
	void setUp() {
		registry.register("test", "Test cluster", client);
		client.namespaces()
			.resource(new NamespaceBuilder().withNewMetadata().withName(NS).endMetadata().build())
			.create();

		// A crashlooping replica of a Deployment that has been rolled before.
		workload("web", 3, 1);
		crashLoop("web", "CrashLoopBackOff");

		// A bad image on a Deployment that has never been changed: nothing to roll back
		// to, and neither restart makes an unresolvable image resolvable.
		workload("img", 1, 1);
		crashLoop("img", "ImagePullBackOff");

		// The same bad image, but introduced by a revision — so undo is a real fix.
		workload("rolled", 2, 1);
		crashLoop("rolled", "ImagePullBackOff");

		// The kernel killed it for memory: a restart brings back the same limit.
		workload("mem", 6, 1);
		client.pods().resource(oomKilled()).create();

		// The scheduler could not place it; the previous template is scheduled by the
		// same rules, so no template-level action is offered.
		workload("sched", 4, 1);
		client.pods().resource(unschedulable()).create();

		// A Service whose only backing workload is scaled to zero, and one whose
		// selector matches nothing at all.
		workload("idle", 1, 0);
		client.services().resource(service("idle-svc", "idle")).create();
		client.services().resource(service("orphan-svc", "nobody")).create();
	}

	@Test
	void crashLoopingReplicaOffersBothAPodDeleteAndAWorkloadRoll() {
		List<RemediationProposal> proposals = remediation.propose("test", NS);

		assertThat(targets(proposals, "restart-pod")).contains("web-rs-0");
		assertThat(targets(proposals, "rollout-restart")).contains("Deployment/web");
	}

	@Test
	void anImagePullIsNotOfferedARestartAtEitherLevel() {
		List<RemediationProposal> proposals = remediation.propose("test", NS);

		assertThat(targets(proposals, "restart-pod")).doesNotContain("img-rs-0");
		assertThat(targets(proposals, "rollout-restart")).doesNotContain("Deployment/img");
	}

	@Test
	void rollbackIsOfferedOnlyWhenThereIsARevisionToGoBackTo() {
		List<RemediationProposal> proposals = remediation.propose("test", NS);

		assertThat(targets(proposals, "rollback")).contains("Deployment/rolled");
		assertThat(targets(proposals, "rollback")).doesNotContain("Deployment/img");
	}

	@Test
	void oomKilledIsRolledBackButNeverRestarted() {
		List<RemediationProposal> proposals = remediation.propose("test", NS);

		assertThat(targets(proposals, "rollback")).contains("Deployment/mem");
		assertThat(targets(proposals, "restart-pod")).doesNotContain("mem-rs-0");
		assertThat(targets(proposals, "rollout-restart")).doesNotContain("Deployment/mem");
	}

	@Test
	void anUnschedulablePodGetsNoProposalAtAll() {
		List<RemediationProposal> proposals = remediation.propose("test", NS);

		assertThat(targets(proposals, "restart-pod")).doesNotContain("sched-rs-0");
		assertThat(targets(proposals, "rollout-restart")).doesNotContain("Deployment/sched");
		assertThat(targets(proposals, "rollback")).doesNotContain("Deployment/sched");
	}

	@Test
	void anEmptyServiceBackedByAZeroReplicaWorkloadOffersAScaleUp() {
		List<RemediationProposal> proposals = remediation.propose("test", NS);

		assertThat(targets(proposals, "scale-up")).containsExactly("Deployment/idle");
	}

	@Test
	void aServiceWhoseSelectorMatchesNothingGetsNoScaleUp() {
		// The whole namespace produces exactly one scale-up (the test above); if the
		// orphan Service produced one too there would be two.
		assertThat(remediation.propose("test", NS).stream().filter((p) -> "scale-up".equals(p.action())).count())
			.isEqualTo(1);
	}

	@Test
	void proposalsAreNotRepeatedPerAffectedPod() {
		client.pods().resource(crashLoopPod("web", "web-rs-1", "CrashLoopBackOff")).create();

		assertThat(targets(remediation.propose("test", NS), "rollout-restart").stream()
			.filter("Deployment/web"::equals)
			.count()).isEqualTo(1);
	}

	@Test
	void workloadActionsAreSkippedWithoutANamespaceToResolveOwnersIn() {
		List<RemediationProposal> proposals = remediation.propose("test", null);

		assertThat(targets(proposals, "rollout-restart")).isEmpty();
		assertThat(targets(proposals, "rollback")).isEmpty();
		assertThat(targets(proposals, "scale-up")).isEmpty();
	}

	@Test
	void everyNewActionIsRejectedWithoutAnExplicitConfirmation() {
		for (String action : List.of("rollout-restart", "rollback", "scale-up")) {
			assertThatThrownBy(() -> remediation.apply("test", NS, action, "Deployment/web", false)).as(action)
				.isInstanceOf(ConfirmationRequiredException.class);
		}
	}

	@Test
	void aConfirmedRolloutRestartStampsThePodTemplate() {
		String result = remediation.apply("test", NS, "rollout-restart", "Deployment/web", true);

		assertThat(result).contains("Deployment/web");
		assertThat(resources.getYaml("test", WorkloadLookup.DEPLOYMENTS, NS, "web"))
			.contains("kweblens.alexmond.org/restartedAt");
	}

	@Test
	void aConfirmedScaleUpSetsOneReplica() {
		remediation.apply("test", NS, "scale-up", "Deployment/idle", true);

		assertThat(resources.getYaml("test", WorkloadLookup.DEPLOYMENTS, NS, "idle")).contains("replicas: 1");
	}

	@Test
	void aTargetThatIsNotAWorkloadIsRejected() {
		assertThatThrownBy(() -> remediation.apply("test", NS, "scale-up", "ConfigMap/settings", true))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> remediation.apply("test", NS, "rollout-restart", "web", true))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void anUnknownActionIsRejected() {
		assertThatThrownBy(() -> remediation.apply("test", NS, "delete-namespace", "Deployment/web", true))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private List<String> targets(List<RemediationProposal> proposals, String action) {
		return proposals.stream().filter((p) -> action.equals(p.action())).map(RemediationProposal::target).toList();
	}

	/** A Deployment at revision {@code revision} plus the ReplicaSet it owns. */
	private void workload(String name, int revision, int replicas) {
		Deployment deployment = new DeploymentBuilder().withNewMetadata()
			.withName(name)
			.withNamespace(NS)
			.addToAnnotations("deployment.kubernetes.io/revision", String.valueOf(revision))
			.endMetadata()
			.withNewSpec()
			.withReplicas(replicas)
			.withNewTemplate()
			.withNewMetadata()
			.addToLabels("app", name)
			.endMetadata()
			.endTemplate()
			.endSpec()
			.build();
		client.apps().deployments().resource(deployment).create();
		ReplicaSet replicaSet = new ReplicaSetBuilder().withNewMetadata()
			.withName(name + "-rs")
			.withNamespace(NS)
			.addNewOwnerReference()
			.withApiVersion("apps/v1")
			.withKind("Deployment")
			.withName(name)
			.withController(true)
			.withUid("uid-" + name)
			.endOwnerReference()
			.endMetadata()
			.build();
		client.apps().replicaSets().resource(replicaSet).create();
	}

	private void crashLoop(String workload, String reason) {
		client.pods().resource(crashLoopPod(workload, workload + "-rs-0", reason)).create();
	}

	private Pod crashLoopPod(String workload, String podName, String reason) {
		return ownedPod(workload, podName).withNewStatus()
			.withPhase("Pending")
			.addNewContainerStatus()
			.withName("app")
			.withNewState()
			.withNewWaiting()
			.withReason(reason)
			.endWaiting()
			.endState()
			.endContainerStatus()
			.endStatus()
			.build();
	}

	private Pod oomKilled() {
		return ownedPod("mem", "mem-rs-0").withNewStatus()
			.withPhase("Pending")
			.addNewContainerStatus()
			.withName("app")
			.withNewState()
			.withNewWaiting()
			.withReason("CrashLoopBackOff")
			.endWaiting()
			.endState()
			.withNewLastState()
			.withNewTerminated()
			.withReason("OOMKilled")
			.withExitCode(137)
			.endTerminated()
			.endLastState()
			.endContainerStatus()
			.endStatus()
			.build();
	}

	private Pod unschedulable() {
		return ownedPod("sched", "sched-rs-0").withNewStatus()
			.withPhase("Pending")
			.addNewCondition()
			.withType("PodScheduled")
			.withStatus("False")
			.withReason("Unschedulable")
			.withMessage("0/3 nodes are available: 3 Insufficient cpu.")
			.endCondition()
			.endStatus()
			.build();
	}

	private PodBuilder ownedPod(String workload, String podName) {
		return new PodBuilder().withNewMetadata()
			.withName(podName)
			.withNamespace(NS)
			.addNewOwnerReference()
			.withApiVersion("apps/v1")
			.withKind("ReplicaSet")
			.withName(workload + "-rs")
			.withController(true)
			.withUid("uid-" + workload + "-rs")
			.endOwnerReference()
			.endMetadata();
	}

	private Service service(String name, String selects) {
		return new ServiceBuilder().withNewMetadata()
			.withName(name)
			.withNamespace(NS)
			.endMetadata()
			.withNewSpec()
			.addToSelector("app", selects)
			.endSpec()
			.build();
	}

}

package org.alexmond.kweblens.web.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.web.security.AuditService;

/**
 * Turns diagnosis findings into <b>proposed</b> remediations, and applies an approved one
 * under guardrails. Safety model (non-negotiable): proposing is read-only; applying
 * requires an explicit {@code confirm} (never autonomous), goes through the auth-gated
 * write path, and is audited. Applying shows a dry-run preview first via
 * {@link RemediationProposal#preview()}.
 *
 * <p>
 * <b>The rule every action here is held to: a fix is only offered when it can actually
 * work.</b> The precondition for each one is documented on the method that decides it,
 * together with what it is deliberately NOT offered for. A proposal that cannot work
 * costs an operator a confirmation, a change to a running system and the time to discover
 * the symptom is unchanged — strictly worse than proposing nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemediationService {

	private static final String RESTART_POD = "restart-pod";

	private static final String ROLLOUT_RESTART = "rollout-restart";

	private static final String ROLLBACK = "rollback";

	private static final String SCALE_UP = "scale-up";

	/**
	 * The reason a Service has nothing behind it that a scale-up could address, as
	 * {@link org.alexmond.kweblens.health.NetworkHealthService} words it. Its other
	 * reason — pods matched but none ready — is a readiness problem, and scaling a
	 * workload whose pods already fail their probe just produces more failing pods.
	 */
	private static final String NO_ENDPOINTS = "no endpoints";

	/**
	 * Findings caused by the pod <b>template</b>, and therefore fixable by restoring the
	 * template that came before it.
	 *
	 * <p>
	 * A bad image, a missing ConfigMap reference, a memory limit that is too low and a
	 * crash on start are all things a revision introduced. Scheduling failures are
	 * excluded: the scheduler's verdict usually names a cluster-side constraint — a
	 * taint, exhausted capacity — that the previous template does not change either, and
	 * the case where it would (someone raised the resource requests) is not
	 * distinguishable from the case where it would not without diffing the revisions. So
	 * it is not offered. "Pod not running" is excluded too: it is a symptom with no
	 * attributed cause, and rolling a workload back on a guess is a spec change made for
	 * no stated reason.
	 */
	private static final Set<String> TEMPLATE_FAILURES = Set.of("CrashLoopBackOff",
			"CrashLoopBackOff — killed (exit 137)", "OOMKilled", "ImagePullBackOff", "ErrImagePull", "InvalidImageName",
			"CreateContainerConfigError", "CreateContainerError", "Init container failed");

	private final DiagnoseService diagnose;

	private final ClusterRegistry clusters;

	private final ResourceService resources;

	private final WorkloadLookup workloads;

	private final AuditService audit;

	/**
	 * Read-only: derive remediation proposals from the current findings. Nothing changes.
	 *
	 * <p>
	 * Everything above a single pod delete needs a namespace to resolve the owning object
	 * in, and a finding carries only {@code Kind/name}. So when the caller asked for a
	 * cluster-wide diagnosis the workload-level actions are skipped rather than guessed
	 * at across namespaces.
	 */
	public List<RemediationProposal> propose(String clusterId, String namespace) {
		List<RemediationProposal> proposals = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		boolean scoped = namespace != null && !namespace.isBlank();
		for (Finding finding : diagnose.diagnose(clusterId, namespace).findings()) {
			String object = finding.object();
			if (object == null) {
				continue;
			}
			if (object.startsWith("Pod/")) {
				proposeForPod(proposals, seen, clusterId, namespace, scoped, finding);
			}
			else if (scoped && object.startsWith("Service/")) {
				proposeForService(proposals, seen, clusterId, namespace, finding);
			}
		}
		return proposals;
	}

	/**
	 * Ask the API server what an action <em>would</em> do, without doing it.
	 *
	 * <p>
	 * This is the real thing, not a description: the patch goes to the cluster with
	 * {@code dryRun=All}, so admission webhooks, validation and quota all run and the
	 * server returns the object that would have resulted. A change a webhook rejects
	 * fails here, before anyone approves it.
	 *
	 * <p>
	 * Only the two patch-shaped actions can be previewed this way. Deleting a pod and
	 * rolling a workload back are not patches — the first is a DELETE, the second asks
	 * fabric8 to resolve a previous revision — so for those this reports plainly that the
	 * cluster was <b>not</b> consulted rather than returning prose that reads like a
	 * server answer. Saying "we did not check" is more useful than implying we did.
	 */
	public RemediationPreview preview(String clusterId, String namespace, String action, String target) {
		return switch (action) {
			case SCALE_UP -> serverPreview(clusterId, namespace, target, ResourceService.scalePatch(1));
			case ROLLOUT_RESTART ->
				serverPreview(clusterId, namespace, target, ResourceService.restartPatch(Instant.now()));
			case RESTART_POD -> RemediationPreview
				.notChecked("Deleting a pod is not a patch, so it cannot be validated by a server-side dry run."
						+ " Pod '" + target + "' would be deleted and recreated by its owner.");
			case ROLLBACK -> RemediationPreview
				.notChecked("A rollback is resolved from the workload's revision history rather than sent as a patch,"
						+ " so it cannot be validated by a server-side dry run.");
			default -> throw new IllegalArgumentException("Unsupported remediation action: " + action);
		};
	}

	private RemediationPreview serverPreview(String clusterId, String namespace, String target, String patch) {
		WorkloadRef workload = workload(target);
		try {
			String result = resources.dryRunPatch(clusterId, descriptor(workload), namespace, workload.name(), patch);
			return RemediationPreview.serverValidated(result);
		}
		catch (KubernetesClientException ex) {
			// A rejection IS the useful answer — it is exactly what the operator needed
			// to
			// know before approving, so it is a result rather than an error.
			return RemediationPreview.rejected(ex.getMessage());
		}
	}

	/**
	 * Apply an approved remediation. Requires {@code confirm=true}; audited.
	 * @throws ConfirmationRequiredException if not confirmed
	 */
	public String apply(String clusterId, String namespace, String action, String target, boolean confirm) {
		if (!confirm) {
			throw new ConfirmationRequiredException();
		}
		return switch (action) {
			case RESTART_POD -> restartPod(clusterId, namespace, target);
			case ROLLOUT_RESTART -> rolloutRestart(clusterId, namespace, target);
			case ROLLBACK -> rollback(clusterId, namespace, target);
			case SCALE_UP -> scaleUp(clusterId, namespace, target);
			default -> throw new IllegalArgumentException("Unsupported remediation action: " + action);
		};
	}

	private String restartPod(String clusterId, String namespace, String target) {
		clusters.require(clusterId).pods().inNamespace(namespace).withName(target).delete();
		audit.record(clusterId, RESTART_POD, AuditService.ref("Pod", namespace, target));
		return "Deleted pod '" + target + "'; its controller will recreate it.";
	}

	private String rolloutRestart(String clusterId, String namespace, String target) {
		WorkloadRef workload = workload(target);
		resources.rolloutRestart(clusterId, descriptor(workload), namespace, workload.name());
		audit.record(clusterId, ROLLOUT_RESTART, AuditService.ref(workload.kind(), namespace, workload.name()));
		return "Rolled " + workload.ref() + " — its pods are being replaced under its update strategy.";
	}

	private String rollback(String clusterId, String namespace, String target) {
		WorkloadRef workload = workload(target);
		resources.rollback(clusterId, descriptor(workload), namespace, workload.name());
		audit.record(clusterId, ROLLBACK, AuditService.ref(workload.kind(), namespace, workload.name()));
		return "Rolled " + workload.ref() + " back to its previous revision.";
	}

	private String scaleUp(String clusterId, String namespace, String target) {
		WorkloadRef workload = workload(target);
		resources.scale(clusterId, descriptor(workload), namespace, workload.name(), 1);
		audit.record(clusterId, SCALE_UP, AuditService.ref(workload.kind(), namespace, workload.name()));
		return "Scaled " + workload.ref() + " to 1 replica.";
	}

	private WorkloadRef workload(String target) {
		WorkloadRef workload = WorkloadRef.parse(target);
		if (workload == null || WorkloadLookup.descriptorFor(workload.kind()) == null) {
			throw new IllegalArgumentException(
					"Remediation target must be a Deployment, StatefulSet or DaemonSet as 'Kind/name', got: " + target);
		}
		return workload;
	}

	private ResourceDescriptor descriptor(WorkloadRef workload) {
		return WorkloadLookup.descriptorFor(workload.kind());
	}

	/**
	 * The pod-level and workload-level proposals for one pod finding.
	 *
	 * <p>
	 * Both a pod delete and a rollout restart can be offered for the same finding, and
	 * that is intentional rather than duplication: deleting one pod is the surgical
	 * option when one replica out of many is wedged, while a rollout restart is one
	 * audited action that clears every replica and respects the workload's
	 * {@code maxUnavailable} instead of dropping capacity all at once. Which is right
	 * depends on how many replicas are affected, which the operator can see and this
	 * cannot.
	 */
	private void proposeForPod(List<RemediationProposal> proposals, Set<String> seen, String clusterId,
			String namespace, boolean scoped, Finding finding) {
		String pod = podName(finding.object());
		boolean restartable = isRestartable(finding);
		if (restartable) {
			add(proposals, seen, new RemediationProposal(RESTART_POD, namespace, pod,
					"Delete pod '" + pod + "' so its controller recreates it (clears a stuck/crashed pod).",
					"Pod '" + pod + "' in '" + namespace + "' would be deleted and recreated by its owner.", "low"));
		}
		if (!scoped) {
			return;
		}
		// Nothing below can be proposed for a finding that is neither restartable nor a
		// template failure, and ownerOf is a cluster call — worth skipping now that a
		// healthy pod can carry findings too (a privileged container has no remediation).
		if (!restartable && !TEMPLATE_FAILURES.contains(finding.title())) {
			return;
		}
		Optional<WorkloadRef> owner = workloads.ownerOf(clusterId, namespace, pod);
		if (owner.isEmpty()) {
			return;
		}
		WorkloadRef workload = owner.get();
		if (restartable) {
			add(proposals, seen,
					new RemediationProposal(ROLLOUT_RESTART, namespace, workload.ref(),
							"Roll " + workload.ref() + " — replace all of its pods under its update strategy.",
							workload.ref() + " in '" + namespace
									+ "' would have its pod template stamped with a restart annotation; "
									+ "its controller then replaces the pods, honouring maxUnavailable.",
							"low"));
		}
		if (TEMPLATE_FAILURES.contains(finding.title())
				&& workloads.hasRolloutHistory(clusterId, namespace, workload)) {
			add(proposals, seen, new RemediationProposal(ROLLBACK, namespace, workload.ref(),
					"Roll " + workload.ref() + " back to its previous revision — '" + finding.title()
							+ "' is a property of the current pod template.",
					workload.ref() + " in '" + namespace
							+ "' would be reverted to the revision before the current one (kubectl rollout undo).",
					"medium"));
		}
	}

	/**
	 * Scale a workload back up when that is why a Service has nothing behind it.
	 *
	 * <p>
	 * Offered only for the "no endpoints" reason, and only when exactly one workload
	 * carries the Service's selector and sits at zero replicas — see
	 * {@link WorkloadLookup#idleWorkloadBehind}, which is where the "would this work?"
	 * question is answered. Not offered when pods matched but are unready (scaling
	 * produces more pods failing the same probe), when nothing matches the selector (the
	 * selector is what is wrong), or when the match is ambiguous.
	 */
	private void proposeForService(List<RemediationProposal> proposals, Set<String> seen, String clusterId,
			String namespace, Finding finding) {
		if (!NO_ENDPOINTS.equals(finding.detail())) {
			return;
		}
		String service = finding.object().substring("Service/".length());
		Optional<WorkloadRef> idle = workloads.idleWorkloadBehind(clusterId, namespace, service);
		if (idle.isEmpty()) {
			return;
		}
		WorkloadRef workload = idle.get();
		add(proposals, seen, new RemediationProposal(SCALE_UP, namespace, workload.ref(),
				"Scale " + workload.ref() + " to 1 replica — it is the only workload matching Service '" + service
						+ "' and it is scaled to zero, which is why the Service has no endpoints.",
				workload.ref() + " in '" + namespace
						+ "' would have spec.replicas set to 1; the Service gains an endpoint once the pod is ready.",
				"low"));
	}

	/**
	 * One proposal per action+target. Several pods of one Deployment produce one finding
	 * each, and three identical "roll this Deployment" rows is a list that stops being
	 * read.
	 */
	private void add(List<RemediationProposal> proposals, Set<String> seen, RemediationProposal proposal) {
		if (seen.add(proposal.action() + "|" + proposal.target())) {
			proposals.add(proposal);
		}
	}

	/**
	 * The pod out of a finding's object string.
	 *
	 * <p>
	 * A finding names the container when it knows it — "Pod/web container app" — because
	 * that is what a reader needs. Restarting is a pod-level action, so anything after
	 * the name is dropped rather than being pasted into a delete call.
	 */
	private String podName(String object) {
		String rest = object.substring("Pod/".length());
		int space = rest.indexOf(' ');
		return (space > 0) ? rest.substring(0, space) : rest;
	}

	/**
	 * Titles worth offering a restart for.
	 *
	 * <p>
	 * Deliberately narrow. A restart clears a stuck process; it does nothing for a bad
	 * image name, a missing ConfigMap, an unschedulable pod or a container the kernel is
	 * killing for memory — proposing one there would be a suggestion that cannot work,
	 * which is worse than no suggestion. The same list gates the workload-level restart,
	 * because stamping a new pod template does not make a bad image resolvable either.
	 */
	private boolean isRestartable(Finding finding) {
		if (finding.object() == null || !finding.object().startsWith("Pod/")) {
			return false;
		}
		String title = finding.title();
		return "CrashLoopBackOff".equals(title) || "Pod not running".equals(title);
	}

}

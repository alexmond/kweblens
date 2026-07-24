package org.alexmond.kweblens.web.ai;

import java.util.ArrayList;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.web.security.AuditService;

/**
 * Turns diagnosis findings into <b>proposed</b> remediations, and applies an approved one
 * under guardrails. Safety model (non-negotiable): proposing is read-only; applying
 * requires an explicit {@code confirm} (never autonomous), goes through the auth-gated
 * write path, and is audited. Applying shows a dry-run preview first via
 * {@link RemediationProposal#preview()}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemediationService {

	private static final String RESTART_POD = "restart-pod";

	private final DiagnoseService diagnose;

	private final ClusterRegistry clusters;

	private final AuditService audit;

	/**
	 * Read-only: derive remediation proposals from the current findings. Nothing changes.
	 */
	public List<RemediationProposal> propose(String clusterId, String namespace) {
		List<RemediationProposal> proposals = new ArrayList<>();
		for (Finding finding : diagnose.diagnose(clusterId, namespace).findings()) {
			if (isRestartable(finding)) {
				String pod = finding.object().substring("Pod/".length());
				proposals.add(new RemediationProposal(RESTART_POD, namespace, pod,
						"Delete pod '" + pod + "' so its controller recreates it (clears a stuck/crashed pod).",
						"dry-run: pod '" + pod + "' in '" + namespace
								+ "' would be deleted and recreated by its owner.",
						"low"));
			}
		}
		return proposals;
	}

	/**
	 * Apply an approved remediation. Requires {@code confirm=true}; audited.
	 * @throws ConfirmationRequiredException if not confirmed
	 */
	public String apply(String clusterId, String namespace, String action, String target, boolean confirm) {
		if (!confirm) {
			throw new ConfirmationRequiredException();
		}
		if (!RESTART_POD.equals(action)) {
			throw new IllegalArgumentException("Unsupported remediation action: " + action);
		}
		clusters.require(clusterId).pods().inNamespace(namespace).withName(target).delete();
		audit.record(clusterId, RESTART_POD, "Pod/" + namespace + "/" + target);
		return "Deleted pod '" + target + "'; its controller will recreate it.";
	}

	private boolean isRestartable(Finding finding) {
		return finding.object() != null && finding.object().startsWith("Pod/")
				&& ("CrashLoopBackOff".equals(finding.title()) || "Pod not running".equals(finding.title()));
	}

}

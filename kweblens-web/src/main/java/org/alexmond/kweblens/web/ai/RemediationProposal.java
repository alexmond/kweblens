package org.alexmond.kweblens.web.ai;

/**
 * A proposed remediation the operator can approve. Proposing is read-only; nothing
 * changes until an explicit, authenticated apply. The {@code preview} is the dry-run of
 * what applying would do.
 *
 * @param action machine action id ({@code restart-pod}, {@code rollout-restart},
 * {@code rollback}, {@code scale-up})
 * @param namespace the target namespace
 * @param target the target object — a bare pod name for {@code restart-pod}, and
 * {@code Kind/name} for the workload-level actions, which need the kind to know which API
 * path to patch
 * @param description what the action does, in words
 * @param preview a dry-run preview of the change
 * @param risk {@code low} / {@code medium} / {@code high}
 */
public record RemediationProposal(String action, String namespace, String target, String description, String preview,
		String risk) {
}

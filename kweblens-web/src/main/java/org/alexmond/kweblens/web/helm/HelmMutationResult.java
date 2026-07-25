package org.alexmond.kweblens.web.helm;

/**
 * The outcome of a Helm install/upgrade/rollback. When {@code dryRun} is true nothing was
 * persisted and {@code manifest} holds the rendered manifest for preview; when false the
 * change was applied and {@code revision}/{@code status} describe the resulting release.
 *
 * @param dryRun whether this was a preview (no cluster changes) or an applied change
 * @param name release name
 * @param namespace release namespace
 * @param revision resulting revision (0 for a dry-run that did not compute one)
 * @param status resulting release status, or null
 * @param manifest the rendered Kubernetes manifest, or null
 */
public record HelmMutationResult(boolean dryRun, String name, String namespace, int revision, String status,
		String manifest) {
}

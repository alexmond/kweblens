package org.alexmond.kweblens.web.helm;

/**
 * One Helm release revision, projected for the dashboard/API.
 *
 * @param name release name
 * @param namespace release namespace
 * @param revision revision number
 * @param status Helm release status (e.g. {@code DEPLOYED})
 * @param chart chart name (e.g. {@code traefik})
 * @param chartVersion chart version (e.g. {@code 27.0.2})
 * @param appVersion the chart's app version
 * @param updated last-deployed timestamp (ISO-8601), or null
 * @param managedByKweblens whether this release was installed/upgraded through kweblens
 * (stamped with a kweblens release label), vs one created externally (helm CLI, another
 * tool)
 * @param latestVersion the newest chart version found in the configured repositories, or
 * null when the chart isn't in any known repo
 * @param latestRepository the repository the newest version was found in, or null
 * @param updateAvailable whether {@code latestVersion} is newer than {@code chartVersion}
 */
public record HelmReleaseSummary(String name, String namespace, int revision, String status, String chart,
		String chartVersion, String appVersion, String updated, boolean managedByKweblens, String latestVersion,
		String latestRepository, boolean updateAvailable) {
}

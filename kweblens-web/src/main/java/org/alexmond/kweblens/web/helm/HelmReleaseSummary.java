package org.alexmond.kweblens.web.helm;

/**
 * One Helm release revision, projected for the dashboard/API.
 *
 * @param name release name
 * @param namespace release namespace
 * @param revision revision number
 * @param status Helm release status (e.g. {@code DEPLOYED})
 * @param chart chart name-version (e.g. {@code traefik-27.0.2})
 * @param appVersion the chart's app version
 * @param updated last-deployed timestamp (ISO-8601), or null
 */
public record HelmReleaseSummary(String name, String namespace, int revision, String status, String chart,
		String appVersion, String updated) {
}

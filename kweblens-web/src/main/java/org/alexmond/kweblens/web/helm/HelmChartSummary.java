package org.alexmond.kweblens.web.helm;

/**
 * One chart version from a repository index, projected for the charts browser.
 *
 * @param name chart name
 * @param version chart version
 * @param appVersion the packaged application version, or null
 * @param description chart description, or null
 * @param repository the configured repo name this chart came from
 */
public record HelmChartSummary(String name, String version, String appVersion, String description, String repository) {
}

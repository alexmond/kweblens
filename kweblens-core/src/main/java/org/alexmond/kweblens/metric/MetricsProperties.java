package org.alexmond.kweblens.metric;

import java.util.Locale;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How kweblens finds the Prometheus-compatible backend it queries for charts.
 *
 * <p>
 * Discovery by service name is the zero-config default and is right almost always, but it
 * is a guess. It fails <em>closed</em> — a wrong service 404s and the charts read
 * "unavailable" rather than showing another cluster's numbers — so the risk is a blank
 * page, not a wrong one. The case it cannot handle is a cluster with <b>two</b> valid
 * backends of different scope, typically a global Thanos querier alongside a local
 * Prometheus: both answer, and the one picked depends on API ordering. That is what this
 * exists to settle.
 */
@Data
@ConfigurationProperties(prefix = "kweblens.metrics")
public class MetricsProperties {

	/**
	 * The backend to query, as {@code namespace/service:port} — e.g.
	 * {@code monitoring/prometheus-k8s:9090}. Overrides name-based discovery.
	 *
	 * <p>
	 * A Service reference rather than a URL, because the query goes through the
	 * kube-apiserver's service proxy: cluster RBAC is then the only credential, and no
	 * network path to the backend is needed. An {@code http(s)://} value is <b>not</b>
	 * supported — reaching a backend outside the cluster needs bearer/basic/mTLS handling
	 * and a place to keep the credential, which is deliberately not built until a
	 * deployment needs it (see {@code docs/design/metrics-sources.md}). Such a value is
	 * reported as unsupported rather than quietly ignored.
	 */
	private String prometheusService;

	/** True when a value is set and is a Service reference this can actually use. */
	public boolean hasUsableService() {
		return this.prometheusService != null && !this.prometheusService.isBlank() && !isExternalUrl();
	}

	/**
	 * True when the configured value is a URL, which is the unbuilt direct-access path.
	 */
	public boolean isExternalUrl() {
		// Locale.ROOT, not the default: in a Turkish locale "HTTP".toLowerCase() yields a
		// dotless i and the prefix check silently stops matching.
		String value = (this.prometheusService != null) ? this.prometheusService.trim().toLowerCase(Locale.ROOT) : "";
		return value.startsWith("http://") || value.startsWith("https://");
	}

}

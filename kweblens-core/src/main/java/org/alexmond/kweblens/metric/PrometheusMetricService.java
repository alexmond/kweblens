package org.alexmond.kweblens.metric;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import lombok.extern.slf4j.Slf4j;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * Time-series metrics from a Prometheus-compatible backend (Prometheus, VictoriaMetrics,
 * Thanos). The backend service is auto-discovered by name and queried through the
 * kube-apiserver <b>service proxy</b>
 * ({@code /api/v1/namespaces/{ns}/services/{svc}:{port}/proxy/...}), so it works with
 * cluster RBAC alone — no direct network path to the backend is needed. Everything
 * degrades to {@link MetricSeries#unavailable()} when no backend is found, so charts show
 * a graceful "not configured" state rather than failing.
 */
@Slf4j
@org.springframework.stereotype.Service
public class PrometheusMetricService {

	private static final List<String> KNOWN = List.of("prometheus-operated", "prometheus-k8s", "prometheus-server",
			"vmsingle", "victoria-metrics-single", "thanos-query", "thanos-query-frontend");

	private static final List<String> EXCLUDE = List.of("node-exporter", "operator", "alertmanager", "pushgateway",
			"kube-state-metrics", "adapter");

	private final ClusterRegistry clusters;

	private final ObjectMapper mapper = new ObjectMapper();

	public PrometheusMetricService(ClusterRegistry clusters) {
		this.clusters = clusters;
	}

	/** The discovered backend as {@code namespace/service:port}, or empty if none. */
	public Optional<String> endpoint(String clusterId) {
		try {
			return clusters.require(clusterId)
				.services()
				.inAnyNamespace()
				.list()
				.getItems()
				.stream()
				.filter(PrometheusMetricService::isPromLike)
				.map(PrometheusMetricService::toAddress)
				.filter((a) -> a != null)
				.findFirst();
		}
		catch (RuntimeException ex) {
			log.warn("Prometheus discovery failed for cluster '{}': {}", clusterId, ex.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * Run a range query against the discovered backend; empty/unavailable on any failure.
	 */
	public MetricSeries queryRange(String clusterId, String promql, String unit, int minutes) {
		Optional<String> address = endpoint(clusterId);
		if (address.isEmpty()) {
			return MetricSeries.unavailable();
		}
		try {
			long end = Instant.now().getEpochSecond();
			long start = end - Math.max(1, minutes) * 60L;
			long step = Math.max(15, (end - start) / 60);
			String url = proxyBase(address.get()) + "/api/v1/query_range?query=" + enc(promql) + "&start=" + start
					+ "&end=" + end + "&step=" + step;
			String body = clusters.require(clusterId).raw(url);
			return parse(body, unit);
		}
		catch (RuntimeException ex) {
			log.warn("Prometheus query failed on cluster '{}': {}", clusterId, ex.getMessage());
			return MetricSeries.unavailable();
		}
	}

	MetricSeries parse(String body, String unit) {
		if (body == null || body.isBlank()) {
			return MetricSeries.unavailable();
		}
		try {
			JsonNode result = mapper.readTree(body).path("data").path("result");
			List<MetricPoint> points = new ArrayList<>();
			if (result.isArray() && !result.isEmpty()) {
				for (JsonNode value : result.get(0).path("values")) {
					points.add(new MetricPoint(value.get(0).asLong(), value.get(1).asDouble()));
				}
			}
			return new MetricSeries(true, unit, points);
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
			log.debug("Unparseable Prometheus response: {}", ex.getMessage());
			return MetricSeries.unavailable();
		}
	}

	private static String proxyBase(String address) {
		String ns = address.substring(0, address.indexOf('/'));
		String rest = address.substring(address.indexOf('/') + 1);
		String svc = rest.substring(0, rest.lastIndexOf(':'));
		String port = rest.substring(rest.lastIndexOf(':') + 1);
		return "/api/v1/namespaces/" + ns + "/services/" + svc + ":" + port + "/proxy";
	}

	private static boolean isPromLike(Service svc) {
		String name = (svc.getMetadata() != null) ? svc.getMetadata().getName() : null;
		if (name == null || svc.getSpec() == null) {
			return false;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		if (EXCLUDE.stream().anyMatch(lower::contains)) {
			return false;
		}
		return KNOWN.stream().anyMatch(lower::contains) || lower.contains("prometheus");
	}

	private static String toAddress(Service svc) {
		List<ServicePort> ports = svc.getSpec().getPorts();
		if (ports == null || ports.isEmpty()) {
			return null;
		}
		Integer port = ports.stream()
			.filter((p) -> "http".equals(p.getName()) || "web".equals(p.getName()))
			.map(ServicePort::getPort)
			.findFirst()
			.orElse(ports.get(0).getPort());
		if (port == null) {
			return null;
		}
		return svc.getMetadata().getNamespace() + "/" + svc.getMetadata().getName() + ":" + port;
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

}

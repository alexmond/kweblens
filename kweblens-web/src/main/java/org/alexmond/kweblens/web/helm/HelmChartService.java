package org.alexmond.kweblens.web.helm;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.GZIPInputStream;

import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * The charts browser: lists charts from the configured HTTP chart repositories
 * ({@link HelmProperties}) by fetching each repo's {@code index.yaml}. Only the newest
 * version of each chart is surfaced (as {@code helm search repo} does). Each repo's index
 * is cached for {@link HelmProperties#getIndexCacheSeconds()} to avoid refetching on
 * every request.
 *
 * <p>
 * Installing a chart (a future slice) pulls and renders it through jhelm; browsing only
 * needs the repository index, which is a static document, so it is fetched directly.
 */
@Slf4j
@Service
public class HelmChartService {

	private final HelmProperties properties;

	private final RestClient restClient = RestClient.builder().build();

	/** Repository indexes are large (tens of MB); lift SnakeYAML's default 3 MB cap. */
	private static final int MAX_INDEX_CODE_POINTS = 64 * 1024 * 1024;

	private final ConcurrentMap<String, Cached> cache = new ConcurrentHashMap<>();

	public HelmChartService(HelmProperties properties) {
		this.properties = properties;
	}

	/**
	 * All charts across the configured repos, optionally filtered by a case-insensitive
	 * query.
	 */
	public List<HelmChartSummary> listCharts(String query) {
		List<HelmChartSummary> all = new ArrayList<>();
		for (HelmProperties.Repository repo : properties.getRepositories()) {
			all.addAll(chartsFor(repo));
		}
		String q = (query != null) ? query.trim().toLowerCase(Locale.ROOT) : "";
		return all.stream()
			.filter((chart) -> q.isEmpty() || matches(chart, q))
			.sorted(Comparator.comparing(HelmChartSummary::name).thenComparing(HelmChartSummary::repository))
			.toList();
	}

	private boolean matches(HelmChartSummary chart, String q) {
		return chart.name().toLowerCase(Locale.ROOT).contains(q)
				|| (chart.description() != null && chart.description().toLowerCase(Locale.ROOT).contains(q));
	}

	private List<HelmChartSummary> chartsFor(HelmProperties.Repository repo) {
		if (!StringUtils.hasText(repo.getName()) || !StringUtils.hasText(repo.getUrl())) {
			return List.of();
		}
		Cached cached = cache.get(repo.getName());
		if (cached != null && cached.expiry > now()) {
			return cached.charts;
		}
		List<HelmChartSummary> charts = fetch(repo);
		cache.put(repo.getName(), new Cached(charts, now() + properties.getIndexCacheSeconds() * 1000L));
		return charts;
	}

	private List<HelmChartSummary> fetch(HelmProperties.Repository repo) {
		String url = stripTrailingSlash(repo.getUrl()) + "/index.yaml";
		try {
			byte[] raw = restClient.get().uri(url).retrieve().body(byte[].class);
			return parse(repo.getName(), decode(raw));
		}
		catch (IOException | RuntimeException ex) {
			log.warn("Could not load Helm index for repo '{}' ({}): {}", repo.getName(), url, ex.getMessage());
			return List.of();
		}
	}

	/**
	 * Decode the index body, transparently gunzipping when the client handed us a gzip
	 * stream (some repos serve {@code index.yaml} gzip-encoded, and the HTTP client does
	 * not always decompress it — a gzip body would otherwise fail YAML parsing).
	 */
	private String decode(byte[] raw) throws IOException {
		if (raw == null || raw.length == 0) {
			return null;
		}
		boolean gzip = raw.length >= 2 && (raw[0] & 0xff) == 0x1f && (raw[1] & 0xff) == 0x8b;
		if (!gzip) {
			return new String(raw, StandardCharsets.UTF_8);
		}
		try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@SuppressWarnings("unchecked")
	private List<HelmChartSummary> parse(String repoName, String body) {
		if (!StringUtils.hasText(body)) {
			return List.of();
		}
		LoaderOptions options = new LoaderOptions();
		options.setCodePointLimit(MAX_INDEX_CODE_POINTS);
		Object root = new Yaml(new SafeConstructor(options)).load(body);
		if (!(root instanceof Map<?, ?> map) || !(map.get("entries") instanceof Map<?, ?> entries)) {
			return List.of();
		}
		List<HelmChartSummary> charts = new ArrayList<>();
		for (Map.Entry<?, ?> entry : entries.entrySet()) {
			if (entry.getValue() instanceof List<?> versions && !versions.isEmpty()
					&& versions.get(0) instanceof Map<?, ?> latest) {
				charts.add(toSummary(repoName, (Map<String, Object>) latest));
			}
		}
		return charts;
	}

	private HelmChartSummary toSummary(String repoName, Map<String, Object> entry) {
		return new HelmChartSummary(str(entry.get("name")), str(entry.get("version")), str(entry.get("appVersion")),
				str(entry.get("description")), repoName);
	}

	private String str(Object value) {
		return (value != null) ? value.toString() : null;
	}

	private String stripTrailingSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	private long now() {
		return System.currentTimeMillis();
	}

	private record Cached(List<HelmChartSummary> charts, long expiry) {
	}

}

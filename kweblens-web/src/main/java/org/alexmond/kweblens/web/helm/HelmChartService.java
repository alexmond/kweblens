package org.alexmond.kweblens.web.helm;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * The charts browser: lists charts from the configured HTTP chart repositories
 * ({@link HelmProperties}) by fetching each repo's {@code index.yaml}. Only the newest
 * version of each chart is surfaced (as {@code helm search repo} does).
 *
 * <p>
 * Repo indexes are large (tens of MB for prometheus-community/bitnami), so fetching them
 * must never block a request for long: indexes are cached per repo, fetched across repos
 * in parallel, served <strong>stale-while-revalidating</strong> (an expired cache is
 * returned immediately and refreshed in the background), and warmed once on startup. That
 * keeps the {@code /helm/charts} endpoint fast instead of timing out on a cold fetch.
 */
@Slf4j
@Service
public class HelmChartService {

	/** Repository indexes are large (tens of MB); lift SnakeYAML's default 3 MB cap. */
	private static final int MAX_INDEX_CODE_POINTS = 64 * 1024 * 1024;

	private final HelmProperties properties;

	private final RestClient restClient;

	private final ConcurrentMap<String, Cached> cache = new ConcurrentHashMap<>();

	/** Repos currently being refreshed in the background, so we don't stack refreshes. */
	private final Set<String> refreshing = ConcurrentHashMap.newKeySet();

	private final ExecutorService refreshExecutor = Executors.newVirtualThreadPerTaskExecutor();

	public HelmChartService(HelmProperties properties) {
		this.properties = properties;
		// Bound each index fetch so one slow/unreachable repo can't hang the endpoint.
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(5_000);
		factory.setReadTimeout(15_000);
		this.restClient = RestClient.builder().requestFactory(factory).build();
	}

	/** Warm the cache once at startup so the first charts request is already fast. */
	@PostConstruct
	void warm() {
		refreshExecutor.submit(() -> {
			try {
				listCharts(null);
			}
			catch (RuntimeException ex) {
				log.debug("Helm charts warm-up skipped: {}", ex.getMessage());
			}
		});
	}

	@PreDestroy
	void shutdown() {
		refreshExecutor.shutdownNow();
	}

	/**
	 * All charts across the configured repos, optionally filtered by a case-insensitive
	 * query. Repos are read in parallel; a cached index is used (and refreshed in the
	 * background when stale) so the call does not block on slow remote fetches.
	 */
	public List<HelmChartSummary> listCharts(String query) {
		List<HelmChartSummary> all = properties.getRepositories()
			.parallelStream()
			.flatMap((repo) -> chartsFor(repo).stream())
			.toList();
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
		if (cached != null) {
			if (cached.expiry() <= now()) {
				refreshAsync(repo);
			}
			return cached.charts();
		}
		// Cold cache: fetch now (callers run this per-repo in parallel).
		List<HelmChartSummary> charts = fetch(repo);
		cache.put(repo.getName(), new Cached(charts, now() + ttlMillis()));
		return charts;
	}

	private void refreshAsync(HelmProperties.Repository repo) {
		if (!refreshing.add(repo.getName())) {
			return;
		}
		refreshExecutor.submit(() -> {
			try {
				cache.put(repo.getName(), new Cached(fetch(repo), now() + ttlMillis()));
			}
			finally {
				refreshing.remove(repo.getName());
			}
		});
	}

	private long ttlMillis() {
		return properties.getIndexCacheSeconds() * 1000L;
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

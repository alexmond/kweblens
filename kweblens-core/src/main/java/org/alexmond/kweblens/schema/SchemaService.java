package org.alexmond.kweblens.schema;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * Serves a JSON Schema for a Kubernetes kind, built from the target cluster's own OpenAPI
 * v3 ({@code /openapi/v3/<group-version>}). Because that document is cluster-accurate and
 * already includes CRDs, one path covers built-in kinds and custom resources alike — the
 * schema matches the running API server version and the cluster's installed CRDs.
 *
 * <p>
 * Each OpenAPI group-version document is self-contained (every {@code $ref} resolves
 * within it), so the schema is returned as a draft-07 document: the requested kind's
 * schema inlined at the root (so consumers see its {@code properties} directly) plus a
 * {@code definitions} block holding every schema in the document, with the OpenAPI
 * {@code #/components/schemas/} refs rewritten to {@code #/definitions/} (draft-07's ref
 * location, which the editor's schema library resolves — {@code $defs} would not).
 *
 * <h2>What the cache is, and what it is not</h2>
 *
 * <p>
 * {@code definitions} maps are cached per (cluster, group-version). The key space is
 * <b>bounded by the cluster's API surface</b> rather than by browsing: the requested kind
 * is resolved through the nav catalog before it gets here, so it is always a built-in
 * kind or one of that cluster's own CRDs — no request text reaches this map. Measured
 * against a CRD-heavy real cluster, caching all 38 of its group-versions retains ~17 MB,
 * which is why there is no size cap: an LRU here would evict entries that are about to be
 * fetched again to save under 2% of the container's memory.
 *
 * <p>
 * What it does need is <b>invalidation</b>, because a key alone does not say which
 * cluster an id currently points at. Two things drop entries: the id being re-registered
 * or removed (see {@link ClusterRegistry#addClientListener}), which is how "edit this
 * cluster to point somewhere else" stops serving the previous cluster's schemas; and
 * {@link #TTL}, which is how an API-server upgrade or an updated CRD stops being
 * invisible for the life of the process. Neither is about memory.
 */
@Slf4j
@Service
public class SchemaService {

	/**
	 * How long a fetched group-version stays usable. A schema changes when the API server
	 * is upgraded or a CRD is updated in place — rare, but nothing else in the process
	 * would ever notice, so an entry is re-fetched eventually rather than never.
	 */
	static final Duration TTL = Duration.ofMinutes(10);

	private final ClusterRegistry clusters;

	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Rewritten {@code definitions} maps, keyed by {@code clusterId|group-version-path}.
	 */
	private final Map<String, Entry> cache = new ConcurrentHashMap<>();

	private final Duration ttl;

	// Explicit, because the TTL-taking overload below means there is more than one
	// constructor and Spring will not choose for us.
	@Autowired
	public SchemaService(ClusterRegistry clusters) {
		this(clusters, TTL);
	}

	/** As {@link #SchemaService(ClusterRegistry)}, with the entry lifetime given. */
	SchemaService(ClusterRegistry clusters, Duration ttl) {
		this.clusters = clusters;
		this.ttl = ttl;
		// From the constructor rather than @PostConstruct so the hook is in place for
		// every construction, including tests that build this directly.
		clusters.addClientListener(this::invalidate);
	}

	/**
	 * Forget everything cached for a cluster. Called when its client is closed, because a
	 * cluster id that has been removed or re-pointed no longer describes the API server
	 * whose schemas are held under it.
	 * @param clusterId the cluster to forget
	 */
	public void invalidate(String clusterId) {
		this.cache.keySet().removeIf((key) -> key.startsWith(clusterId + '|'));
	}

	/**
	 * The JSON Schema for a kind, or {@code null} when the cluster's OpenAPI has no
	 * schema for that group/version/kind (or the document couldn't be fetched).
	 */
	public Map<String, Object> jsonSchema(String clusterId, String group, String version, String kind) {
		String gvPath = group.isEmpty() ? ("api/" + version) : ("apis/" + group + "/" + version);
		String key = clusterId + '|' + gvPath;
		Entry cached = cache.get(key);
		Map<String, Object> defs;
		if (cached != null && !cached.expired(System.nanoTime())) {
			defs = cached.defs();
		}
		else {
			Map<String, Object> fetched = fetchDefs(clusterId, gvPath);
			if (fetched.isEmpty()) {
				// A refresh that failed must not turn into "this kind has no schema" when
				// a good answer is already in hand; the deadline stays passed, so the
				// next
				// call tries again.
				defs = (cached != null) ? cached.defs() : fetched;
			}
			else {
				cache.put(key, new Entry(fetched, System.nanoTime() + this.ttl.toNanos()));
				defs = fetched;
			}
		}
		String name = findSchemaName(defs, group, version, kind);
		if (!(defs.get(name) instanceof Map<?, ?> target)) {
			return null;
		}
		// Inline the kind's schema at the root (its properties are then directly visible)
		// and attach every schema as draft-07 `definitions` for the nested $refs.
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("$schema", "http://json-schema.org/draft-07/schema#");
		out.putAll(asStringMap(target));
		out.put("definitions", defs);
		return out;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asStringMap(Map<?, ?> map) {
		return (Map<String, Object>) map;
	}

	private Map<String, Object> fetchDefs(String clusterId, String gvPath) {
		try {
			String doc = clusters.require(clusterId).raw("/openapi/v3/" + gvPath);
			return (doc != null) ? rewriteDefs(doc, mapper) : Map.of();
		}
		catch (RuntimeException ex) {
			log.warn("Could not fetch OpenAPI schema for {} on cluster '{}': {}", gvPath, clusterId, ex.getMessage());
			return Map.of();
		}
	}

	/**
	 * Extract {@code components.schemas} from an OpenAPI v3 document and rewrite its
	 * {@code #/components/schemas/} refs to {@code #/definitions/} so the map can serve
	 * as a self-contained draft-07 JSON Schema {@code definitions} block.
	 */
	static Map<String, Object> rewriteDefs(String openApiDocJson, ObjectMapper mapper) {
		try {
			JsonNode schemas = mapper.readTree(openApiDocJson).path("components").path("schemas");
			if (!schemas.isObject()) {
				return Map.of();
			}
			String rewritten = mapper.writeValueAsString(schemas).replace("#/components/schemas/", "#/definitions/");
			return mapper.readValue(rewritten, new TypeReference<>() {
			});
		}
		catch (JacksonException ex) {
			return Map.of();
		}
	}

	/**
	 * The schema key whose {@code x-kubernetes-group-version-kind} marker matches the
	 * target GVK (core group is the empty string), or {@code null} if none.
	 */
	static String findSchemaName(Map<String, Object> defs, String group, String version, String kind) {
		for (Map.Entry<String, Object> entry : defs.entrySet()) {
			if (!(entry.getValue() instanceof Map<?, ?> schema)) {
				continue;
			}
			if (!(schema.get("x-kubernetes-group-version-kind") instanceof List<?> markers)) {
				continue;
			}
			for (Object marker : markers) {
				if (marker instanceof Map<?, ?> gvk && kind.equals(gvk.get("kind"))
						&& version.equals(gvk.get("version"))
						&& group.equals((gvk.get("group") != null) ? gvk.get("group") : "")) {
					return entry.getKey();
				}
			}
		}
		return null;
	}

	/**
	 * One cached group-version and the {@link System#nanoTime()} reading past which it is
	 * refetched. A monotonic clock rather than the wall clock: a cache that a clock
	 * adjustment can make immortal is not a cache with a TTL.
	 */
	private record Entry(Map<String, Object> defs, long deadlineNanos) {

		boolean expired(long nowNanos) {
			return nowNanos - this.deadlineNanos >= 0;
		}
	}

}

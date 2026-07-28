package org.alexmond.kweblens.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

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
 * {@code definitions} maps are cached per (cluster, group-version).
 */
@Slf4j
@Service
public class SchemaService {

	private final ClusterRegistry clusters;

	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Rewritten {@code definitions} maps, keyed by {@code clusterId|group-version-path}.
	 */
	private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();

	public SchemaService(ClusterRegistry clusters) {
		this.clusters = clusters;
	}

	/**
	 * The JSON Schema for a kind, or {@code null} when the cluster's OpenAPI has no
	 * schema for that group/version/kind (or the document couldn't be fetched).
	 */
	public Map<String, Object> jsonSchema(String clusterId, String group, String version, String kind) {
		String gvPath = group.isEmpty() ? ("api/" + version) : ("apis/" + group + "/" + version);
		Map<String, Object> defs = cache.get(clusterId + '|' + gvPath);
		if (defs == null) {
			defs = fetchDefs(clusterId, gvPath);
			if (!defs.isEmpty()) {
				cache.put(clusterId + '|' + gvPath, defs);
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

}

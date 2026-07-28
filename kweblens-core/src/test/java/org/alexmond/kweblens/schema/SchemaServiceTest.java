package org.alexmond.kweblens.schema;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tests the pure OpenAPI-to-JSON-Schema logic (ref rewrite + GVK lookup) without a
 * live cluster; the cluster fetch itself is a thin fabric8 {@code raw()} call.
 */
class SchemaServiceTest {

	private static final String DOC = """
			{
			  "components": {
			    "schemas": {
			      "io.k8s.api.core.v1.ConfigMap": {
			        "type": "object",
			        "x-kubernetes-group-version-kind": [{"group": "", "version": "v1", "kind": "ConfigMap"}],
			        "properties": {
			          "data": {"type": "object"},
			          "metadata": {"$ref": "#/components/schemas/io.k8s.apimachinery.pkg.apis.meta.v1.ObjectMeta"}
			        }
			      },
			      "io.k8s.apimachinery.pkg.apis.meta.v1.ObjectMeta": {
			        "type": "object",
			        "properties": {"name": {"type": "string"}}
			      }
			    }
			  }
			}
			""";

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	@SuppressWarnings("unchecked")
	void rewritesComponentRefsToDefs() {
		Map<String, Object> defs = SchemaService.rewriteDefs(DOC, mapper);
		assertThat(defs).containsKeys("io.k8s.api.core.v1.ConfigMap",
				"io.k8s.apimachinery.pkg.apis.meta.v1.ObjectMeta");
		Map<String, Object> configMap = (Map<String, Object>) defs.get("io.k8s.api.core.v1.ConfigMap");
		Map<String, Object> properties = (Map<String, Object>) configMap.get("properties");
		Map<String, Object> metadata = (Map<String, Object>) properties.get("metadata");
		assertThat(metadata.get("$ref")).isEqualTo("#/definitions/io.k8s.apimachinery.pkg.apis.meta.v1.ObjectMeta");
	}

	@Test
	void findsSchemaNameByGvk() {
		Map<String, Object> defs = SchemaService.rewriteDefs(DOC, mapper);
		assertThat(SchemaService.findSchemaName(defs, "", "v1", "ConfigMap")).isEqualTo("io.k8s.api.core.v1.ConfigMap");
	}

	@Test
	void returnsNullForUnknownKind() {
		Map<String, Object> defs = SchemaService.rewriteDefs(DOC, mapper);
		assertThat(SchemaService.findSchemaName(defs, "apps", "v1", "Deployment")).isNull();
	}

	@Test
	void toleratesMalformedDocument() {
		assertThat(SchemaService.rewriteDefs("not json", mapper)).isEmpty();
	}

}

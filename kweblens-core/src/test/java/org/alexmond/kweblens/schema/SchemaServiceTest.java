package org.alexmond.kweblens.schema;

import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterOrigin;
import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit-tests the pure OpenAPI-to-JSON-Schema logic (ref rewrite + GVK lookup), and the
 * cache around it, without a live cluster; the cluster fetch itself is a thin fabric8
 * {@code raw()} call, which is stubbed so that "did a request leave" is observable.
 */
class SchemaServiceTest {

	private static final String CORE_V1 = "/openapi/v3/api/v1";

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

	private final ClusterRegistry registry = new ClusterRegistry();

	@AfterEach
	void closeRegistry() {
		this.registry.close();
	}

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

	@Test
	void theOpenApiDocumentIsFetchedOnceAndThenServedFromTheCache() {
		// "Cached" is asserted against the request that would have left, not against the
		// answer: the answer is identical either way.
		KubernetesClient client = clientServing(DOC);
		SchemaService service = serviceFor(client);

		assertThat(service.jsonSchema("mock", "", "v1", "ConfigMap")).isNotNull();
		assertThat(service.jsonSchema("mock", "", "v1", "ConfigMap")).isNotNull();
		assertThat(service.jsonSchema("mock", "", "v1", "ConfigMap")).isNotNull();

		verify(client, times(1)).raw(CORE_V1);
	}

	@Test
	void closingAClustersClientDropsItsCachedSchemas() {
		// A cluster id that has been removed, or edited to point somewhere else, no
		// longer
		// describes the API server whose schemas are held under it. Serving the previous
		// cluster's schema to the editor is how a manifest gets written, and validated,
		// against an API version that is not there.
		KubernetesClient client = clientServing(DOC);
		SchemaService service = serviceFor(client);
		assertThat(service.jsonSchema("mock", "", "v1", "ConfigMap")).isNotNull();

		this.registry.unregister("mock");
		this.registry.register("mock", "mock", client, ClusterOrigin.RUNTIME);
		assertThat(service.jsonSchema("mock", "", "v1", "ConfigMap")).isNotNull();

		// Fetched again: the entry did not outlive the cluster it described.
		verify(client, times(2)).raw(CORE_V1);
	}

	@Test
	void anEntryIsRefetchedOnceItsTtlHasPassed() {
		KubernetesClient client = clientServing(DOC);
		// A zero TTL expires every entry immediately, which is the same code path a
		// ten-minute-old entry takes.
		SchemaService service = serviceFor(client, Duration.ZERO);

		assertThat(service.jsonSchema("mock", "", "v1", "ConfigMap")).isNotNull();
		assertThat(service.jsonSchema("mock", "", "v1", "ConfigMap")).isNotNull();

		verify(client, times(2)).raw(CORE_V1);
	}

	@Test
	void aFailedRefreshKeepsTheAnswerAlreadyInHand() {
		// The document is served once and then the API server stops answering. A cache
		// that dropped the entry on a failed refresh would turn a transient blip into
		// "this kind has no schema", which the editor renders as no completions at all.
		KubernetesClient client = mock(KubernetesClient.class);
		given(client.raw(CORE_V1)).willReturn(DOC, null);
		SchemaService service = serviceFor(client, Duration.ZERO);
		assertThat(service.jsonSchema("mock", "", "v1", "ConfigMap")).isNotNull();

		assertThat(service.jsonSchema("mock", "", "v1", "ConfigMap")).isNotNull();

		verify(client, times(2)).raw(CORE_V1);
	}

	private KubernetesClient clientServing(String document) {
		KubernetesClient client = mock(KubernetesClient.class);
		given(client.raw(CORE_V1)).willReturn(document);
		return client;
	}

	private SchemaService serviceFor(KubernetesClient client) {
		return serviceFor(client, SchemaService.TTL);
	}

	private SchemaService serviceFor(KubernetesClient client, Duration ttl) {
		this.registry.register("mock", "mock", client, ClusterOrigin.RUNTIME);
		return new SchemaService(this.registry, ttl);
	}

}

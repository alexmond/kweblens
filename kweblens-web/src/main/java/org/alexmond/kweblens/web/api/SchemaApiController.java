package org.alexmond.kweblens.web.api;

import java.util.Map;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.schema.SchemaService;
import org.alexmond.kweblens.web.nav.ClusterNavService;
import org.alexmond.kweblens.web.ui.UnknownResourceException;

/**
 * JSON Schema API: serves the schema for a resource kind (by its nav {@code resource} id)
 * from the cluster's own OpenAPI v3, so the YAML editor can offer cluster-accurate
 * completion, lint and hover — including CRDs. Read-only; 404 when the cluster's OpenAPI
 * has no schema for the kind.
 */
@RestController
@RequiredArgsConstructor
public class SchemaApiController {

	private final SchemaService schemas;

	private final ClusterNavService clusterNav;

	@GetMapping("/api/v1/clusters/{clusterId}/schema")
	public ResponseEntity<Map<String, Object>> schema(@PathVariable String clusterId, @RequestParam String resource) {
		ResourceDescriptor descriptor = clusterNav.find(clusterId, resource)
			.orElseThrow(() -> new UnknownResourceException(resource));
		Map<String, Object> schema = schemas.jsonSchema(clusterId, descriptor.group(), descriptor.version(),
				descriptor.kind());
		return (schema != null) ? ResponseEntity.ok(schema) : ResponseEntity.notFound().build();
	}

}

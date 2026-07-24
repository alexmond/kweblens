package org.alexmond.kweblens.web.api;

import lombok.RequiredArgsConstructor;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.resource.ResourceSummary;
import org.alexmond.kweblens.web.nav.NavCatalog;
import org.alexmond.kweblens.web.security.AuditService;
import org.alexmond.kweblens.web.ui.UnknownResourceException;

/**
 * YAML API: fetch a single resource's manifest, and apply (server-side) a manifest. Apply
 * is a mutating call — gated by security (see SecurityConfig) and recorded by
 * {@link AuditService}.
 */
@RestController
@RequiredArgsConstructor
public class YamlApiController {

	private final ResourceService resources;

	private final NavCatalog navCatalog;

	private final AuditService audit;

	@GetMapping(value = "/api/v1/clusters/{clusterId}/yaml", produces = "application/yaml")
	public ResponseEntity<String> yaml(@PathVariable String clusterId, @RequestParam String resource,
			@RequestParam(required = false) String namespace, @RequestParam String name) {
		ResourceDescriptor descriptor = navCatalog.find(resource)
			.orElseThrow(() -> new UnknownResourceException(resource));
		String yaml = resources.getYaml(clusterId, descriptor, namespace, name);
		return (yaml != null) ? ResponseEntity.ok(yaml) : ResponseEntity.notFound().build();
	}

	@PostMapping(value = "/api/v1/clusters/{clusterId}/apply",
			consumes = { MediaType.TEXT_PLAIN_VALUE, "application/yaml" })
	public ResourceSummary apply(@PathVariable String clusterId, @RequestBody String manifest) {
		ResourceSummary applied = resources.apply(clusterId, manifest);
		audit.record(clusterId, "apply", applied.kind() + "/" + applied.namespace() + "/" + applied.name());
		return applied;
	}

}

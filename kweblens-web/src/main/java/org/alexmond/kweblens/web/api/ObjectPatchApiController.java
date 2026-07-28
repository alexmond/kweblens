package org.alexmond.kweblens.web.api;

import lombok.RequiredArgsConstructor;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.resource.ResourceSummary;
import org.alexmond.kweblens.web.nav.ClusterNavService;
import org.alexmond.kweblens.web.security.AuditService;
import org.alexmond.kweblens.web.ui.UnknownResourceException;

/**
 * JSON Merge Patch API for the structured (form) editor: targeted edits — labels,
 * annotations, ConfigMap/Secret data — applied without clobbering the rest of the object.
 * Mutating, so gated by {@code SecurityConfig} and recorded by {@link AuditService}.
 */
@RestController
@RequiredArgsConstructor
public class ObjectPatchApiController {

	private final ResourceService resources;

	private final ClusterNavService clusterNav;

	private final AuditService audit;

	@PatchMapping(value = "/api/v1/clusters/{clusterId}/patch",
			consumes = { "application/merge-patch+json", MediaType.APPLICATION_JSON_VALUE })
	public ResourceSummary patch(@PathVariable String clusterId, @RequestParam String resource,
			@RequestParam(required = false) String namespace, @RequestParam String name,
			@RequestBody String mergePatch) {
		ResourceDescriptor descriptor = clusterNav.find(clusterId, resource)
			.orElseThrow(() -> new UnknownResourceException(resource));
		ResourceSummary patched = resources.patch(clusterId, descriptor, namespace, name, mergePatch);
		audit.record(clusterId, "patch", AuditService.ref(patched.kind(), patched.namespace(), patched.name()));
		return patched;
	}

}

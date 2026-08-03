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
import org.alexmond.kweblens.web.nav.ClusterNavService;
import org.alexmond.kweblens.web.security.AuditService;

/**
 * YAML API: fetch a single resource's manifest, and apply (server-side) a manifest. Apply
 * is a mutating call — gated by security (see SecurityConfig) and recorded by
 * {@link AuditService}.
 */
@RestController
@RequiredArgsConstructor
public class YamlApiController {

	private final ResourceService resources;

	private final ClusterNavService clusterNav;

	private final AuditService audit;

	@GetMapping(value = "/api/v1/clusters/{clusterId}/yaml", produces = "application/yaml")
	public ResponseEntity<String> yaml(@PathVariable String clusterId, @RequestParam String resource,
			@RequestParam(required = false) String namespace, @RequestParam String name) {
		ResourceDescriptor descriptor = clusterNav.find(clusterId, resource)
			.orElseThrow(() -> new UnknownResourceException(resource));
		String yaml = resources.getYaml(clusterId, descriptor, namespace, name);
		return (yaml != null) ? ResponseEntity.ok(yaml) : ResponseEntity.notFound().build();
	}

	@PostMapping(value = "/api/v1/clusters/{clusterId}/apply",
			consumes = { MediaType.TEXT_PLAIN_VALUE, "application/yaml" })
	public ResourceSummary apply(@PathVariable String clusterId, @RequestBody String manifest) {
		ResourceSummary applied = resources.apply(clusterId, manifest);
		audit.record(clusterId, "apply", AuditService.ref(applied.kind(), applied.namespace(), applied.name()));
		return applied;
	}

	/**
	 * What the apply <em>would</em> produce, from the API server, without producing it.
	 *
	 * <p>
	 * A <b>POST</b> rather than a GET, and deliberately so on two counts. It carries a
	 * manifest body, which a GET cannot; and although it changes nothing, it is the same
	 * request as the write with one flag flipped, so it belongs on the side of the
	 * security model that the write is on. In open-mode every non-GET requires the admin
	 * login, which means the preview cannot be used to probe a cluster that the caller
	 * could not already write to.
	 *
	 * <p>
	 * <b>Not audited.</b> The audit trail records what was changed; nothing was.
	 * Recording dry runs would dilute the log that exists to answer "who changed this" —
	 * the apply that follows is what gets the entry.
	 */
	@PostMapping(value = "/api/v1/clusters/{clusterId}/apply/dry-run",
			consumes = { MediaType.TEXT_PLAIN_VALUE, "application/yaml" }, produces = "application/yaml")
	public ResponseEntity<String> applyDryRun(@PathVariable String clusterId, @RequestBody String manifest) {
		return ResponseEntity.ok(resources.dryRunApply(clusterId, manifest));
	}

}

package org.alexmond.kweblens.web.api;

import java.util.Map;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.web.nav.ClusterNavService;
import org.alexmond.kweblens.web.security.AuditService;
import org.alexmond.kweblens.web.ui.UnknownResourceException;

/**
 * Mutating resource actions — delete, scale, and rolling restart. All are POSTs, so they
 * are auth-gated by SecurityConfig, and each is recorded by {@link AuditService}.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/resources/{resourceId}/{namespace}/{name}")
@RequiredArgsConstructor
public class ResourceActionApiController {

	private final ResourceService resources;

	private final ClusterNavService clusterNav;

	private final AuditService audit;

	@PostMapping("/delete")
	public Map<String, String> delete(@PathVariable String clusterId, @PathVariable String resourceId,
			@PathVariable String namespace, @PathVariable String name,
			@RequestParam(defaultValue = "false") boolean force) {
		ResourceDescriptor descriptor = descriptor(clusterId, resourceId);
		resources.delete(clusterId, descriptor, namespace, name, force);
		audit.record(clusterId, force ? "force-delete" : "delete", descriptor.kind() + "/" + namespace + "/" + name);
		return Map.of("result", (force ? "force-deleted " : "deleted ") + descriptor.kind() + " " + name);
	}

	@PostMapping("/scale")
	public Map<String, String> scale(@PathVariable String clusterId, @PathVariable String resourceId,
			@PathVariable String namespace, @PathVariable String name, @RequestParam int replicas) {
		ResourceDescriptor descriptor = descriptor(clusterId, resourceId);
		resources.scale(clusterId, descriptor, namespace, name, replicas);
		audit.record(clusterId, "scale=" + replicas, descriptor.kind() + "/" + namespace + "/" + name);
		return Map.of("result", "scaled " + name + " to " + replicas);
	}

	@PostMapping("/restart")
	public Map<String, String> restart(@PathVariable String clusterId, @PathVariable String resourceId,
			@PathVariable String namespace, @PathVariable String name) {
		ResourceDescriptor descriptor = descriptor(clusterId, resourceId);
		resources.rolloutRestart(clusterId, descriptor, namespace, name);
		audit.record(clusterId, "restart", descriptor.kind() + "/" + namespace + "/" + name);
		return Map.of("result", "restarted " + name);
	}

	private ResourceDescriptor descriptor(String clusterId, String resourceId) {
		return clusterNav.find(clusterId, resourceId).orElseThrow(() -> new UnknownResourceException(resourceId));
	}

}

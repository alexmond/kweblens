package org.alexmond.kweblens.web.api;

import java.util.Map;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.web.security.AuditService;

/**
 * Node scheduling actions: cordon / uncordon (mark a node un/schedulable).
 * Cluster-scoped, so no namespace. Auth-gated (POST) and audited.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/nodes/{name}")
@RequiredArgsConstructor
public class NodeActionApiController {

	private final ResourceService resources;

	private final AuditService audit;

	@PostMapping("/cordon")
	public Map<String, String> cordon(@PathVariable String clusterId, @PathVariable String name) {
		resources.setUnschedulable(clusterId, name, true);
		audit.record(clusterId, "cordon", "Node/" + name);
		return Map.of("result", "cordoned " + name);
	}

	@PostMapping("/uncordon")
	public Map<String, String> uncordon(@PathVariable String clusterId, @PathVariable String name) {
		resources.setUnschedulable(clusterId, name, false);
		audit.record(clusterId, "uncordon", "Node/" + name);
		return Map.of("result", "uncordoned " + name);
	}

	@PostMapping("/drain")
	public Map<String, String> drain(@PathVariable String clusterId, @PathVariable String name) {
		resources.drainNode(clusterId, name);
		audit.record(clusterId, "drain", "Node/" + name);
		return Map.of("result", "drained " + name);
	}

}

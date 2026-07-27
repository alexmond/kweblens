package org.alexmond.kweblens.web.api;

import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.portforward.PortForwardInfo;
import org.alexmond.kweblens.portforward.PortForwardService;
import org.alexmond.kweblens.web.security.AuditService;

/**
 * Managed port-forwards for a cluster. Listing is a read (open in open-mode); starting
 * and stopping are POSTs, so they are auth-gated by SecurityConfig and audited.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/port-forwards")
@RequiredArgsConstructor
public class PortForwardApiController {

	private final PortForwardService portForwards;

	private final AuditService audit;

	@GetMapping
	public List<PortForwardInfo> list(@PathVariable String clusterId) {
		return portForwards.list(clusterId);
	}

	@PostMapping
	public PortForwardInfo start(@PathVariable String clusterId, @RequestBody StartRequest request) {
		PortForwardInfo info = portForwards.start(clusterId, request.kind(), request.namespace(), request.name(),
				request.remotePort(), request.localPort());
		audit.record(clusterId, "port-forward", AuditService.ref(info.kind(), info.namespace(), info.name()) + " "
				+ info.remotePort() + "->" + info.localPort());
		return info;
	}

	@PostMapping("/{id}/stop")
	public Map<String, String> stop(@PathVariable String clusterId, @PathVariable String id) {
		portForwards.stop(id);
		audit.record(clusterId, "port-forward-stop", id);
		return Map.of("result", "stopped " + id);
	}

	/**
	 * Body for starting a forward; {@code localPort} is optional (0/absent = ephemeral).
	 */
	public record StartRequest(String kind, String namespace, String name, int remotePort, Integer localPort) {
	}

}

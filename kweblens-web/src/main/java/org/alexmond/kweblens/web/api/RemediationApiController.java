package org.alexmond.kweblens.web.api;

import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.web.ai.RemediationProposal;
import org.alexmond.kweblens.web.ai.RemediationService;

/**
 * AI-assisted remediation. {@code GET /remediations} proposes fixes (read-only).
 * {@code POST
 * /remediations/apply} carries one out — a mutating call, so it is auth-gated and, per
 * the safety model, requires {@code confirm=true} (never autonomous).
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/remediations")
@RequiredArgsConstructor
public class RemediationApiController {

	private final RemediationService remediation;

	@GetMapping
	public List<RemediationProposal> propose(@PathVariable String clusterId,
			@RequestParam(required = false) String namespace) {
		return remediation.propose(clusterId, namespace);
	}

	@PostMapping("/apply")
	public Map<String, String> apply(@PathVariable String clusterId, @RequestParam String namespace,
			@RequestParam String action, @RequestParam String target,
			@RequestParam(defaultValue = "false") boolean confirm) {
		return Map.of("result", remediation.apply(clusterId, namespace, action, target, confirm));
	}

}

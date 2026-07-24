package org.alexmond.kweblens.web.api;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.web.ai.DiagnoseResult;
import org.alexmond.kweblens.web.ai.DiagnoseService;

/**
 * Read-only AI-assisted diagnosis. Deterministic validators always run; the LLM only adds
 * a summary when enabled. GET (idempotent, read-only), so it is usable under open-mode.
 */
@RestController
@RequiredArgsConstructor
public class DiagnoseApiController {

	private final DiagnoseService diagnose;

	@GetMapping("/api/v1/clusters/{clusterId}/diagnose")
	public DiagnoseResult diagnose(@PathVariable String clusterId, @RequestParam(required = false) String namespace) {
		return diagnose.diagnose(clusterId, namespace);
	}

}

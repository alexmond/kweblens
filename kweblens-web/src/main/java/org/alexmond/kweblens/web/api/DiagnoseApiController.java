package org.alexmond.kweblens.web.api;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.web.ai.DiagnoseResult;
import org.alexmond.kweblens.web.ai.DiagnoseService;
import org.alexmond.kweblens.web.security.AuditService;

/**
 * AI-assisted diagnosis, split so that reading it is free (#251).
 *
 * <p>
 * The {@code GET} runs the deterministic validators and returns them plus any summary
 * already cached for exactly those findings. It never starts an inference call, so the
 * panel can mount, and the namespace filter can change, as often as it likes.
 *
 * <p>
 * {@code POST /summary} is the only thing that calls a model. It is a non-GET because it
 * is write-shaped rather than because it writes: it spends money and sends cluster state
 * to a third-party inference provider, and the security model gates every non-GET behind
 * the admin login in both modes — which is the right default for that. It is audited for
 * the same reason.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/diagnose")
@RequiredArgsConstructor
public class DiagnoseApiController {

	private final DiagnoseService diagnose;

	private final AuditService audit;

	@GetMapping
	public DiagnoseResult diagnose(@PathVariable String clusterId, @RequestParam(required = false) String namespace) {
		return this.diagnose.diagnose(clusterId, namespace);
	}

	/**
	 * Run the analysis now and cache it.
	 *
	 * <p>
	 * The cache behind this is in-memory and per-process: a restart, or a second replica,
	 * starts with nothing. That costs an extra call, never a wrong answer — the summary
	 * is only ever served back for the findings it was written about.
	 */
	@PostMapping("/summary")
	public DiagnoseResult analyse(@PathVariable String clusterId, @RequestParam(required = false) String namespace) {
		this.audit.record(clusterId, "diagnose-analyse", (namespace != null) ? namespace : "(all namespaces)");
		return this.diagnose.analyse(clusterId, namespace);
	}

}

package org.alexmond.kweblens.web.api;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.web.security.AuditEntry;
import org.alexmond.kweblens.web.security.AuditService;

/**
 * The audit trail of mutating actions as JSON, newest-first. (The server-rendered
 * {@code /audit} page went away with the Thymeleaf UI; the trail stays readable here so
 * recorded actions are not write-only.)
 */
@RestController
@RequiredArgsConstructor
public class AuditApiController {

	private final AuditService audit;

	@GetMapping("/api/v1/audit")
	public List<AuditEntry> audit() {
		return audit.recent();
	}

}

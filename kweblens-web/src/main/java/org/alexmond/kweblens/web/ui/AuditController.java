package org.alexmond.kweblens.web.ui;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import org.alexmond.kweblens.web.security.AuditEntry;
import org.alexmond.kweblens.web.security.AuditService;

/**
 * The audit trail of mutating actions — a page at {@code /audit} and JSON at
 * {@code /api/v1/audit}, both newest-first.
 */
@Controller
@RequiredArgsConstructor
public class AuditController {

	private final AuditService audit;

	@GetMapping("/audit")
	public String page(Model model) {
		model.addAttribute("entries", audit.recent());
		return "audit";
	}

	@GetMapping("/api/v1/audit")
	@ResponseBody
	public List<AuditEntry> api() {
		return audit.recent();
	}

}

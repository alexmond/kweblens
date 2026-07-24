package org.alexmond.kweblens.web.security;

import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Records mutating actions (who did what to which cluster/resource). Write endpoints —
 * YAML apply, pod exec, Helm actions — call this so there is an audit trail. Currently
 * log-backed; a persistent store / {@code /audit} view can layer on later.
 */
@Slf4j
@Service
public class AuditService {

	/** Record a write against a cluster resource by the current authenticated user. */
	public void record(String clusterId, String action, String target) {
		log.info("AUDIT user={} cluster={} action={} target={}", currentUser(), clusterId, action, target);
	}

	private String currentUser() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return (authentication != null && authentication.isAuthenticated()) ? authentication.getName() : "anonymous";
	}

}

package org.alexmond.kweblens.web.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Records mutating actions (who did what to which cluster/resource). Write endpoints —
 * YAML apply, pod exec, Helm actions, delete/scale/restart — call this so there is an
 * audit trail: it logs each action and keeps a bounded, newest-first in-memory history
 * surfaced at {@code /audit}. A persistent store can replace the in-memory ring later
 * without changing callers.
 */
@Slf4j
@Service
public class AuditService {

	private static final int MAX_ENTRIES = 500;

	// Thread-safe + weakly-consistent iteration; the ring is bounded by trimming after
	// each add
	// (a brief overshoot under heavy concurrency is fine for an audit history).
	private final Deque<AuditEntry> entries = new ConcurrentLinkedDeque<>();

	/** Record a write against a cluster resource by the current authenticated user. */
	public void record(String clusterId, String action, String target) {
		String user = currentUser();
		entries.addFirst(new AuditEntry(Instant.now(), user, clusterId, action, target));
		while (entries.size() > MAX_ENTRIES) {
			entries.pollLast();
		}
		log.info("AUDIT user={} cluster={} action={} target={}", user, clusterId, action, target);
	}

	/** The recorded actions, newest first (a snapshot copy). */
	public List<AuditEntry> recent() {
		return new ArrayList<>(entries);
	}

	private String currentUser() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return (authentication != null && authentication.isAuthenticated()) ? authentication.getName() : "anonymous";
	}

}

package org.alexmond.kweblens.web.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Records mutating actions (who did what to which cluster/resource). Write endpoints —
 * YAML apply, patch, scale, restart, rollback, delete, pod exec, port-forward, Helm
 * actions, runtime cluster changes and pod file access — call this so there is an audit
 * trail.
 * <p>
 * Every entry goes two places:
 * <ul>
 * <li><b>the {@code kweblens.audit} logger</b>, one structured line per entry
 * ({@code kweblens-audit seq=… ts=… user="…" cluster="…" action="…" target="…"}). That is
 * the durable copy: it survives a restart because whatever already ships the application
 * log keeps it, and it can be routed to its own file or appender <em>by logger name</em>
 * without changing kweblens. The category is pinned to {@code INFO} in
 * {@code application.yml} so raising the root level cannot silently switch the trail
 * off.</li>
 * <li><b>a bounded, newest-first in-memory ring</b> (the newest {@value #MAX_ENTRIES}),
 * surfaced at {@code /audit}. That is a live view only — eviction from it no longer loses
 * the record, because the line is already in the log.</li>
 * </ul>
 * Values are logged quoted and sanitised, so a newline in an attacker-influenced field —
 * a pod file path, a Helm release name — cannot forge a second audit line.
 * <p>
 * <b>What is never recorded: credentials and payloads.</b> Callers pass an action and an
 * object reference only — a runtime cluster's kubeconfig is audited as
 * {@code cluster-update-credential} against the cluster id, a pod file write as
 * {@code file-write=<n>B} against the file's path. Keep it that way: never pass a request
 * body, a token or file content to {@link #record}.
 */
@Service
public class AuditService {

	/**
	 * Logger category every entry is written to. A dedicated name (not a class name) so a
	 * deployment can filter, route or ship the audit trail on its own.
	 */
	public static final String AUDIT_LOGGER = "kweblens.audit";

	/** Fixed first token of every audit line, so the trail is greppable. */
	static final String LINE_PREFIX = "kweblens-audit";

	/** How many entries the in-memory live view keeps. */
	private static final int MAX_ENTRIES = 500;

	private static final Logger AUDIT = LoggerFactory.getLogger(AUDIT_LOGGER);

	private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

	// Thread-safe + weakly-consistent iteration; the ring is bounded by trimming after
	// each add
	// (a brief overshoot under heavy concurrency is fine for an audit history).
	private final Deque<AuditEntry> entries = new ConcurrentLinkedDeque<>();

	// Per-process counter carried on every line: makes a gap in shipped logs visible and
	// lets a live-view row be matched to the line that recorded it.
	private final AtomicLong sequence = new AtomicLong();

	/**
	 * Format an audit target as {@code kind/namespace/name} — the convention write
	 * endpoints use.
	 */
	public static String ref(String kind, String namespace, String name) {
		return kind + "/" + namespace + "/" + name;
	}

	/** Render one entry as a single structured, greppable log line. */
	static String line(long seq, AuditEntry entry) {
		StringBuilder line = new StringBuilder(160);
		line.append(LINE_PREFIX).append(" seq=").append(seq).append(" ts=").append(entry.timestamp());
		field(line, "user", entry.user());
		field(line, "cluster", entry.cluster());
		field(line, "action", entry.action());
		field(line, "target", entry.target());
		return line.toString();
	}

	private static void field(StringBuilder line, String key, String value) {
		line.append(' ').append(key).append("=\"").append(sanitize(value)).append('"');
	}

	/**
	 * Escape quotes and backslashes and strip control characters, so one entry is always
	 * exactly one parseable line and a crafted value cannot inject a forged one.
	 */
	private static String sanitize(String value) {
		if (value == null) {
			return "";
		}
		String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
		return CONTROL_CHARS.matcher(escaped).replaceAll(" ");
	}

	/**
	 * Record a write against a cluster resource by the current authenticated user. The
	 * target is an object reference — never a request body, credential or file content.
	 */
	public void record(String clusterId, String action, String target) {
		AuditEntry entry = new AuditEntry(Instant.now(), currentUser(), clusterId, action, target);
		AUDIT.info("{}", line(sequence.incrementAndGet(), entry));
		entries.addFirst(entry);
		while (entries.size() > MAX_ENTRIES) {
			entries.pollLast();
		}
	}

	/**
	 * The recorded actions still in the live view, newest first (a snapshot copy).
	 * Anything evicted from it, and everything from before the last restart, is in the
	 * {@value #AUDIT_LOGGER} log.
	 */
	public List<AuditEntry> recent() {
		return new ArrayList<>(entries);
	}

	private String currentUser() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return (authentication != null && authentication.isAuthenticated()) ? authentication.getName() : "anonymous";
	}

}

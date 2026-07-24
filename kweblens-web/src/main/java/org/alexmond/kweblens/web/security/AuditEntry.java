package org.alexmond.kweblens.web.security;

import java.time.Instant;

/**
 * One recorded mutating action.
 *
 * @param timestamp when it happened
 * @param user the authenticated principal (or {@code anonymous})
 * @param cluster the target cluster id
 * @param action the action (e.g. {@code apply}, {@code delete}, {@code scale=3},
 * {@code exec})
 * @param target the target object (e.g. {@code Kind/namespace/name})
 */
public record AuditEntry(Instant timestamp, String user, String cluster, String action, String target) {
}

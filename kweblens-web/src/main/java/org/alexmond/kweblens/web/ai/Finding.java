package org.alexmond.kweblens.web.ai;

/**
 * One diagnosis finding.
 *
 * @param severity {@code critical} / {@code warning} / {@code info}
 * @param title short problem statement
 * @param object the involved object as {@code Kind/namespace/name}
 * @param detail evidence (the observed state/event that triggered the finding)
 * @param suggestedFix a concrete next step
 * @param source {@code validator} (deterministic) or {@code ai}
 */
public record Finding(String severity, String title, String object, String detail, String suggestedFix, String source) {
}

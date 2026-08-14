package org.alexmond.kweblens.health;

/**
 * One thing the cluster is configured to allow, and where that was read.
 *
 * <p>
 * <b>This is evidence, not advice, and it is never a statement about who is looking.</b>
 * kweblens runs as a single trusted operator (ADR-001), so a finding here says what
 * <i>the cluster</i> permits — it says nothing about the identity of the person reading
 * it, and it is not an authorization decision.
 *
 * <p>
 * {@code detail} must name the field or the objects it was read from, so a reader can
 * confirm the verdict from the manifests rather than trusting this. <b>A Secret's or a
 * ServiceAccount's name may appear in a finding; a Secret's values never do.</b>
 *
 * @param severity {@code critical} / {@code warning} / {@code info}
 * @param title short statement of what is configured
 * @param object the object the finding is about, as {@code Kind/name} or
 * {@code Kind/namespace/name}
 * @param detail the observed configuration, naming every object that was joined to reach
 * it
 * @param fix a concrete next step
 */
public record SecurityFinding(String severity, String title, String object, String detail, String fix) {

	/** Where this sits in a sorted list: critical, then warning, then everything else. */
	int rank() {
		return switch (this.severity) {
			case "critical" -> 0;
			case "warning" -> 1;
			default -> 2;
		};
	}

}

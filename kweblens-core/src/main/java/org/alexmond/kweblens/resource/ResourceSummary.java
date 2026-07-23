package org.alexmond.kweblens.resource;

import java.time.Duration;
import java.time.Instant;

/**
 * One row in a resource list view — the handful of fields the dashboard and the CLI both
 * show, independent of the underlying Kubernetes kind.
 *
 * @param kind the Kubernetes kind (e.g. {@code Pod}, {@code Namespace})
 * @param namespace the namespace, or {@code null} for cluster-scoped resources
 * @param name the resource name
 * @param status a short status/phase string (e.g. {@code Running}, {@code Active})
 * @param age compact human age since creation (e.g. {@code 3d}, {@code 5h}, {@code 12m})
 */
public record ResourceSummary(String kind, String namespace, String name, String status, String age) {

	/**
	 * Render a compact, kubectl-style age from a creation timestamp to {@code now} — the
	 * largest single unit (days, hours, minutes, else seconds). A null or future creation
	 * renders as {@code "-"}.
	 */
	public static String age(Instant creation, Instant now) {
		if (creation == null || now == null || creation.isAfter(now)) {
			return "-";
		}
		Duration d = Duration.between(creation, now);
		long days = d.toDays();
		if (days > 0) {
			return days + "d";
		}
		long hours = d.toHours();
		if (hours > 0) {
			return hours + "h";
		}
		long minutes = d.toMinutes();
		if (minutes > 0) {
			return minutes + "m";
		}
		return d.toSeconds() + "s";
	}

}

package org.alexmond.kweblens.log;

import java.time.Instant;

/**
 * One log line, tagged with the {@link LogSource} it came from.
 *
 * <p>
 * {@code timestamp} is the Kubernetes-reported time of the line, parsed out of the
 * {@code usingTimestamps()} prefix and <em>removed</em> from {@link #text()} — so clients
 * get clean output plus a sortable instant, rather than having to parse the prefix
 * themselves. It is null when the API server did not supply one (which is why interleaved
 * ordering is best-effort; see {@code MultiLogApiController}).
 */
public record LogLine(String source, Instant timestamp, String text) {
}

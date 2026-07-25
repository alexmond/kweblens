package org.alexmond.kweblens.metric;

import java.util.List;

/**
 * A single time-series result for a chart. {@code available} is false when no
 * Prometheus-compatible backend was found; an empty {@code points} with
 * {@code available=true} means the backend answered but had no data.
 *
 * @param available whether a metrics backend was reachable
 * @param unit value unit hint ({@code cores}, {@code bytes})
 * @param points the samples, oldest first
 */
public record MetricSeries(boolean available, String unit, List<MetricPoint> points) {

	public static MetricSeries unavailable() {
		return new MetricSeries(false, "", List.of());
	}
}

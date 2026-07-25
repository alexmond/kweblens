package org.alexmond.kweblens.metric;

/**
 * One time-series sample: {@code t} epoch seconds, {@code v} the value.
 */
public record MetricPoint(long t, double v) {
}

import { describe, expect, it } from 'vitest';

import { buildMetricOption, CHART_TOKENS } from './metric-chart-option';
import type { MetricSeries } from './types';

// The invariant: no CSS custom property may reach the echarts option.
//
// The chart is drawn by a CanvasRenderer, and a canvas rejects `var(--x)` outright — the
// assignment is discarded and echarts keeps its own default, which is black. That is how four
// axis colour declarations rendered as pure black on the dark theme's #1f242a panel (1.34:1)
// through every review: canvas text has no DOM node, so `contrast-check.mjs` sees nothing, and
// in light mode black reads perfectly. This test is the only gate that can catch a relapse.

const walk = (value: unknown, visit: (s: string) => void): void => {
  if (typeof value === 'string') {
    visit(value);
  } else if (Array.isArray(value)) {
    value.forEach((v) => walk(v, visit));
  } else if (value && typeof value === 'object') {
    Object.values(value).forEach((v) => walk(v, visit));
  }
};

const stringsIn = (value: unknown): string[] => {
  const found: string[] = [];
  walk(value, (s) => found.push(s));
  return found;
};

const series: MetricSeries = {
  available: true,
  unit: 'bytes',
  points: [
    { t: 1, v: 10 },
    { t: 2, v: 20 },
  ],
};

const tokens = {
  '--panel': '#1f242a',
  '--border': '#333a42',
  '--muted': '#9aa5b0',
  '--text': '#dfe4e8',
  '--accent': '#3d9be0',
};

describe('buildMetricOption', () => {
  it('never hands a CSS custom property to the canvas renderer', () => {
    const offenders = stringsIn(buildMetricOption(series, tokens)).filter((s) => s.includes('var(--'));
    expect(offenders).toEqual([]);
  });

  it('paints the axes, grid and tooltip with the RESOLVED token values', () => {
    const option = buildMetricOption(series, tokens) as Record<string, never>;
    const found = stringsIn(option);
    for (const token of CHART_TOKENS) {
      expect(found, `${token} never reaches the option`).toContain(tokens[token]);
    }
  });

  it('gives the tooltip container an explicit background, which is the only way to reach it', () => {
    // echarts owns and inline-styles the tooltip box; a stylesheet rule cannot override it, so
    // leaving these unset left a white box under near-white text at 1.28:1 in dark mode.
    const tooltip = buildMetricOption(series, tokens).tooltip as {
      backgroundColor?: string;
      borderColor?: string;
      textStyle?: { color?: string };
    };
    expect(tooltip.backgroundColor).toBe(tokens['--panel']);
    expect(tooltip.borderColor).toBe(tokens['--border']);
    expect(tooltip.textStyle?.color).toBe(tokens['--text']);
  });

  it('builds an empty chart rather than throwing when the series has not arrived', () => {
    expect(() => buildMetricOption(null, tokens)).not.toThrow();
  });
});

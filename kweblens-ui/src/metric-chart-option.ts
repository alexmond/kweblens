import type { EChartsOption } from 'echarts';

import { fmtStamp, fmtValue } from './format';
import type { MetricSeries } from './types';

/**
 * The echarts option for a metrics chart, built from RESOLVED colours.
 *
 * It lives outside `MetricChart.vue` so `metric-chart-option.test.ts` can assert the one
 * invariant that matters here: no `var(--…)` may ever reach the option. The chart is drawn by
 * echarts' CanvasRenderer, and a canvas cannot resolve a CSS custom property — it rejects the
 * assignment and keeps its default, which is black. That is how the axis labels and grid lines
 * came to be painted pure black on the dark theme's #1f242a panel (1.34:1) while every review
 * passed: the failure is invisible to `contrast-check.mjs`, which samples DOM nodes, and canvas
 * text has no node. `useThemeTokens` supplies the resolved values.
 *
 * The series colour comes from `--accent` for the same reason it now can: it used to be a
 * hard-coded `#0a7ac2`, which is the LIGHT theme's accent and measured 3.40:1 on the dark
 * panel. The token is 5.18:1 there, and matches what App.vue already does for every other
 * accent surface.
 */

/** Tokens the chart needs as real colour strings. Pass the resolved map to `buildMetricOption`. */
export const CHART_TOKENS = ['--panel', '--border', '--muted', '--text', '--accent'] as const;

export const buildMetricOption = (series: MetricSeries | null, tokens: Record<string, string>): EChartsOption => {
  const unit = series?.unit ?? '';
  const data = (series?.points ?? []).map((p) => [p.t * 1000, p.v]);
  const panel = tokens['--panel'];
  const border = tokens['--border'];
  const muted = tokens['--muted'];
  const text = tokens['--text'];
  const accent = tokens['--accent'];
  return {
    grid: { left: 52, right: 12, top: 10, bottom: 22 },
    // The tooltip container is echarts' own div and echarts INLINE-STYLES it, so a stylesheet
    // rule cannot reach it; its colours have to be set here. They were not, which left the
    // default white box carrying near-white `.chart-tip-v` text at 1.28:1 in dark mode.
    tooltip: {
      trigger: 'axis',
      backgroundColor: panel,
      borderColor: border,
      borderRadius: 6,
      padding: [5, 8],
      textStyle: { color: text, fontSize: 11 },
      formatter: (params: unknown) => {
        const p = (params as { data: [number, number] }[])[0];
        return `<div class="chart-tip-t">${fmtStamp(p.data[0] / 1000)}</div><div class="chart-tip-v">${fmtValue(unit, p.data[1])}</div>`;
      },
    },
    xAxis: {
      type: 'time',
      axisLabel: {
        fontSize: 10,
        color: muted,
        // At the width these cards get, echarts lays out enough time ticks that the labels run
        // into one another — "00:2000:2500:30…". That has always been true and always visible
        // in light mode; in dark the labels were black on a black panel, so nobody saw it, and
        // making them readable is what put it on screen.
        hideOverlap: true,
        formatter: (v: number) => new Date(v).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
      },
      axisLine: { lineStyle: { color: border } },
    },
    yAxis: {
      type: 'value',
      axisLabel: { fontSize: 10, color: muted, formatter: (v: number) => fmtValue(unit, v) },
      splitLine: { lineStyle: { color: border, type: 'dashed' } },
    },
    series: [
      {
        type: 'line',
        smooth: true,
        showSymbol: false,
        data,
        lineStyle: { color: accent, width: 1.5 },
        itemStyle: { color: accent },
        areaStyle: { color: accent, opacity: 0.14 },
      },
    ],
  };
};

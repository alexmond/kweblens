<script setup lang="ts">
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import type { EChartsOption } from 'echarts';
import { computed, ref, watch } from 'vue';
import VChart from 'vue-echarts';

import { api } from '../api';
import { fmtStamp, fmtValue } from '../format';
import type { MetricPoint, MetricSeries } from '../types';

use([LineChart, GridComponent, TooltipComponent, CanvasRenderer]);

const ACCENT = '#0a7ac2';

const props = defineProps<{
  cluster: string;
  target: string;
  namespace?: string;
  name?: string;
  label: string;
}>();

const series = ref<MetricSeries | null>(null);
const selected = ref<MetricPoint | null>(null);

watch(
  () => [props.cluster, props.target, props.namespace, props.name],
  () => {
    series.value = null;
    selected.value = null;
    api
      .metricGraph(props.cluster, props.target, { namespace: props.namespace, name: props.name, minutes: 60 })
      .then((s) => (series.value = s))
      .catch(() => (series.value = { available: false, unit: '', points: [] }));
  },
  { immediate: true },
);

const state = computed<'loading' | 'unavailable' | 'empty' | 'ok'>(() => {
  const s = series.value;
  if (s === null) {
    return 'loading';
  }
  if (!s.available) {
    return 'unavailable';
  }
  return s.points.length === 0 ? 'empty' : 'ok';
});

const meta = computed(() => {
  const s = series.value;
  if (!s || s.points.length === 0) {
    return '';
  }
  const vals = s.points.map((p) => p.v);
  return `now ${fmtValue(s.unit, vals[vals.length - 1])} · peak ${fmtValue(s.unit, Math.max(...vals))}`;
});

const option = computed<EChartsOption>(() => {
  const s = series.value;
  const unit = s?.unit ?? '';
  const data = (s?.points ?? []).map((p) => [p.t * 1000, p.v]);
  return {
    grid: { left: 52, right: 12, top: 10, bottom: 22 },
    tooltip: {
      trigger: 'axis',
      formatter: (params: unknown) => {
        const p = (params as { data: [number, number] }[])[0];
        return `<div class="chart-tip-t">${fmtStamp(p.data[0] / 1000)}</div><div class="chart-tip-v">${fmtValue(unit, p.data[1])}</div>`;
      },
    },
    xAxis: {
      type: 'time',
      axisLabel: {
        fontSize: 10,
        color: 'var(--muted)',
        formatter: (v: number) => new Date(v).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
      },
      axisLine: { lineStyle: { color: 'var(--border)' } },
    },
    yAxis: {
      type: 'value',
      axisLabel: { fontSize: 10, color: 'var(--muted)', formatter: (v: number) => fmtValue(unit, v) },
      splitLine: { lineStyle: { color: 'var(--border)', type: 'dashed' } },
    },
    series: [
      {
        type: 'line',
        smooth: true,
        showSymbol: false,
        data,
        lineStyle: { color: ACCENT, width: 1.5 },
        itemStyle: { color: ACCENT },
        areaStyle: { color: ACCENT, opacity: 0.14 },
      },
    ],
  };
});

const onPointClick = (params: unknown) => {
  const p = params as { data?: [number, number] };
  if (p.data) {
    selected.value = { t: p.data[0] / 1000, v: p.data[1] };
  }
};
</script>

<template>
  <div class="chart">
    <div class="chart-title">{{ label }}</div>
    <div v-if="state === 'loading'" class="empty">Loading…</div>
    <div v-else-if="state === 'unavailable'" class="empty">Graphs need a Prometheus / VictoriaMetrics backend.</div>
    <div v-else-if="state === 'empty'" class="empty">No data.</div>
    <div v-else class="spark">
      <VChart class="metric-echart" :option="option" autoresize @click="onPointClick" />
      <div class="spark-meta">
        <span v-if="selected" class="chart-sel">
          selected {{ fmtStamp(selected.t) }} · <strong>{{ fmtValue(series!.unit, selected.v) }}</strong>
          <button class="chart-sel-clear" aria-label="Clear selection" @click="selected = null">×</button>
        </span>
        <template v-else>{{ meta }} · click a point to inspect</template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.metric-echart {
  width: 100%;
  height: 150px;
}
</style>

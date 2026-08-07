<script setup lang="ts">
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import type { EChartsOption } from 'echarts';
import { computed, ref, watch } from 'vue';
import VChart from 'vue-echarts';

import { api } from '../api';
import { useThemeTokens } from '../composables/useThemeTokens';
import { fmtStamp, fmtValue } from '../format';
import { buildMetricOption, CHART_TOKENS } from '../metric-chart-option';
import type { MetricPoint, MetricSeries } from '../types';

use([LineChart, GridComponent, TooltipComponent, CanvasRenderer]);

// The chart is a CANVAS: `var(--muted)` handed to it is discarded and echarts falls back to
// black. Resolve the tokens to real colours, and re-resolve them when the theme flips.
const tokens = useThemeTokens(CHART_TOKENS);

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

const option = computed<EChartsOption>(() => buildMetricOption(series.value, tokens.value));

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
  /* Clicking a point selects it, so hint that the surface is interactive. The rule used to be
     `.chart .recharts-wrapper` in styles.css and stopped matching anything at the echarts
     migration; it lives with the component now so it cannot be orphaned again. */
  cursor: pointer;
}
</style>

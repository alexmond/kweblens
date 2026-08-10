<script setup lang="ts">
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import type { EChartsOption } from 'echarts';
import { computed, ref, watch } from 'vue';
import VChart from 'vue-echarts';

import { api } from '../api';
import { failureNotice } from '../apiFailure';
import { useThemeTokens } from '../composables/useThemeTokens';
import { metricSeriesEmpty } from '../emptyState';
import { fmtStamp, fmtValue } from '../format';
import { buildMetricOption, CHART_TOKENS } from '../metric-chart-option';
import type { MetricPoint, MetricSeries } from '../types';
import EmptyState from './EmptyState.vue';
import ErrorNotice from './ErrorNotice.vue';
import LoadingNotice from './LoadingNotice.vue';

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
// A failed call used to be written as `{ available: false }`, which the chart rendered as
// "Graphs need a Prometheus / VictoriaMetrics backend" — a confident statement about how the
// operator's cluster is configured, produced by a `.catch` that never looked at the error.
// `available: false` now means only what the server said it means, and a failure is a
// failure, with a retry (R3, same rule as #306).
const error = ref<string | null>(null);

const load = () => {
  series.value = null;
  selected.value = null;
  error.value = null;
  api
    .metricGraph(props.cluster, props.target, { namespace: props.namespace, name: props.name, minutes: 60 })
    .then((s) => (series.value = s))
    .catch((e) => (error.value = failureNotice(e)));
};

watch(() => [props.cluster, props.target, props.namespace, props.name], load, { immediate: true });

const loading = computed(() => series.value === null && error.value === null);
const emptyCopy = computed(() =>
  metricSeriesEmpty({
    loading: loading.value,
    failed: error.value !== null,
    available: series.value?.available ?? false,
    points: series.value?.points.length ?? 0,
  }),
);

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
    <LoadingNotice v-if="loading" />
    <ErrorNotice v-else-if="error" :message="error" @retry="load()" />
    <EmptyState v-else-if="emptyCopy" :title="emptyCopy.title" :body="emptyCopy.body" variant="inline" />
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

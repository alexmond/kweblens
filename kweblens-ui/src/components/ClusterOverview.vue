<script setup lang="ts">
// The cluster dashboard: node/namespace/warnings stat cards, API-server line, cluster
// CPU + memory metric charts, and a warnings table built from the cluster's events.
// Emits nothing — purely presentational (data is fetched internally per cluster).
import { NDataTable } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { shallowRef, computed, ref, watch } from 'vue';

import { api } from '../api';
import { ageToSeconds } from '../kube';
import type { EventSummary, KubeObject } from '../types';
import MetricChart from './MetricChart.vue';
import StatCard from './StatCard.vue';

const props = defineProps<{ cluster: string; name: string; masterUrl?: string; namespaceCount: number }>();

const nodes = shallowRef<KubeObject[] | null>(null);
const warnings = shallowRef<EventSummary[] | null>(null);
const err = ref<string | null>(null);

let reqId = 0;
watch(
  () => props.cluster,
  (cluster) => {
    const my = ++reqId;
    nodes.value = null;
    warnings.value = null;
    err.value = null;
    api
      .objects(cluster, 'nodes')
      .then((n) => my === reqId && (nodes.value = n))
      .catch((e) => my === reqId && (err.value = String(e)));
    api
      .events(cluster)
      .then((ev) => my === reqId && (warnings.value = ev.filter((x) => x.type === 'Warning')))
      .catch(() => my === reqId && (warnings.value = []));
  },
  { immediate: true },
);

const nodeReady = (o: KubeObject): boolean => {
  const conds = ((o.status as Record<string, unknown>)?.conditions as Record<string, unknown>[]) ?? [];
  const r = conds.find((c) => c.type === 'Ready');
  return r ? r.status === 'True' : false;
};
const readyNodes = computed(() => (nodes.value ?? []).filter(nodeReady).length);

const warnColumns: DataTableColumns<EventSummary> = [
  { title: 'Reason', key: 'reason', sorter: 'default' },
  { title: 'Object', key: 'object', sorter: 'default' },
  { title: 'Message', key: 'message', sorter: 'default' },
  { title: 'Age', key: 'age', sorter: (a, b) => ageToSeconds(a.age) - ageToSeconds(b.age), defaultSortOrder: 'ascend' },
];
// Capped for rendering, but the cap is REPORTED. Previously the stat card showed the true
// total while the table showed 30, so the page contradicted itself.
const WARNING_LIMIT = 30;
const warnRows = computed(() => (warnings.value ?? []).slice(0, WARNING_LIMIT));
const warningsTruncated = computed(() => (warnings.value?.length ?? 0) > WARNING_LIMIT);
</script>

<template>
  <div class="overview">
    <h1 class="ov-title">{{ name }}</h1>
    <div class="ov-cards">
      <StatCard :value="nodes ? nodes.length : '…'" :label="`Nodes${nodes ? ` · ${readyNodes} ready` : ''}`" />
      <StatCard :value="namespaceCount" label="Namespaces" />
      <StatCard
        :value="warnings ? warnings.length : '…'"
        label="Warnings"
        :danger="!!(warnings && warnings.length > 0)"
      />
    </div>
    <div v-if="masterUrl" class="ov-api">
      API server: <span class="mono">{{ masterUrl }}</span>
    </div>
    <div v-if="err" class="error">{{ err }}</div>
    <div class="charts">
      <MetricChart :cluster="cluster" target="cluster-cpu" label="Cluster CPU (cores)" />
      <MetricChart :cluster="cluster" target="cluster-mem" label="Cluster Memory" />
    </div>
    <section class="ov-sec">
      <h3>Warnings</h3>
      <div v-if="warningsTruncated" class="ov-truncated">
        Showing the {{ WARNING_LIMIT }} most recent of {{ warnings?.length }} warnings.
      </div>
      <div v-if="warnings && warnings.length === 0" class="empty">No warnings.</div>
      <NDataTable
        v-else
        :columns="warnColumns"
        :data="warnRows"
        :loading="warnings === null"
        :row-key="(w) => `${w.object}/${w.reason}/${w.age}`"
        size="small"
      />
    </section>
  </div>
</template>

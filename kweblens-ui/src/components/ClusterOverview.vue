<script setup lang="ts">
// The cluster dashboard: node/namespace/warnings stat cards, API-server line, cluster
// CPU + memory metric charts, and a warnings table built from the cluster's events.
// Emits nothing — purely presentational (data is fetched internally per cluster).
import { computed, ref, watch } from 'vue';

import { api } from '../api';
import { ageToSeconds } from '../kube';
import { useTableSort } from '../composables/useTableSort';
import type { EventSummary, KubeObject } from '../types';
import MetricChart from './MetricChart.vue';
import SortTh from './SortTh.vue';
import StatCard from './StatCard.vue';

const props = defineProps<{ cluster: string; name: string; masterUrl?: string; namespaceCount: number }>();

const nodes = ref<KubeObject[] | null>(null);
const warnings = ref<EventSummary[] | null>(null);
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

const { sorted, sort, clickHeader } = useTableSort(
  () => warnings.value ?? [],
  'age',
  (w, k) => (k === 'age' ? ageToSeconds(w.age) : ((w[k as keyof EventSummary] as string) ?? '')),
);
const warnRows = computed(() => sorted.value.slice(0, 30));
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
      <div v-if="warnings === null" class="empty">Loading…</div>
      <div v-else-if="warnings.length === 0" class="empty">No warnings.</div>
      <table v-else class="mini">
        <thead>
          <tr>
            <SortTh label="Reason" col-key="reason" :sort="sort" @sort="clickHeader" />
            <SortTh label="Object" col-key="object" :sort="sort" @sort="clickHeader" />
            <SortTh label="Message" col-key="message" :sort="sort" @sort="clickHeader" />
            <SortTh label="Age" col-key="age" :sort="sort" @sort="clickHeader" />
          </tr>
        </thead>
        <tbody>
          <tr v-for="(w, i) in warnRows" :key="i" class="warn">
            <td>{{ w.reason }}</td>
            <td>{{ w.object }}</td>
            <td>{{ w.message }}</td>
            <td>{{ w.age }}</td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>

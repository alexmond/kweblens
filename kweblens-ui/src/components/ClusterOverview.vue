<script setup lang="ts">
// The cluster dashboard: node/namespace/warnings stat cards, API-server line, cluster
// CPU + memory metric charts, and a warnings table built from the cluster's events.
//
// Cards and warning rows navigate; the shell owns where to (this only names a kind). Note the
// split scope: warnings follow the namespace filter, while nodes and the cluster metric charts
// are cluster-scoped and CANNOT — so they say so instead of quietly showing unfiltered numbers
// beside filtered ones.
import DiagnosisPanel from './DiagnosisPanel.vue';
import { NDataTable } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { shallowRef, computed, ref, watch } from 'vue';

import { api } from '../api';
import { ageToSeconds, eventObjectKind } from '../kube';
import type { EventSummary, KubeObject } from '../types';
import MetricChart from './MetricChart.vue';
import StatCard from './StatCard.vue';

const props = defineProps<{
  cluster: string;
  name: string;
  masterUrl?: string;
  namespaceCount: number;
  namespace?: string | null;
  /** Whether the shell can navigate to a kind — a row with nowhere to go must not look clickable. */
  knowsKind?: (kind: string) => boolean;
  /** Passed to the diagnosis panel: its Analyse trigger is a POST and needs the admin login. */
  authed?: boolean;
}>();

const emit = defineEmits<{
  (e: 'navigate', kind: string, namespace?: string): void;
  (e: 'require-auth'): void;
}>();

const nodes = shallowRef<KubeObject[] | null>(null);
const warnings = shallowRef<EventSummary[] | null>(null);
const err = ref<string | null>(null);

let reqId = 0;
watch(
  () => [props.cluster, props.namespace] as const,
  ([cluster, namespace]) => {
    const my = ++reqId;
    nodes.value = null;
    warnings.value = null;
    err.value = null;
    api
      .objects(cluster, 'nodes')
      .then((n) => my === reqId && (nodes.value = n))
      .catch((e) => my === reqId && (err.value = String(e)));
    api
      .events(cluster, namespace ?? undefined)
      .then((ev) => my === reqId && (warnings.value = ev.filter((x) => x.type === 'Warning')))
      .catch(() => my === reqId && (warnings.value = []));
  },
  { immediate: true },
);

/** The kind a warning row would open, or null when it has nowhere to go. */
const rowKind = (w: EventSummary): string | null => {
  const kind = eventObjectKind(w.object);
  return kind && (!props.knowsKind || props.knowsKind(kind)) ? kind : null;
};

const rowProps = (w: EventSummary) => {
  const kind = rowKind(w);
  if (!kind) {
    return {};
  }
  const go = () => emit('navigate', kind, w.namespace ?? undefined);
  return { class: 'row-link', style: { cursor: 'pointer' }, onClick: go };
};

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
    <div class="ov-scope-note">{{ namespace ? `Namespace: ${namespace}` : 'All namespaces' }}</div>
    <div class="ov-cards">
      <StatCard
        :value="nodes ? nodes.length : '…'"
        :label="`Nodes${nodes ? ` · ${readyNodes} ready` : ''}`"
        clickable
        @select="emit('navigate', 'Node')"
      />
      <StatCard :value="namespaceCount" label="Namespaces" clickable @select="emit('navigate', 'Namespace')" />
      <StatCard
        :value="warnings ? warnings.length : '…'"
        label="Warnings"
        :danger="!!(warnings && warnings.length > 0)"
      />
    </div>
    <!-- Nodes and the charts below are cluster-scoped. Saying so is the honest alternative to
         either ignoring the filter silently or pretending these can be narrowed. -->
    <div v-if="namespace" class="ov-scope-note">Nodes and cluster metrics are cluster-wide and ignore this filter.</div>
    <div v-if="masterUrl" class="ov-api">
      API server: <span class="mono">{{ masterUrl }}</span>
    </div>
    <div v-if="err" class="error">{{ err }}</div>
    <div class="charts">
      <MetricChart :cluster="cluster" target="cluster-cpu" label="Cluster CPU (cores)" />
      <MetricChart :cluster="cluster" target="cluster-mem" label="Cluster Memory" />
    </div>
    <!-- Diagnosis sits above Warnings: warnings are raw events, diagnosis is the reading
         of them plus what to do. Reason before evidence. -->
    <DiagnosisPanel
      :cluster="cluster"
      :namespace="namespace ?? null"
      :authed="authed"
      @require-auth="emit('require-auth')"
    />

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
        :row-props="rowProps"
        size="small"
      />
    </section>
  </div>
</template>

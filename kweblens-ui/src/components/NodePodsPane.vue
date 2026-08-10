<script setup lang="ts">
// The Pods tab of a node's detail: every pod scheduled on that node, with live CPU/Memory
// usage bars in the same style as the node list's perf columns. Rows are clickable and emit
// `open` so the shell swaps the drawer to that pod's detail.
//
// Per-pod DISK is deliberately absent: metrics-server (which backs /metrics/pods) reports
// CPU and memory only — there is no pod-level disk figure to show. The node's own disk is
// on the Metrics tab.
import { NDataTable } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { computed, h, onBeforeUnmount, ref, shallowRef, watch } from 'vue';

import { api } from '../api';
import { failureNotice } from '../apiFailure';
import { nodePodsEmpty } from '../emptyState';
import { gib, objKey, objName, objNs, parseCpuCores, parseMemBytes } from '../kube';
import type { KubeObject, UsageSummary } from '../types';
import EmptyState from './EmptyState.vue';
import LoadingNotice from './LoadingNotice.vue';
import StatusBadge from './StatusBadge.vue';
import UsageBar from './UsageBar.vue';

const props = defineProps<{ cluster: string; node: string }>();
const emit = defineEmits<{ (e: 'open', obj: KubeObject): void }>();

const pods = shallowRef<KubeObject[] | null>(null);
const usage = shallowRef<Record<string, UsageSummary>>({});
const error = ref<string | null>(null);

const emptyCopy = computed(() =>
  nodePodsEmpty({
    loading: pods.value === null,
    failed: error.value !== null,
    count: pods.value?.length ?? 0,
    node: props.node,
  }),
);

const podPhase = (o: KubeObject): string => String((o.status as Record<string, unknown>)?.phase ?? '—');
/** A pod's requested CPU/memory summed over its containers — the usage bar's denominator. */
const requested = (o: KubeObject, key: 'cpu' | 'memory'): string[] =>
  (((o.spec as Record<string, unknown>)?.containers as Record<string, unknown>[]) ?? [])
    .map((c) => ((c.resources as Record<string, unknown>)?.requests as Record<string, string>)?.[key])
    .filter(Boolean) as string[];

const cpuRequested = (o: KubeObject) => requested(o, 'cpu').reduce((n, q) => n + parseCpuCores(q), 0);
const memRequested = (o: KubeObject) => requested(o, 'memory').reduce((n, q) => n + parseMemBytes(q), 0);

// Live usage for a pod, as NUMBERS — the perf columns sort on these, never on the formatted
// text ("0.9" would otherwise sort above "10", and "900Mi" above "2.0Gi").
const cpuUsed = (o: KubeObject) => parseCpuCores(usage.value[objKey(o)]?.cpu);
const memUsed = (o: KubeObject) => parseMemBytes(usage.value[objKey(o)]?.memory);
const byText = (a: string, b: string) => a.localeCompare(b, undefined, { numeric: true });

const columns = computed<DataTableColumns<KubeObject>>(() => [
  // Sized to fit the drawer at its DEFAULT width: name/namespace flex and ellipsize while
  // the perf columns keep fixed room, so CPU/Memory are visible without expanding or
  // scrolling. (Expanding the drawer just gives the name more room.)
  // Default sort is name-ascending on purpose: usage refreshes every 15s, so defaulting to a
  // metric column would make rows jump under the cursor. Sorting by CPU/Memory is one click
  // away, and there the live re-ordering is what you asked for.
  {
    title: 'Name',
    key: 'name',
    minWidth: 120,
    ellipsis: { tooltip: true },
    sorter: (a, b) => byText(objName(a), objName(b)),
    defaultSortOrder: 'ascend',
    render: (o) => objName(o),
  },
  {
    title: 'Namespace',
    key: 'ns',
    minWidth: 90,
    ellipsis: { tooltip: true },
    sorter: (a, b) => byText(objNs(a) ?? '', objNs(b) ?? ''),
    render: (o) => objNs(o) ?? '—',
  },
  {
    title: 'Status',
    key: 'status',
    width: 84,
    sorter: (a, b) => byText(podPhase(a), podPhase(b)),
    render: (o) => h(StatusBadge, { text: podPhase(o) }),
  },
  {
    title: 'CPU',
    key: 'cpu',
    width: 104,
    sorter: (a, b) => cpuUsed(a) - cpuUsed(b),
    render: (o) => {
      const u = usage.value[objKey(o)];
      if (!u) {
        return '—';
      }
      const used = parseCpuCores(u.cpu);
      const req = cpuRequested(o);
      return h(UsageBar, {
        fraction: req ? used / req : 0,
        color: '#2e9e8f',
        text: req ? `${used.toFixed(2)} / ${req.toFixed(2)}` : used.toFixed(2),
      });
    },
  },
  {
    title: 'Memory',
    key: 'mem',
    width: 104,
    sorter: (a, b) => memUsed(a) - memUsed(b),
    render: (o) => {
      const u = usage.value[objKey(o)];
      if (!u) {
        return '—';
      }
      const used = parseMemBytes(u.memory);
      const req = memRequested(o);
      return h(UsageBar, {
        fraction: req ? used / req : 0,
        color: '#c026a8',
        text: req ? `${gib(used)} / ${gib(req)}` : gib(used),
      });
    },
  },
]);

// Load the node's pods, plus pod usage (refreshed on the same 15s cadence as the node list).
let timer = 0;
const load = (first: boolean) => {
  if (first) {
    api
      .nodePods(props.cluster, props.node)
      .then((p) => (pods.value = p))
      .catch((e) => (error.value = failureNotice(e)));
  }
  api
    .podMetrics(props.cluster)
    .then((list) => {
      const map: Record<string, UsageSummary> = {};
      list.forEach((u) => (map[(u.namespace ?? '') + '/' + u.name] = u));
      usage.value = map;
    })
    .catch(() => undefined);
};

watch(
  () => [props.cluster, props.node],
  () => {
    pods.value = null;
    error.value = null;
    load(true);
    window.clearInterval(timer);
    timer = window.setInterval(() => load(false), 15000);
  },
  { immediate: true },
);
onBeforeUnmount(() => window.clearInterval(timer));

const rowProps = (o: KubeObject) => ({ style: 'cursor: pointer', onClick: () => emit('open', o) });
</script>

<template>
  <div class="node-pods">
    <div v-if="error" class="error">{{ error }}</div>
    <LoadingNotice v-else-if="pods === null" />
    <EmptyState v-else-if="emptyCopy" :title="emptyCopy.title" :body="emptyCopy.body" variant="inline" />
    <NDataTable v-else :columns="columns" :data="pods" :row-key="objKey" :row-props="rowProps" size="small" />
  </div>
</template>

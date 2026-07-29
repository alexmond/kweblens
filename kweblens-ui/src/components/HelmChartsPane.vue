<script setup lang="ts">
import { NDataTable, NInput } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { shallowRef, computed, h, ref, watch } from 'vue';

import { api } from '../api';
import KebabMenu from './KebabMenu.vue';
import type { HelmAction, KebabItem } from './helm-types';
import type { HelmChart } from '../types';

// Searchable chart catalogue (from the configured repositories). Each row's kebab installs
// the chart, which the parent turns into an install action (auth-gated there).
//
// Events emitted:
//   (e: 'action', a: HelmAction): void   — request the install modal for a chart
const props = defineProps<{ cluster: string }>();
const emit = defineEmits<{ (e: 'action', a: HelmAction): void }>();

const charts = shallowRef<HelmChart[] | null>(null);
const error = ref<string | null>(null);
const query = ref('');

watch(
  () => props.cluster,
  (cluster, _old, onCleanup) => {
    let cancelled = false;
    onCleanup(() => (cancelled = true));
    charts.value = null;
    error.value = null;
    api
      .helmCharts(cluster)
      .then((c) => !cancelled && (charts.value = c))
      .catch((e) => !cancelled && (error.value = String(e)));
  },
  { immediate: true },
);

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase();
  return (charts.value ?? []).filter(
    (c) => !q || c.name.toLowerCase().includes(q) || (c.description ?? '').toLowerCase().includes(q),
  );
});

const items = (c: HelmChart): KebabItem[] => [
  {
    label: 'Install',
    onClick: () => emit('action', { mode: 'install', repository: c.repository, chart: c.name, version: c.version }),
  },
];

const cmp = (k: keyof HelmChart) => (a: HelmChart, b: HelmChart) =>
  String(a[k] ?? '').localeCompare(String(b[k] ?? ''), undefined, { numeric: true });

const columns = computed<DataTableColumns<HelmChart>>(() => [
  { title: 'Name', key: 'name', sorter: cmp('name'), defaultSortOrder: 'ascend', render: (r) => r.name },
  { title: 'Description', key: 'description', sorter: cmp('description'), render: (r) => r.description ?? '—' },
  { title: 'Version', key: 'version', sorter: cmp('version'), render: (r) => r.version },
  { title: 'App Version', key: 'appVersion', sorter: cmp('appVersion'), render: (r) => r.appVersion ?? '—' },
  { title: 'Repository', key: 'repository', sorter: cmp('repository'), render: (r) => r.repository },
  { title: '', key: '_menu', width: 44, render: (r) => h(KebabMenu, { items: items(r) }) },
]);

const rowKey = (r: HelmChart) => `${r.repository}/${r.name}`;
</script>

<template>
  <div class="content-head">
    <span class="count">{{ charts ? `${filtered.length} charts` : '' }}</span>
    <div class="spacer" />
    <NInput v-model:value="query" placeholder="Search charts…" clearable style="max-width: 240px" />
  </div>
  <div v-if="error" class="error">{{ error }}</div>
  <NDataTable :columns="columns" :data="filtered" :row-key="rowKey" :loading="charts === null" size="small">
    <template #empty>
      {{ charts === null ? 'Loading…' : 'No charts. Configure repositories under kweblens.helm.repositories.' }}
    </template>
  </NDataTable>
</template>

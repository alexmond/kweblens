<script setup lang="ts">
import { computed, ref, watch } from 'vue';

import { api } from '../api';
import { useTableSort } from '../composables/useTableSort';
import SortTh from './SortTh.vue';
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

const charts = ref<HelmChart[] | null>(null);
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

const { sorted, sort, clickHeader } = useTableSort(
  () => filtered.value,
  'name',
  (c, k) => (c[k as keyof HelmChart] as string) ?? '',
);

const items = (c: HelmChart): KebabItem[] => [
  {
    label: 'Install',
    onClick: () => emit('action', { mode: 'install', repository: c.repository, chart: c.name, version: c.version }),
  },
];
</script>

<template>
  <div class="content-head">
    <span class="count">{{ charts ? `${filtered.length} charts` : '' }}</span>
    <div class="spacer" />
    <input v-model="query" class="search" type="search" placeholder="Search charts…" />
  </div>
  <div v-if="error" class="error">{{ error }}</div>
  <div v-if="charts === null" class="empty">Loading…</div>
  <div v-else-if="filtered.length === 0" class="empty">
    No charts. Configure repositories under kweblens.helm.repositories.
  </div>
  <table v-else class="grid">
    <thead>
      <tr>
        <SortTh label="Name" col-key="name" :sort="sort" @sort="clickHeader" />
        <SortTh label="Description" col-key="description" :sort="sort" @sort="clickHeader" />
        <SortTh label="Version" col-key="version" :sort="sort" @sort="clickHeader" />
        <SortTh label="App Version" col-key="appVersion" :sort="sort" @sort="clickHeader" />
        <SortTh label="Repository" col-key="repository" :sort="sort" @sort="clickHeader" />
        <th />
      </tr>
    </thead>
    <tbody>
      <tr v-for="c in sorted" :key="c.repository + '/' + c.name">
        <td class="name">{{ c.name }}</td>
        <td class="muted">{{ c.description ?? '—' }}</td>
        <td>{{ c.version }}</td>
        <td>{{ c.appVersion ?? '—' }}</td>
        <td>{{ c.repository }}</td>
        <td class="row-actions"><KebabMenu :items="items(c)" /></td>
      </tr>
    </tbody>
  </table>
</template>

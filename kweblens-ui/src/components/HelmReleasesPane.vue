<script setup lang="ts">
import { ref, watch } from 'vue';

import { api } from '../api';
import { age } from '../columns';
import { useDialog } from '../dialog';
import { useTableSort } from '../composables/useTableSort';
import SortTh from './SortTh.vue';
import StatusBadge from './StatusBadge.vue';
import KebabMenu from './KebabMenu.vue';
import type { HelmAction, KebabItem } from './helm-types';
import type { HelmRelease } from '../types';

// Installed releases: sortable table with an "update available" chip and per-row actions
// (resources / values / history / update / upgrade / rollback / uninstall).
//
// Props:
//   refreshKey — bumped by the parent after a mutation applies, to force a reload.
//
// Events emitted:
//   (e: 'action', a: HelmAction): void                 — request the action modal
//   (e: 'resources', ns: string, name: string): void   — open the resources modal
//   (e: 'values', ns: string, name: string): void      — open the stored-values modal
//   (e: 'history', ns: string, name: string): void     — open the history modal
//   (e: 'require-auth'): void                           — unauthenticated uninstall attempt
const props = defineProps<{ cluster: string; authed: boolean; refreshKey: number }>();
const emit = defineEmits<{
  (e: 'action', a: HelmAction): void;
  (e: 'resources', ns: string, name: string): void;
  (e: 'values', ns: string, name: string): void;
  (e: 'history', ns: string, name: string): void;
  (e: 'require-auth'): void;
}>();

const dialog = useDialog();
const releases = ref<HelmRelease[] | null>(null);
const error = ref<string | null>(null);
const localKey = ref(0);

watch(
  () => [props.cluster, props.refreshKey, localKey.value],
  (_v, _o, onCleanup) => {
    let cancelled = false;
    onCleanup(() => (cancelled = true));
    releases.value = null;
    error.value = null;
    api
      .helmReleases(props.cluster)
      .then((r) => !cancelled && (releases.value = r))
      .catch((e) => !cancelled && (error.value = String(e)));
  },
  { immediate: true },
);

const uninstall = (r: HelmRelease) => {
  if (!props.authed) {
    emit('require-auth');
    return;
  }
  dialog
    .confirm({
      title: 'Uninstall release',
      message: `Uninstall release "${r.name}" in ${r.namespace}? This removes its resources and history.`,
      confirmLabel: 'Uninstall',
      danger: true,
    })
    .then((ok) => {
      if (!ok) {
        return;
      }
      api
        .helmUninstall(props.cluster, r.namespace, r.name)
        .then(() => (localKey.value += 1))
        .catch((e) => (error.value = String(e)));
    });
};

const { sorted, sort, clickHeader } = useTableSort(
  () => releases.value ?? [],
  'name',
  (r, k) => {
    if (k === 'revision') {
      return r.revision;
    }
    if (k === 'updated') {
      return Date.parse(r.updated ?? '') || 0;
    }
    return (r[k as keyof HelmRelease] as string) ?? '';
  },
);

const items = (r: HelmRelease): KebabItem[] => {
  const list: KebabItem[] = [
    { label: 'Resources', onClick: () => emit('resources', r.namespace, r.name) },
    { label: 'Values', onClick: () => emit('values', r.namespace, r.name) },
    { label: 'History', onClick: () => emit('history', r.namespace, r.name) },
  ];
  if (r.updateAvailable) {
    list.push({
      label: `Update → ${r.latestVersion}`,
      onClick: () =>
        emit('action', {
          mode: 'upgrade',
          namespace: r.namespace,
          name: r.name,
          chart: r.chart,
          chartVersion: r.chartVersion,
          repository: r.latestRepository ?? undefined,
          version: r.latestVersion ?? undefined,
        }),
    });
  }
  list.push({
    label: 'Upgrade',
    onClick: () =>
      emit('action', {
        mode: 'upgrade',
        namespace: r.namespace,
        name: r.name,
        chart: r.chart,
        chartVersion: r.chartVersion,
      }),
  });
  list.push({
    label: 'Rollback',
    disabled: r.revision <= 1,
    onClick: () => emit('action', { mode: 'rollback', namespace: r.namespace, name: r.name, revision: r.revision - 1 }),
  });
  list.push({ label: 'Uninstall', danger: true, onClick: () => uninstall(r) });
  return list;
};
</script>

<template>
  <div class="content-head">
    <span class="count">{{ releases ? `${releases.length} releases` : '' }}</span>
  </div>
  <div v-if="error" class="error">{{ error }}</div>
  <div v-if="releases === null" class="empty">Loading…</div>
  <div v-else-if="releases.length === 0" class="empty">No releases.</div>
  <table v-else class="grid">
    <thead>
      <tr>
        <SortTh label="Name" col-key="name" :sort="sort" @sort="clickHeader" />
        <SortTh label="Namespace" col-key="namespace" :sort="sort" @sort="clickHeader" />
        <SortTh label="Chart" col-key="chart" :sort="sort" @sort="clickHeader" />
        <SortTh label="Source" col-key="managedByKweblens" :sort="sort" @sort="clickHeader" />
        <SortTh label="Revision" col-key="revision" :sort="sort" @sort="clickHeader" />
        <SortTh label="Version" col-key="chartVersion" :sort="sort" @sort="clickHeader" />
        <SortTh label="App Version" col-key="appVersion" :sort="sort" @sort="clickHeader" />
        <SortTh label="Status" col-key="status" :sort="sort" @sort="clickHeader" />
        <SortTh label="Updated" col-key="updated" :sort="sort" @sort="clickHeader" />
        <th />
      </tr>
    </thead>
    <tbody>
      <tr v-for="r in sorted" :key="r.namespace + '/' + r.name">
        <td class="name">{{ r.name }}</td>
        <td>{{ r.namespace }}</td>
        <td>{{ r.chart }}</td>
        <td>
          <span v-if="r.managedByKweblens" class="chip">kweblens</span>
          <span v-else class="chip subtle">external</span>
        </td>
        <td>{{ r.revision }}</td>
        <td>
          {{ r.chartVersion }}
          <span
            v-if="r.updateAvailable"
            class="chip update-chip"
            :title="`Latest ${r.latestVersion} in ${r.latestRepository}`"
          >
            ↑ {{ r.latestVersion }}
          </span>
        </td>
        <td>{{ r.appVersion }}</td>
        <td><StatusBadge :text="r.status" /></td>
        <td>{{ r.updated ? age(r.updated) : '—' }}</td>
        <td class="row-actions"><KebabMenu :items="items(r)" /></td>
      </tr>
    </tbody>
  </table>
</template>

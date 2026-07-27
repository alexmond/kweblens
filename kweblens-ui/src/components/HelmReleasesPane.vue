<script setup lang="ts">
import { NDataTable, NTag } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { computed, h, ref, watch } from 'vue';

import { api } from '../api';
import { age } from '../columns';
import { useDialog } from '../dialog';
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

const str = (k: keyof HelmRelease) => (a: HelmRelease, b: HelmRelease) =>
  String(a[k] ?? '').localeCompare(String(b[k] ?? ''), undefined, { numeric: true });

const renderVersion = (r: HelmRelease) =>
  h('span', { title: r.updateAvailable ? `Latest ${r.latestVersion} in ${r.latestRepository}` : undefined }, [
    r.chartVersion,
    r.updateAvailable
      ? h(NTag, { size: 'small', type: 'warning', style: 'margin-left: 6px' }, () => `↑ ${r.latestVersion}`)
      : null,
  ]);

const columns = computed<DataTableColumns<HelmRelease>>(() => [
  { title: 'Name', key: 'name', sorter: str('name'), defaultSortOrder: 'ascend', render: (r) => r.name },
  { title: 'Namespace', key: 'namespace', sorter: str('namespace'), render: (r) => r.namespace },
  { title: 'Chart', key: 'chart', sorter: str('chart'), render: (r) => r.chart },
  {
    title: 'Source',
    key: 'managedByKweblens',
    sorter: str('managedByKweblens'),
    render: (r) =>
      r.managedByKweblens
        ? h(NTag, { size: 'small', type: 'info' }, () => 'kweblens')
        : h(NTag, { size: 'small', bordered: false }, () => 'external'),
  },
  { title: 'Revision', key: 'revision', sorter: (a, b) => a.revision - b.revision, render: (r) => r.revision },
  { title: 'Version', key: 'chartVersion', sorter: str('chartVersion'), render: renderVersion },
  { title: 'App Version', key: 'appVersion', sorter: str('appVersion'), render: (r) => r.appVersion },
  { title: 'Status', key: 'status', sorter: str('status'), render: (r) => h(StatusBadge, { text: r.status }) },
  {
    title: 'Updated',
    key: 'updated',
    sorter: (a, b) => (Date.parse(a.updated ?? '') || 0) - (Date.parse(b.updated ?? '') || 0),
    render: (r) => (r.updated ? age(r.updated) : '—'),
  },
  { title: '', key: '_menu', width: 44, render: (r) => h(KebabMenu, { items: items(r) }) },
]);

const rowKey = (r: HelmRelease) => `${r.namespace}/${r.name}`;
</script>

<template>
  <div class="content-head">
    <span class="count">{{ releases ? `${releases.length} releases` : '' }}</span>
  </div>
  <div v-if="error" class="error">{{ error }}</div>
  <NDataTable :columns="columns" :data="releases ?? []" :row-key="rowKey" :loading="releases === null" size="small">
    <template #empty>
      {{ releases === null ? 'Loading…' : 'No releases.' }}
    </template>
  </NDataTable>
</template>

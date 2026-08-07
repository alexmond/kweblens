<script setup lang="ts">
import { NButton, NDataTable, NModal } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { computed, h } from 'vue';

import { api } from '../api';
import { useAsyncData } from '../composables/useAsyncData';
import { age } from '../columns';
import ErrorNotice from './ErrorNotice.vue';
import StatusBadge from './StatusBadge.vue';
import type { HelmRelease } from '../types';

// Revision history of a release (helm history), newest revision first, read-only.
//
// Events emitted:
//   (e: 'close'): void
const props = defineProps<{ cluster: string; namespace: string; name: string }>();
const emit = defineEmits<{ (e: 'close'): void }>();

const {
  data: history,
  loading,
  error,
  reload,
} = useAsyncData(
  () => [props.cluster, props.namespace, props.name],
  () => api.helmHistory(props.cluster, props.namespace, props.name),
);

const rows = computed(() => [...(history.value ?? [])].sort((a, b) => b.revision - a.revision));

const columns: DataTableColumns<HelmRelease> = [
  { title: 'Revision', key: 'revision', render: (r) => r.revision },
  { title: 'Chart', key: 'chart', render: (r) => r.chart },
  { title: 'Version', key: 'chartVersion', render: (r) => r.chartVersion },
  { title: 'App Version', key: 'appVersion', render: (r) => r.appVersion },
  { title: 'Status', key: 'status', render: (r) => h(StatusBadge, { text: r.status ?? '—' }) },
  { title: 'Updated', key: 'updated', render: (r) => (r.updated ? age(r.updated) : '—') },
];

const rowKey = (r: HelmRelease) => r.revision;
</script>

<template>
  <NModal
    :show="true"
    preset="card"
    title="History"
    style="width: 720px; max-width: 92vw"
    @update:show="(v) => !v && emit('close')"
  >
    <p class="modal-note">
      Revision history of release <strong>{{ name }}</strong> in <strong>{{ namespace }}</strong> (helm history).
    </p>
    <ErrorNotice v-if="error" :message="error" :retrying="loading" @retry="reload" />
    <NDataTable :columns="columns" :data="rows" :row-key="rowKey" :loading="loading" size="small">
      <template #empty>
        {{ history === null ? 'Loading…' : 'No history for this release.' }}
      </template>
    </NDataTable>
    <template #footer>
      <div class="modal-actions">
        <NButton @click="emit('close')">Close</NButton>
      </div>
    </template>
  </NModal>
</template>

<script setup lang="ts">
import { NButton, NDataTable, NModal } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { computed, h } from 'vue';

import { api } from '../api';
import { useAsyncData } from '../composables/useAsyncData';
import type { HelmResourceRef } from '../types';

// Objects a release manages (from its rendered manifest). Click a name to open it in the shell.
//
// Events emitted:
//   (e: 'close'): void
//   (e: 'open', kind: string, namespace: string): void
const props = defineProps<{ cluster: string; namespace: string; name: string }>();
const emit = defineEmits<{ (e: 'close'): void; (e: 'open', kind: string, namespace: string): void }>();

const {
  data: resources,
  loading,
  error,
  reload,
} = useAsyncData(
  () => [props.cluster, props.namespace, props.name],
  () => api.helmReleaseResources(props.cluster, props.namespace, props.name),
);

const cmp = (k: keyof HelmResourceRef) => (a: HelmResourceRef, b: HelmResourceRef) =>
  String(a[k] ?? '').localeCompare(String(b[k] ?? ''), undefined, { numeric: true });

const columns = computed<DataTableColumns<HelmResourceRef>>(() => [
  { title: 'Kind', key: 'kind', sorter: cmp('kind'), defaultSortOrder: 'ascend', render: (r) => r.kind },
  { title: 'Namespace', key: 'namespace', sorter: cmp('namespace'), render: (r) => r.namespace },
  {
    title: 'Name',
    key: 'name',
    sorter: cmp('name'),
    render: (r) => h('a', { class: 'cell-link', onClick: () => emit('open', r.kind, r.namespace) }, r.name),
  },
]);

const rowKey = (r: HelmResourceRef) => `${r.kind}/${r.namespace}/${r.name}`;
</script>

<template>
  <NModal
    :show="true"
    preset="card"
    title="Resources"
    style="width: 720px; max-width: 92vw"
    @update:show="(v) => !v && emit('close')"
  >
    <p class="modal-note">
      Objects managed by release <strong>{{ name }}</strong> in <strong>{{ namespace }}</strong> (from its manifest).
      Click a name to open it.
    </p>
    <ErrorNotice v-if="error" :message="error" :retrying="loading" @retry="reload" />
    <NDataTable :columns="columns" :data="resources ?? []" :row-key="rowKey" :loading="loading" size="small">
      <template #empty>
        {{ resources === null ? 'Loading…' : "No resources in this release's manifest." }}
      </template>
    </NDataTable>
    <template #footer>
      <div class="modal-actions">
        <NButton @click="emit('close')">Close</NButton>
      </div>
    </template>
  </NModal>
</template>

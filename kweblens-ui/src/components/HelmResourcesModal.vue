<script setup lang="ts">
import { NButton, NDataTable, NModal } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { shallowRef, computed, h, ref, watch } from 'vue';

import { api } from '../api';
import type { HelmResourceRef } from '../types';

// Objects a release manages (from its rendered manifest). Click a name to open it in the shell.
//
// Events emitted:
//   (e: 'close'): void
//   (e: 'open', kind: string, namespace: string): void
const props = defineProps<{ cluster: string; namespace: string; name: string }>();
const emit = defineEmits<{ (e: 'close'): void; (e: 'open', kind: string, namespace: string): void }>();

const resources = shallowRef<HelmResourceRef[] | null>(null);
const error = ref<string | null>(null);

watch(
  () => [props.cluster, props.namespace, props.name],
  (_v, _o, onCleanup) => {
    let cancelled = false;
    onCleanup(() => (cancelled = true));
    api
      .helmReleaseResources(props.cluster, props.namespace, props.name)
      .then((r) => !cancelled && (resources.value = r))
      .catch((e) => !cancelled && (error.value = String(e)));
  },
  { immediate: true },
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
    <div v-if="error" class="error">{{ error }}</div>
    <NDataTable :columns="columns" :data="resources ?? []" :row-key="rowKey" :loading="resources === null" size="small">
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

<script setup lang="ts">
import { ref, watch } from 'vue';

import { api } from '../api';
import { useEscapeKey } from '../composables/useEscapeKey';
import { useTableSort } from '../composables/useTableSort';
import SortTh from './SortTh.vue';
import type { HelmResourceRef } from '../types';

// Objects a release manages (from its rendered manifest). Click a name to open it in the shell.
//
// Events emitted:
//   (e: 'close'): void
//   (e: 'open', kind: string, namespace: string): void
const props = defineProps<{ cluster: string; namespace: string; name: string }>();
const emit = defineEmits<{ (e: 'close'): void; (e: 'open', kind: string, namespace: string): void }>();

const resources = ref<HelmResourceRef[] | null>(null);
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

useEscapeKey(() => emit('close'));

const { sorted, sort, clickHeader } = useTableSort(
  () => resources.value ?? [],
  'kind',
  (r, k) => (r[k as keyof HelmResourceRef] as string) ?? '',
);
</script>

<template>
  <div class="modal-backdrop" @click="emit('close')">
    <div class="modal wide" @click.stop>
      <h2>Resources</h2>
      <p class="modal-note">
        Objects managed by release <strong>{{ name }}</strong> in <strong>{{ namespace }}</strong> (from its manifest).
        Click a name to open it.
      </p>
      <div v-if="error" class="error">{{ error }}</div>
      <div v-if="resources === null" class="empty">Loading…</div>
      <div v-else-if="resources.length === 0" class="empty">No resources in this release's manifest.</div>
      <table v-else class="grid">
        <thead>
          <tr>
            <SortTh label="Kind" col-key="kind" :sort="sort" @sort="clickHeader" />
            <SortTh label="Namespace" col-key="namespace" :sort="sort" @sort="clickHeader" />
            <SortTh label="Name" col-key="name" :sort="sort" @sort="clickHeader" />
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in sorted" :key="r.kind + '/' + r.namespace + '/' + r.name">
            <td>{{ r.kind }}</td>
            <td>{{ r.namespace }}</td>
            <td class="name">
              <button class="cell-link" @click="emit('open', r.kind, r.namespace)">{{ r.name }}</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div class="modal-actions">
        <button type="button" class="btn" @click="emit('close')">Close</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';

import { api } from '../api';
import { age } from '../columns';
import { useEscapeKey } from '../composables/useEscapeKey';
import StatusBadge from './StatusBadge.vue';
import type { HelmRelease } from '../types';

// Revision history of a release (helm history), newest revision first, read-only.
//
// Events emitted:
//   (e: 'close'): void
const props = defineProps<{ cluster: string; namespace: string; name: string }>();
const emit = defineEmits<{ (e: 'close'): void }>();

const history = ref<HelmRelease[] | null>(null);
const error = ref<string | null>(null);

watch(
  () => [props.cluster, props.namespace, props.name],
  (_v, _o, onCleanup) => {
    let cancelled = false;
    onCleanup(() => (cancelled = true));
    api
      .helmHistory(props.cluster, props.namespace, props.name)
      .then((h) => !cancelled && (history.value = h))
      .catch((e) => !cancelled && (error.value = String(e)));
  },
  { immediate: true },
);

useEscapeKey(() => emit('close'));

const rows = computed(() => [...(history.value ?? [])].sort((a, b) => b.revision - a.revision));
</script>

<template>
  <div class="modal-backdrop" @click="emit('close')">
    <div class="modal wide" @click.stop>
      <h2>History</h2>
      <p class="modal-note">
        Revision history of release <strong>{{ name }}</strong> in <strong>{{ namespace }}</strong> (helm history).
      </p>
      <div v-if="error" class="error">{{ error }}</div>
      <div v-if="history === null" class="empty">Loading…</div>
      <div v-else-if="rows.length === 0" class="empty">No history for this release.</div>
      <table v-else class="grid">
        <thead>
          <tr>
            <th>Revision</th>
            <th>Chart</th>
            <th>Version</th>
            <th>App Version</th>
            <th>Status</th>
            <th>Updated</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.revision">
            <td>{{ r.revision }}</td>
            <td>{{ r.chart }}</td>
            <td>{{ r.chartVersion }}</td>
            <td>{{ r.appVersion }}</td>
            <td><StatusBadge :text="r.status" /></td>
            <td>{{ r.updated ? age(r.updated) : '—' }}</td>
          </tr>
        </tbody>
      </table>
      <div class="modal-actions">
        <button type="button" class="btn" @click="emit('close')">Close</button>
      </div>
    </div>
  </div>
</template>

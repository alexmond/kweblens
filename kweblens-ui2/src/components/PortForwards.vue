<script setup lang="ts">
// The port-forwards table: lists active forwards (polled every 3s so Active/Closed/Failed
// stays current), lets an authed user Stop one, and shows the loopback local port.
//
// Emits:
//   require-auth ()   — a Stop was attempted without being signed in; shell prompts login
import { ref, watch } from 'vue';

import { api } from '../api';
import { useTableSort } from '../composables/useTableSort';
import type { PortForward } from '../types';
import SortTh from './SortTh.vue';

const props = defineProps<{ cluster: string; authed: boolean }>();
const emit = defineEmits<{ (e: 'require-auth'): void }>();

const forwards = ref<PortForward[] | null>(null);
const error = ref<string | null>(null);
const busy = ref<string | null>(null);

const refresh = () =>
  api
    .portForwards(props.cluster)
    .then((f) => (forwards.value = f))
    .catch((e) => (error.value = String(e)));

// Poll so status (Active/Closed/Failed) stays current as connections come and go.
watch(
  () => props.cluster,
  (cluster, _old, onCleanup) => {
    let cancelled = false;
    forwards.value = null;
    error.value = null;
    const tick = () => {
      if (cancelled) {
        return;
      }
      api
        .portForwards(cluster)
        .then((f) => !cancelled && (forwards.value = f))
        .catch((e) => !cancelled && (error.value = String(e)));
    };
    tick();
    const timer = window.setInterval(tick, 3000);
    onCleanup(() => {
      cancelled = true;
      window.clearInterval(timer);
    });
  },
  { immediate: true },
);

const stop = (id: string) => {
  if (!props.authed) {
    emit('require-auth');
    return;
  }
  busy.value = id;
  api
    .stopPortForward(props.cluster, id)
    .then(() => refresh())
    .catch((e) => (error.value = String(e)))
    .finally(() => (busy.value = null));
};

const { sorted, sort, clickHeader } = useTableSort(
  () => forwards.value ?? [],
  'name',
  (f, k) => {
    if (k === 'remotePort') {
      return f.remotePort;
    }
    if (k === 'localPort') {
      return f.localPort;
    }
    return (f[k as keyof PortForward] as string) ?? '';
  },
);
</script>

<template>
  <div class="overview">
    <div class="content-head">
      <h1>Port Forwards</h1>
      <span class="count">{{ forwards ? `${forwards.length} items` : '' }}</span>
    </div>
    <p class="modal-note">
      Forwards bind on the kweblens host. Reach a forward at <code>host:localPort</code> (loopback unless configured
      otherwise). Start one from a Pod or Service detail.
    </p>
    <div v-if="error" class="error">{{ error }}</div>
    <div v-if="forwards === null" class="empty">Loading…</div>
    <div v-else-if="forwards.length === 0" class="empty">No active forwards.</div>
    <table v-else class="grid">
      <thead>
        <tr>
          <SortTh label="Name" col-key="name" :sort="sort" @sort="clickHeader" />
          <SortTh label="Namespace" col-key="namespace" :sort="sort" @sort="clickHeader" />
          <SortTh label="Kind" col-key="kind" :sort="sort" @sort="clickHeader" />
          <SortTh label="Pod Port" col-key="remotePort" :sort="sort" @sort="clickHeader" />
          <SortTh label="Local Port" col-key="localPort" :sort="sort" @sort="clickHeader" />
          <SortTh label="Protocol" col-key="protocol" :sort="sort" @sort="clickHeader" />
          <SortTh label="Status" col-key="status" :sort="sort" @sort="clickHeader" />
          <th />
        </tr>
      </thead>
      <tbody>
        <tr v-for="f in sorted" :key="f.id">
          <td class="name">{{ f.name }}</td>
          <td>{{ f.namespace }}</td>
          <td>{{ f.kind }}</td>
          <td>{{ f.remotePort }}</td>
          <td :title="`${f.address}:${f.localPort}`">{{ f.localPort }}</td>
          <td>{{ f.protocol }}</td>
          <td>
            <span :class="'pf-status pf-' + f.status.toLowerCase()">{{ f.status }}</span>
          </td>
          <td>
            <button class="btn" :disabled="busy === f.id" @click="stop(f.id)">Stop</button>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

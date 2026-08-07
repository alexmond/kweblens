<script setup lang="ts">
// The port-forwards table (Naive NDataTable): lists active forwards (polled every 3s so
// Active/Closed/Failed stays current), lets an authed user Stop one, shows the loopback local port.
// Emits: require-auth () — a Stop was attempted without being signed in; shell prompts login
import { NButton, NDataTable, NTag } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { shallowRef, computed, h, ref, watch } from 'vue';

import { api } from '../api';
import { failureNotice } from '../apiFailure';
import type { PortForward } from '../types';

const props = defineProps<{ cluster: string; authed: boolean }>();
const emit = defineEmits<{ (e: 'require-auth'): void }>();

const forwards = shallowRef<PortForward[] | null>(null);
const error = ref<string | null>(null);
const busy = ref<string | null>(null);

const refresh = () =>
  api
    .portForwards(props.cluster)
    .then((f) => (forwards.value = f))
    .catch((e) => (error.value = failureNotice(e)));

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
        .catch((e) => !cancelled && (error.value = failureNotice(e)));
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
    .catch((e) => (error.value = failureNotice(e)))
    .finally(() => (busy.value = null));
};

const STATUS_TYPE: Record<string, 'success' | 'error' | 'warning' | 'default'> = {
  active: 'success',
  closed: 'default',
  failed: 'error',
};
const columns = computed<DataTableColumns<PortForward>>(() => [
  { title: 'Name', key: 'name', sorter: 'default' },
  { title: 'Namespace', key: 'namespace', sorter: 'default' },
  { title: 'Kind', key: 'kind', sorter: 'default' },
  {
    // A Service port is not what the container listens on, so show the translation
    // (podinfo forwards 80 -> 9898). Same number on both sides means no translation.
    title: 'Port',
    key: 'remotePort',
    sorter: (a, b) => a.remotePort - b.remotePort,
    render: (f) => (f.podPort && f.podPort !== f.remotePort ? `${f.remotePort} → ${f.podPort}` : String(f.remotePort)),
  },
  { title: 'Pod', key: 'podName', sorter: 'default', render: (f) => f.podName || '—' },
  { title: 'Local Port', key: 'localPort', sorter: (a, b) => a.localPort - b.localPort },
  { title: 'Protocol', key: 'protocol', sorter: 'default' },
  {
    title: 'Status',
    key: 'status',
    sorter: 'default',
    render: (f) =>
      h(
        NTag,
        { size: 'small', type: STATUS_TYPE[f.status.toLowerCase()] ?? 'default', bordered: false },
        () => f.status,
      ),
  },
  {
    title: '',
    key: '_stop',
    render: (f) =>
      h(NButton, { size: 'small', disabled: busy.value === f.id, onClick: () => stop(f.id) }, () => 'Stop'),
  },
]);
</script>

<template>
  <div class="overview">
    <div class="content-head">
      <h1>Port Forwards</h1>
      <span class="count">{{ forwards ? `${forwards.length} items` : '' }}</span>
    </div>
    <p class="modal-note">
      Forwards bind on the kweblens host. Reach a forward at <code>host:localPort</code> (loopback unless configured
      otherwise). Start one from a Pod or Service detail. A Service port is translated to the pod's
      <code>targetPort</code>; the Port column shows that translation (80 → 9898) when the two differ.
    </p>
    <div v-if="error" class="error">{{ error }}</div>
    <NDataTable
      :columns="columns"
      :data="forwards ?? []"
      :loading="forwards === null && !error"
      :row-key="(f) => f.id"
      size="small"
    />
  </div>
</template>

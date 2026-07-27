<script setup lang="ts">
// Start-port-forward modal: pick a remote (pod) port — a select when known ports are
// supplied, otherwise a free number input — plus an optional local port (blank = auto).
//
// Emits:
//   close ()          — modal dismissed (backdrop / Cancel / Escape)
//   started ()        — a forward was started successfully
//   auth-expired ()   — start returned 401; shell should force re-auth
import { ref } from 'vue';

import { ApiError, api } from '../api';
import { useEscapeKey } from '../composables/useEscapeKey';

const props = defineProps<{
  cluster: string;
  kind: string;
  namespace: string;
  name: string;
  ports: number[];
}>();
const emit = defineEmits<{ (e: 'close'): void; (e: 'started'): void; (e: 'auth-expired'): void }>();

const remotePort = ref(props.ports[0] ? String(props.ports[0]) : '');
const localPort = ref('');
const busy = ref(false);
const error = ref<string | null>(null);

useEscapeKey(() => emit('close'));

const submit = () => {
  const remote = Number.parseInt(remotePort.value, 10);
  if (!Number.isFinite(remote) || remote <= 0) {
    error.value = 'Enter a valid pod port.';
    return;
  }
  const local = localPort.value.trim() ? Number.parseInt(localPort.value, 10) : undefined;
  busy.value = true;
  error.value = null;
  api
    .startPortForward(props.cluster, {
      kind: props.kind,
      namespace: props.namespace,
      name: props.name,
      remotePort: remote,
      localPort: local,
    })
    .then(() => emit('started'))
    .catch((err) => {
      if (err instanceof ApiError && err.status === 401) {
        emit('auth-expired');
      }
      error.value = String(err);
      busy.value = false;
    });
};
</script>

<template>
  <div class="modal-backdrop" @click="emit('close')">
    <form class="modal" @click.stop @submit.prevent="submit">
      <h2>Forward {{ kind }}</h2>
      <p class="modal-note">
        {{ namespace }}/{{ name }} — binds a local port on the kweblens host to a port on this {{ kind.toLowerCase() }}.
      </p>
      <div v-if="error" class="error">{{ error }}</div>
      <label>
        <span>Pod port</span>
        <select v-if="ports.length > 0" v-model="remotePort">
          <option v-for="p in ports" :key="p" :value="String(p)">{{ p }}</option>
        </select>
        <input v-else v-model="remotePort" type="number" :min="1" autofocus />
      </label>
      <label>
        <span>Local port (blank = auto)</span>
        <input v-model="localPort" type="number" :min="0" />
      </label>
      <div class="modal-actions">
        <button type="button" class="btn" :disabled="busy" @click="emit('close')">Cancel</button>
        <button type="submit" class="btn primary" :disabled="busy">{{ busy ? 'Starting…' : 'Start forward' }}</button>
      </div>
    </form>
  </div>
</template>

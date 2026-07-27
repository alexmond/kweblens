<script setup lang="ts">
// Start-port-forward modal (Naive NModal): pick a remote (pod) port — an NSelect when known
// ports are supplied, else a free number input — plus an optional local port (blank = auto).
// Emits: close (), started (), auth-expired ()
import { NButton, NForm, NFormItem, NInputNumber, NModal, NSelect } from 'naive-ui';
import { computed, ref } from 'vue';

import { ApiError, api } from '../api';

const props = defineProps<{
  cluster: string;
  kind: string;
  namespace: string;
  name: string;
  ports: number[];
}>();
const emit = defineEmits<{ (e: 'close'): void; (e: 'started'): void; (e: 'auth-expired'): void }>();

const remotePort = ref<number | null>(props.ports[0] ?? null);
const localPort = ref<number | null>(null);
const busy = ref(false);
const error = ref<string | null>(null);

const portOptions = computed(() => props.ports.map((p) => ({ label: String(p), value: p })));

const submit = () => {
  const remote = remotePort.value ?? 0;
  if (!Number.isFinite(remote) || remote <= 0) {
    error.value = 'Enter a valid pod port.';
    return;
  }
  busy.value = true;
  error.value = null;
  api
    .startPortForward(props.cluster, {
      kind: props.kind,
      namespace: props.namespace,
      name: props.name,
      remotePort: remote,
      localPort: localPort.value ?? undefined,
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
const onShow = (v: boolean) => {
  if (!v) {
    emit('close');
  }
};
</script>

<template>
  <NModal
    :show="true"
    preset="card"
    :title="`Forward ${kind}`"
    :bordered="false"
    style="max-width: 440px"
    @update:show="onShow"
  >
    <p class="modal-note">
      {{ namespace }}/{{ name }} — binds a local port on the kweblens host to a port on this {{ kind.toLowerCase() }}.
    </p>
    <div v-if="error" class="error">{{ error }}</div>
    <NForm @submit.prevent="submit">
      <NFormItem label="Pod port">
        <NSelect v-if="ports.length > 0" v-model:value="remotePort" :options="portOptions" />
        <NInputNumber v-else v-model:value="remotePort" :min="1" style="width: 100%" />
      </NFormItem>
      <NFormItem label="Local port (blank = auto)">
        <NInputNumber v-model:value="localPort" :min="0" style="width: 100%" />
      </NFormItem>
    </NForm>
    <template #footer>
      <div class="dialog-actions">
        <NButton :disabled="busy" @click="emit('close')">Cancel</NButton>
        <NButton type="primary" :loading="busy" @click="submit">Start forward</NButton>
      </div>
    </template>
  </NModal>
</template>

<style scoped>
.dialog-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
</style>

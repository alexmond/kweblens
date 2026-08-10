<script setup lang="ts">
// Start-port-forward modal (Naive NModal): pick a remote port — an NSelect when known
// ports are supplied, else a free number input — plus an optional local port (blank = auto).
// For a Service the number is the SERVICE port; the server resolves it to the pod's
// targetPort (named or numeric), so the label must not call it a pod port.
// Emits: close (), started (), auth-expired ()
import { NButton, NForm, NFormItem, NInputNumber, NModal, NSelect } from 'naive-ui';
import { computed, ref } from 'vue';

import { api } from '../api';
import { isSessionExpiry } from '../apiFailure';
import type { ActionFailure } from '../paneFailure';
import { actionFailed, actionReport } from '../paneFailure';
import ActionNotice from './ActionNotice.vue';

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
// Starting a forward BINDS A SOCKET on the kweblens host, so it is an action and never gets a
// Retry: a button that re-posts it would try to bind again, and a start that timed out may
// already hold the port. The modal's own "Start forward" is the re-do, once the reader has
// read why the first one did not.
const failure = ref<ActionFailure | null>(null);

const portOptions = computed(() => props.ports.map((p) => ({ label: String(p), value: p })));
const isService = computed(() => props.kind.toLowerCase() === 'service');
const portLabel = computed(() => (isService.value ? 'Service port' : 'Pod port'));

const submit = () => {
  const remote = remotePort.value ?? 0;
  if (!Number.isFinite(remote) || remote <= 0) {
    // Rejected here, so nothing was attempted and there is nothing to say about the cluster.
    failure.value = actionReport('Start forward failed', `Enter a valid ${portLabel.value.toLowerCase()}.`);
    return;
  }
  busy.value = true;
  failure.value = null;
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
      if (isSessionExpiry(err)) {
        emit('auth-expired');
      }
      failure.value = actionFailed('Start forward failed', err);
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
      <template v-if="isService">
        The service port is resolved to the pod's <code>targetPort</code>, so it may land on a different number.
      </template>
    </p>
    <ActionNotice v-if="failure" :failure="failure" />
    <NForm @submit.prevent="submit">
      <NFormItem :label="portLabel">
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

<script setup lang="ts">
// Create-from-YAML modal (Naive NModal): a manifest textarea applied via server-side apply.
// Emits: close () — dismissed or apply succeeded; auth-expired () — kweblens itself refused
// the login (a 401, or a bodyless 403). A CODED 403 is the cluster's own RBAC verdict and
// stays in the modal with its reason, because signing in again would not change it.
import { NButton, NInput, NModal } from 'naive-ui';
import { ref } from 'vue';

import { api } from '../api';
import { failureNotice, isSessionExpiry } from '../apiFailure';

const props = defineProps<{ cluster: string }>();
const emit = defineEmits<{ (e: 'close'): void; (e: 'auth-expired'): void }>();

const draft = ref(
  'apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: example\n  namespace: default\ndata:\n  key: value\n',
);
const busy = ref(false);
const msg = ref<string | null>(null);
const err = ref(false);

const apply = async () => {
  busy.value = true;
  msg.value = null;
  err.value = false;
  try {
    const r = await api.apply(props.cluster, draft.value);
    msg.value = `created ${r.kind}/${r.name}`;
    window.setTimeout(() => emit('close'), 700);
  } catch (e) {
    err.value = true;
    // The message is set in EVERY branch, including the auth one. This used to emit
    // `auth-expired` and set nothing, and the shell closes the modal on that event — so a
    // cluster refusal destroyed a hand-typed manifest and explained nothing.
    msg.value = failureNotice(e);
    if (isSessionExpiry(e)) {
      emit('auth-expired');
    }
  } finally {
    busy.value = false;
  }
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
    title="Create from YAML"
    :bordered="false"
    style="max-width: 720px"
    @update:show="onShow"
  >
    <p class="modal-note">Server-side apply — paste or edit a manifest, then Apply.</p>
    <NInput
      v-model:value="draft"
      type="textarea"
      :autosize="{ minRows: 12, maxRows: 24 }"
      :input-props="{ spellcheck: 'false' }"
      class="yaml-mono"
    />
    <div v-if="msg" :class="'act-msg' + (err ? ' err' : '')">{{ msg }}</div>
    <template #footer>
      <div class="dialog-actions">
        <NButton :disabled="busy" @click="emit('close')">Cancel</NButton>
        <NButton type="primary" :loading="busy" @click="apply">Apply</NButton>
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
.yaml-mono :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
</style>

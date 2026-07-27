<script setup lang="ts">
// Create-from-YAML modal (Naive NModal): a manifest textarea applied via server-side apply.
// Emits: close () — dismissed or apply succeeded; auth-expired () — apply returned 401/403
import { NButton, NInput, NModal } from 'naive-ui';
import { ref } from 'vue';

import { ApiError, api } from '../api';

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
    if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
      emit('auth-expired');
    } else {
      msg.value = String(e);
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

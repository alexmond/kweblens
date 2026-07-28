<script setup lang="ts">
// The pop-out YAML editor window. The drawer's YAML tab shows the manifest read-only
// (embedded); clicking Edit opens this large NModal with the CodeMirror editor so editing
// gets a full-size surface instead of the narrow drawer.
//
// Emits:
//   applied (text)   — server-side apply succeeded; the parent refreshes its embedded view
//   auth-expired ()  — apply returned 401/403; the shell must re-prompt for creds
//   close ()         — dismissed, or apply succeeded (after a brief confirmation)
import { NButton, NModal } from 'naive-ui';
import { ref } from 'vue';

import { ApiError, api } from '../api';
import YamlEditor from './YamlEditor.vue';

const props = defineProps<{ cluster: string; title: string; initialText: string }>();
const emit = defineEmits<{
  (e: 'applied', text: string): void;
  (e: 'auth-expired'): void;
  (e: 'close'): void;
}>();

const draft = ref(props.initialText);
const busy = ref(false);
const msg = ref<string | null>(null);
const err = ref(false);

const apply = async () => {
  busy.value = true;
  msg.value = null;
  err.value = false;
  try {
    const r = await api.apply(props.cluster, draft.value);
    emit('applied', draft.value);
    msg.value = `applied ${r.kind}/${r.name}`;
    window.setTimeout(() => emit('close'), 600);
  } catch (e) {
    err.value = true;
    if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
      emit('auth-expired');
      msg.value = 'Authentication failed — sign in again.';
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
    :title="title"
    :bordered="false"
    class="yaml-editor-modal"
    style="width: min(1100px, 92vw)"
    @update:show="onShow"
  >
    <YamlEditor v-model:value="draft" class="yaml-edit-cm" />
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
</style>

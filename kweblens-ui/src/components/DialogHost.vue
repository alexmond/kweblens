<script setup lang="ts">
import { NButton, NInput } from 'naive-ui';
import { NModal } from 'naive-ui';
import { computed, nextTick, ref, watch } from 'vue';

import { closeDialog, dialogState } from '../dialog';
import type { ConfirmOpts, PromptOpts } from '../dialog';

// Promise-based confirm/prompt (dialog.ts) rendered with Naive NModal so it themes with the app.
const value = ref('');
const inputRef = ref<{ focus: () => void; select: () => void } | null>(null);

const isPrompt = computed(() => dialogState.value?.kind === 'prompt');
const opts = computed(() => dialogState.value?.opts ?? null);
const promptOpts = computed(() => (isPrompt.value ? (opts.value as PromptOpts) : null));
const danger = computed(() => !isPrompt.value && (opts.value as ConfirmOpts | null)?.danger);
const confirmLabel = computed(() => opts.value?.confirmLabel ?? (isPrompt.value ? 'OK' : 'Confirm'));

watch(dialogState, (s) => {
  if (s?.kind === 'prompt') {
    value.value = s.opts.initial ?? '';
    void nextTick(() => {
      inputRef.value?.focus();
      inputRef.value?.select();
    });
  }
});

const cancel = () => {
  const s = dialogState.value;
  if (s?.kind === 'prompt') {
    s.resolve(null);
  } else if (s?.kind === 'confirm') {
    s.resolve(false);
  }
  closeDialog();
};
const accept = () => {
  const s = dialogState.value;
  if (s?.kind === 'prompt') {
    s.resolve(value.value);
  } else if (s?.kind === 'confirm') {
    s.resolve(true);
  }
  closeDialog();
};
const onShow = (v: boolean) => {
  if (!v) {
    cancel();
  }
};
</script>

<template>
  <NModal
    :show="!!dialogState"
    preset="card"
    :title="opts?.title ?? (isPrompt ? 'Input' : 'Confirm')"
    :style="{ maxWidth: '440px' }"
    :bordered="false"
    @update:show="onShow"
  >
    <p class="dialog-message">{{ opts?.message }}</p>
    <NInput
      v-if="promptOpts"
      ref="inputRef"
      v-model:value="value"
      :placeholder="promptOpts.placeholder"
      @keyup.enter="accept"
    />
    <template #footer>
      <div class="dialog-actions">
        <NButton @click="cancel">Cancel</NButton>
        <NButton :type="danger ? 'error' : 'primary'" @click="accept">{{ confirmLabel }}</NButton>
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

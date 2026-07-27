<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';

import { closeDialog, dialogState } from '../dialog';
import type { ConfirmOpts, PromptOpts } from '../dialog';
import { useEscapeKey } from '../composables/useEscapeKey';

const value = ref('');
const inputRef = ref<HTMLInputElement | null>(null);

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

useEscapeKey(cancel);
</script>

<template>
  <Teleport to="body">
    <div v-if="dialogState" class="modal-backdrop" @click="cancel">
      <form class="modal dialog" @click.stop @submit.prevent="accept">
        <h2 v-if="opts?.title">{{ opts.title }}</h2>
        <p class="dialog-message">{{ opts?.message }}</p>
        <label v-if="promptOpts" class="dialog-field">
          <span v-if="promptOpts.label">{{ promptOpts.label }}</span>
          <input
            ref="inputRef"
            v-model="value"
            :type="promptOpts.type ?? 'text'"
            :placeholder="promptOpts.placeholder"
          />
        </label>
        <div class="modal-actions">
          <button type="button" class="btn" @click="cancel">Cancel</button>
          <button type="submit" :class="'btn ' + (danger ? 'danger' : 'primary')">{{ confirmLabel }}</button>
        </div>
      </form>
    </div>
  </Teleport>
</template>

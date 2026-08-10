<script setup lang="ts">
// HTTP Basic sign-in modal (Naive NModal). Credentials kept in memory for the tab only.
// Callback prop (emits can't return a promise): onSubmit(user,pass) => Promise<boolean>
// Emits: cancel () — user dismissed the modal
import { NButton, NForm, NFormItem, NInput, NModal } from 'naive-ui';
import { computed, ref } from 'vue';

import { signInRejected } from '../paneFailure';
import ActionNotice from './ActionNotice.vue';

const props = defineProps<{ onSubmit: (user: string, pass: string) => Promise<boolean> }>();
const emit = defineEmits<{ (e: 'cancel'): void }>();

const user = ref('admin');
const pass = ref('');
const busy = ref(false);
const failed = ref(false);

const submit = () => {
  busy.value = true;
  failed.value = false;
  props.onSubmit(user.value, pass.value).then((ok) => {
    busy.value = false;
    if (!ok) {
      failed.value = true;
    }
  });
};
// A rejected sign-in is an ACTION result, not a failed fetch: the re-try is the Sign in
// button two lines below, which the operator presses after correcting the password. An
// automatic Retry would re-post the same credentials that were just refused.
const failure = computed(() => (failed.value ? signInRejected() : null));
const onShow = (v: boolean) => {
  if (!v) {
    emit('cancel');
  }
};
</script>

<template>
  <NModal :show="true" preset="card" title="Sign in" :bordered="false" style="max-width: 420px" @update:show="onShow">
    <p class="modal-note">Credentials are kept in memory for this tab only and sent over HTTP Basic.</p>
    <ActionNotice v-if="failure" :failure="failure" />
    <NForm @submit.prevent="submit">
      <NFormItem label="Username">
        <NInput v-model:value="user" autofocus @keyup.enter="submit" />
      </NFormItem>
      <NFormItem label="Password">
        <NInput v-model:value="pass" type="password" @keyup.enter="submit" />
      </NFormItem>
    </NForm>
    <template #footer>
      <div class="dialog-actions">
        <NButton :disabled="busy" @click="emit('cancel')">Cancel</NButton>
        <NButton type="primary" :loading="busy" @click="submit">{{ busy ? 'Signing in…' : 'Sign in' }}</NButton>
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

<script setup lang="ts">
// HTTP Basic sign-in modal. Credentials are kept in memory for the tab only.
//
// Callback prop (Vue emits can't return a promise, so this is a prop, not an emit):
//   onSubmit(user: string, pass: string) => Promise<boolean>
//                     — resolves true on success (modal closes via shell), false shows error
// Emits:
//   cancel ()          — user dismissed the modal (backdrop / Cancel / Escape)
import { ref } from 'vue';

import { useEscapeKey } from '../composables/useEscapeKey';

const props = defineProps<{ onSubmit: (user: string, pass: string) => Promise<boolean> }>();
const emit = defineEmits<{ (e: 'cancel'): void }>();

const user = ref('admin');
const pass = ref('');
const busy = ref(false);
const failed = ref(false);

useEscapeKey(() => emit('cancel'));

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
</script>

<template>
  <div class="modal-backdrop" @click="emit('cancel')">
    <form class="modal" @click.stop @submit.prevent="submit">
      <h2>Sign in</h2>
      <p class="modal-note">Credentials are kept in memory for this tab only and sent over HTTP Basic.</p>
      <div v-if="failed" class="error">Invalid credentials.</div>
      <label>
        <span>Username</span>
        <input v-model="user" autofocus />
      </label>
      <label>
        <span>Password</span>
        <input v-model="pass" type="password" />
      </label>
      <div class="modal-actions">
        <button type="button" class="btn" :disabled="busy" @click="emit('cancel')">Cancel</button>
        <button type="submit" class="btn primary" :disabled="busy">{{ busy ? 'Signing in…' : 'Sign in' }}</button>
      </div>
    </form>
  </div>
</template>

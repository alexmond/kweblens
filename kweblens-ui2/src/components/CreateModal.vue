<script setup lang="ts">
// Create-from-YAML modal: a manifest textarea applied via server-side apply. On success it
// briefly shows the created object, then closes; a 401/403 signals an expired session.
//
// Emits:
//   close ()          — modal dismissed (backdrop / Cancel / Escape) or apply succeeded
//   auth-expired ()   — apply returned 401/403; shell should force re-auth
import { ref } from 'vue';

import { ApiError, api } from '../api';
import { useEscapeKey } from '../composables/useEscapeKey';

const props = defineProps<{ cluster: string }>();
const emit = defineEmits<{ (e: 'close'): void; (e: 'auth-expired'): void }>();

const draft = ref(
  'apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: example\n  namespace: default\ndata:\n  key: value\n',
);
const busy = ref(false);
const msg = ref<string | null>(null);
const err = ref(false);

useEscapeKey(() => emit('close'));

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
</script>

<template>
  <div class="modal-backdrop" @click="emit('close')">
    <div class="modal wide" @click.stop>
      <h2>Create from YAML</h2>
      <p class="modal-note">Server-side apply — paste or edit a manifest, then Apply.</p>
      <textarea v-model="draft" class="yaml-edit tall" :spellcheck="false" />
      <div v-if="msg" :class="'act-msg' + (err ? ' err' : '')">{{ msg }}</div>
      <div class="modal-actions">
        <button type="button" class="btn" :disabled="busy" @click="emit('close')">Cancel</button>
        <button type="button" class="btn primary" :disabled="busy" @click="apply">Apply</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';

import { api } from '../api';
import { useEscapeKey } from '../composables/useEscapeKey';
import YamlView from './YamlView.vue';

// A release's stored configuration (helm get values), read-only.
//
// Events emitted:
//   (e: 'close'): void
const props = defineProps<{ cluster: string; namespace: string; name: string }>();
const emit = defineEmits<{ (e: 'close'): void }>();

const values = ref<string | null>(null);
const error = ref<string | null>(null);

watch(
  () => [props.cluster, props.namespace, props.name],
  (_v, _o, onCleanup) => {
    let cancelled = false;
    onCleanup(() => (cancelled = true));
    api
      .helmReleaseValues(props.cluster, props.namespace, props.name)
      .then((y) => !cancelled && (values.value = y))
      .catch((e) => !cancelled && (error.value = String(e)));
  },
  { immediate: true },
);

useEscapeKey(() => emit('close'));
</script>

<template>
  <div class="modal-backdrop" @click="emit('close')">
    <div class="modal wide" @click.stop>
      <h2>Values</h2>
      <p class="modal-note">
        Stored configuration for release <strong>{{ name }}</strong> in <strong>{{ namespace }}</strong> (helm get
        values).
      </p>
      <div v-if="error" class="error">{{ error }}</div>
      <div v-if="values === null" class="empty">Loading…</div>
      <div v-else-if="values.trim() === ''" class="empty">No user-supplied values (chart defaults only).</div>
      <YamlView v-else :text="values" />
      <div class="modal-actions">
        <button type="button" class="btn" @click="emit('close')">Close</button>
      </div>
    </div>
  </div>
</template>

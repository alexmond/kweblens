<script setup lang="ts">
import { NButton, NModal } from 'naive-ui';
import { ref, watch } from 'vue';

import { api } from '../api';
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
</script>

<template>
  <NModal
    :show="true"
    preset="card"
    title="Values"
    style="width: 720px; max-width: 92vw"
    @update:show="(v) => !v && emit('close')"
  >
    <p class="modal-note">
      Stored configuration for release <strong>{{ name }}</strong> in <strong>{{ namespace }}</strong> (helm get
      values).
    </p>
    <div v-if="error" class="error">{{ error }}</div>
    <div v-if="values === null" class="empty">Loading…</div>
    <div v-else-if="values.trim() === ''" class="empty">No user-supplied values (chart defaults only).</div>
    <YamlView v-else :text="values" />
    <template #footer>
      <div class="modal-actions">
        <NButton @click="emit('close')">Close</NButton>
      </div>
    </template>
  </NModal>
</template>

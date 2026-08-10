<script setup lang="ts">
import { NButton, NModal } from 'naive-ui';
import { computed, ref, watch } from 'vue';

import { api } from '../api';
import { failureNotice } from '../apiFailure';
import { helmValuesEmpty } from '../emptyState';
import EmptyState from './EmptyState.vue';
import LoadingNotice from './LoadingNotice.vue';
import YamlView from './YamlView.vue';

// A release's stored configuration (helm get values), read-only.
//
// Events emitted:
//   (e: 'close'): void
const props = defineProps<{ cluster: string; namespace: string; name: string }>();
const emit = defineEmits<{ (e: 'close'): void }>();

const values = ref<string | null>(null);
const error = ref<string | null>(null);

// `values === null` is BOTH "the request has not answered" and "the request failed", which
// is why the modal used to leave "Loading…" on screen forever underneath its own error
// message. The error is the loading branch's terminator, not a separate line above it.
const loading = computed(() => values.value === null && error.value === null);
const emptyCopy = computed(() =>
  helmValuesEmpty({
    loading: loading.value,
    failed: error.value !== null,
    blank: values.value !== null && values.value.trim() === '',
    release: props.name,
  }),
);

watch(
  () => [props.cluster, props.namespace, props.name],
  (_v, _o, onCleanup) => {
    let cancelled = false;
    onCleanup(() => (cancelled = true));
    api
      .helmReleaseValues(props.cluster, props.namespace, props.name)
      .then((y) => !cancelled && (values.value = y))
      .catch((e) => !cancelled && (error.value = failureNotice(e)));
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
    <LoadingNotice v-if="loading" />
    <EmptyState v-else-if="emptyCopy" :title="emptyCopy.title" :body="emptyCopy.body" variant="inline" />
    <YamlView v-else-if="values" :text="values" />
    <template #footer>
      <div class="modal-actions">
        <NButton @click="emit('close')">Close</NButton>
      </div>
    </template>
  </NModal>
</template>

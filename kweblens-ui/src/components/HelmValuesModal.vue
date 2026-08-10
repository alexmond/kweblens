<script setup lang="ts">
import { NButton, NModal } from 'naive-ui';
import { computed } from 'vue';

import { api } from '../api';
import { useAsyncData } from '../composables/useAsyncData';
import { helmValuesEmpty } from '../emptyState';
import EmptyState from './EmptyState.vue';
import ErrorNotice from './ErrorNotice.vue';
import LoadingNotice from './LoadingNotice.vue';
import YamlView from './YamlView.vue';

// A release's stored configuration (helm get values), read-only.
//
// Events emitted:
//   (e: 'close'): void
const props = defineProps<{ cluster: string; namespace: string; name: string }>();
const emit = defineEmits<{ (e: 'close'): void }>();

// `helm get values` is a READ, so its failure is the one shape that can honestly offer to run
// itself again: the worst a Retry costs is the same message. `useAsyncData` is the shipped
// three-state loader (loading / loaded / failed) and hands `reload` to the notice — replacing
// the hand-rolled pair here, where `values === null` used to mean BOTH "not answered yet" and
// "failed" and left "Loading…" on screen forever underneath the error.
const {
  data: values,
  loading,
  error,
  reload,
} = useAsyncData(
  () => [props.cluster, props.namespace, props.name],
  () => api.helmReleaseValues(props.cluster, props.namespace, props.name),
);

const emptyCopy = computed(() =>
  helmValuesEmpty({
    loading: loading.value,
    failed: error.value !== null,
    blank: values.value !== null && values.value.trim() === '',
    release: props.name,
  }),
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
    <ErrorNotice v-if="error" :message="error" :retrying="loading" @retry="reload" />
    <LoadingNotice v-else-if="loading" />
    <EmptyState v-else-if="emptyCopy" :title="emptyCopy.title" :body="emptyCopy.body" variant="inline" />
    <YamlView v-else-if="values" :text="values" />
    <template #footer>
      <div class="modal-actions">
        <NButton @click="emit('close')">Close</NButton>
      </div>
    </template>
  </NModal>
</template>

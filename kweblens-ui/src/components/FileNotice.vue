<script setup lang="ts">
// One explained outcome of a file-browser request.
//
// The backend gives every failure its own code, and they are not all the same KIND of
// thing: "the feature is switched off" and "this image ships no shell" are correct states
// of the system, while "the command failed in the container" is a malfunction. Only the
// last one is an error, so only the last one renders as one (ErrorNotice, red, with Retry);
// the others get a calmer notice that leads with what is true and follows with what to do.
//
// Emits: retry () — the caller re-runs the request.
import type { FileNotice } from '../podFiles';
import ErrorNotice from './ErrorNotice.vue';

defineProps<{ notice: FileNotice; retrying?: boolean }>();
const emit = defineEmits<{ (e: 'retry'): void }>();
</script>

<template>
  <ErrorNotice
    v-if="notice.tone === 'error'"
    :message="`${notice.title} — ${notice.detail}`"
    :retrying="retrying"
    @retry="emit('retry')"
  />
  <div v-else class="files-notice" :class="notice.tone" role="status">
    <p class="files-notice-title">{{ notice.title }}</p>
    <p class="files-notice-detail">{{ notice.detail }}</p>
    <p v-if="notice.hint" class="files-notice-hint">{{ notice.hint }}</p>
    <button v-if="notice.retryable" type="button" class="btn" :disabled="retrying" @click="emit('retry')">
      {{ retrying ? 'Retrying…' : 'Retry' }}
    </button>
  </div>
</template>

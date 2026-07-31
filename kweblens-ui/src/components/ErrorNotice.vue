<script setup lang="ts">
// A failed request, with a way to try again.
//
// Retry exists because a timeout is the one error a user can act on immediately, and until
// now the only recovery was reloading the whole page — which throws away every other pane's
// state to re-run one request.
defineProps<{ message: string; retrying?: boolean }>();
const emit = defineEmits<{ (e: 'retry'): void }>();
</script>

<template>
  <div class="error error-notice" role="alert">
    <span class="error-text">{{ message }}</span>
    <button type="button" class="btn error-retry" :disabled="retrying" @click="emit('retry')">
      {{ retrying ? 'Retrying…' : 'Retry' }}
    </button>
  </div>
</template>

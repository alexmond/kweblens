<script setup lang="ts">
import { computed, ref } from 'vue';

import { api } from '../api';

// Version / build-time / source-repo footer, from Actuator's public /actuator/info.
const info = ref<{ build?: { version?: string; time?: string } } | null>(null);
api
  .info()
  .then((i) => (info.value = i))
  .catch(() => undefined);

const version = computed(() => info.value?.build?.version);
const built = computed(() => info.value?.build?.time);
</script>

<template>
  <footer class="app-footer">
    <a class="repo-link" href="https://github.com/alexmond/kweblens" target="_blank" rel="noreferrer">
      github.com/alexmond/kweblens ↗
    </a>
    <span class="ver-line" :title="built ? `Built ${built}` : undefined">
      {{ version ? `v${version}` : 'dev' }}{{ built ? ` · built ${new Date(built).toLocaleString()}` : '' }}
    </span>
  </footer>
</template>

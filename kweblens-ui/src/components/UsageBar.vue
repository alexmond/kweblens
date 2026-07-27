<script setup lang="ts">
import { computed } from 'vue';

// A used/total value with a proportional fill bar (vCenter/Freelens style).
const props = defineProps<{ fraction: number; color: string; text: string }>();

const pct = computed(() => Math.max(0, Math.min(100, props.fraction * 100)));
</script>

<template>
  <div class="ubar" :title="`${Math.round(pct)}%`">
    <div class="ubar-track">
      <div class="ubar-fill" :style="{ width: pct + '%', background: color }" />
    </div>
    <div class="ubar-text">{{ text }}</div>
  </div>
</template>

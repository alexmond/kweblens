<script setup lang="ts">
import { NProgress } from 'naive-ui';
import { computed } from 'vue';

// A used/total value with a proportional fill bar (Naive NProgress) + a value label.
const props = defineProps<{ fraction: number; color: string; text: string }>();

const pct = computed(() => Math.max(0, Math.min(100, props.fraction * 100)));
</script>

<template>
  <div class="ubar" :title="`${Math.round(pct)}%`">
    <NProgress type="line" :percentage="pct" :color="color" :show-indicator="false" :height="8" />
    <div class="ubar-text">{{ text }}</div>
  </div>
</template>

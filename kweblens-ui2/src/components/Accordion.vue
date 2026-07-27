<script setup lang="ts">
import { ref } from 'vue';

// Collapsible section — a Freelens-style accordion. Body is the default slot.
const props = defineProps<{ title: string; count?: number; defaultOpen?: boolean }>();

const open = ref(props.defaultOpen ?? true);
</script>

<template>
  <section class="ov-sec acc">
    <h3 class="acc-head" @click="open = !open">
      <span :class="'acc-caret' + (open ? ' open' : '')">▸</span>
      {{ title }}
      <span v-if="count !== undefined" class="acc-count">{{ count }}</span>
    </h3>
    <div v-if="open" class="acc-body"><slot /></div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue';

import type { KubeObject } from '../types';
import { containerSquares } from './containerSquares';

// One coloured square per container, by state (Freelens-style), with a hover tooltip.
// The computation lives in containerSquares.ts so it can be tested without a DOM.
const props = defineProps<{ obj: KubeObject }>();

const squares = computed(() => containerSquares(props.obj));
</script>

<template>
  <template v-if="squares.length === 0">—</template>
  <span v-else class="csquares">
    <span
      v-for="sq in squares"
      :key="sq.key"
      :class="['csq', 'csq-' + sq.tone, sq.role !== 'app' ? 'csq-' + sq.role : '', sq.gap ? 'csq-gap' : '']"
      :title="sq.title"
    />
  </span>
</template>

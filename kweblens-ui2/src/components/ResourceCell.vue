<script setup lang="ts">
import { computed } from 'vue';

import type { CellSpec, TableColumn } from '../table';
import { toneFor } from '../table';
import type { KubeObject } from '../types';
import ContainerSquares from './ContainerSquares.vue';
import UsageBar from './UsageBar.vue';

// Renders one table cell from a column's CellSpec: a usage bar, container squares, or text
// (with an optional status/ready tone pill). Keeps ResourceTable's template declarative.
const props = defineProps<{ col: TableColumn; obj: KubeObject }>();

const spec = computed<CellSpec>(() => {
  if (props.col.cell) {
    return props.col.cell(props.obj);
  }
  const text = props.col.render(props.obj);
  return { type: 'text', text, tone: toneFor(props.col.key, text) };
});

const usage = computed(() => (spec.value.type === 'usagebar' ? spec.value : null));
const isContainers = computed(() => spec.value.type === 'containers');
const text = computed(() => (spec.value.type === 'text' ? spec.value : null));
</script>

<template>
  <UsageBar v-if="usage" :fraction="usage.fraction" :color="usage.color" :text="usage.text" />
  <ContainerSquares v-else-if="isContainers" :obj="obj" />
  <template v-else-if="text">
    <span v-if="text.tone" :class="'status-pill status-' + text.tone">{{ text.text }}</span>
    <template v-else>{{ text.text }}</template>
  </template>
</template>

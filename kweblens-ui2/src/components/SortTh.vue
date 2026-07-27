<script setup lang="ts">
import type { SortState } from '../composables/useTableSort';

defineProps<{ label: string; colKey: string; sort: SortState }>();
const emit = defineEmits<{ (e: 'sort', key: string): void }>();
</script>

<template>
  <th class="sortable" @click="emit('sort', colKey)">
    {{ label }}
    <span v-if="sort.key === colKey" class="sort-ind">{{ sort.dir === 1 ? ' ▲' : ' ▼' }}</span>
  </th>
</template>

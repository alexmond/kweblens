<script setup lang="ts">
// One dashboard stat card, shared by the Cluster and Workloads overviews.
//
// Clickable when a destination is given. Renders as a real <button> in that case rather than a
// div with a @click, so it is keyboard-reachable and announced as interactive; a card with
// nowhere to go stays a plain div, so what is clickable is honestly signalled.
defineProps<{ value: string | number; label: string; danger?: boolean; clickable?: boolean }>();
const emit = defineEmits<{ (e: 'select'): void }>();
</script>

<template>
  <button
    v-if="clickable"
    type="button"
    :class="'ov-card ov-card-link' + (danger ? ' danger' : '')"
    @click="emit('select')"
  >
    <div class="ov-num">{{ value }}</div>
    <div class="ov-lbl">{{ label }}</div>
  </button>
  <div v-else :class="'ov-card' + (danger ? ' danger' : '')">
    <div class="ov-num">{{ value }}</div>
    <div class="ov-lbl">{{ label }}</div>
  </div>
</template>

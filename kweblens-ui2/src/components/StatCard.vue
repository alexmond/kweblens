<script setup lang="ts">
// One dashboard stat card, shared by the Cluster and Workloads overviews.
// Purely presentational — emits nothing.
defineProps<{ value: string | number; label: string; danger?: boolean }>();
</script>

<template>
  <div :class="'ov-card' + (danger ? ' danger' : '')">
    <div class="ov-num">{{ value }}</div>
    <div class="ov-lbl">{{ label }}</div>
  </div>
</template>

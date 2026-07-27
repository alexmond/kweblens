<script setup lang="ts">
defineProps<{ map: Record<string, string> }>();
</script>

<template>
  <div class="chips">
    <span v-for="(v, k) in map" :key="k" class="chip">{{ k }}={{ v }}</span>
  </div>
</template>

<script setup lang="ts">
import { NTag } from 'naive-ui';

defineProps<{ map: Record<string, string> }>();
</script>

<template>
  <div class="chips">
    <NTag v-for="(v, k) in map" :key="k" size="small" :bordered="false">{{ k }}={{ v }}</NTag>
  </div>
</template>

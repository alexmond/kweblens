<script setup lang="ts">
import { NCollapse, NCollapseItem } from 'naive-ui';

// Collapsible section (Naive NCollapse). Body is the default slot.
const props = defineProps<{ title: string; count?: number; defaultOpen?: boolean }>();

const expanded = (props.defaultOpen ?? true) ? ['sec'] : [];
</script>

<template>
  <NCollapse :default-expanded-names="expanded" class="ov-sec acc">
    <NCollapseItem name="sec">
      <template #header>
        {{ title }}
        <span v-if="count !== undefined" class="acc-count">{{ count }}</span>
      </template>
      <slot />
    </NCollapseItem>
  </NCollapse>
</template>

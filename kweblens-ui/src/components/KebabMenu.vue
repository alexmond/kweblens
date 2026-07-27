<script setup lang="ts">
import { NButton, NDropdown } from 'naive-ui';
import type { DropdownOption } from 'naive-ui';
import { computed } from 'vue';

import type { KebabItem } from './helm-types';

// A generic kebab (⋮) actions menu for the non-resource tables (Helm charts/releases/repos).
// Wraps Naive's NDropdown (teleported + collision-aware by default, so the table's overflow
// container never clips it) while keeping the `items: KebabItem[]` contract its callers rely on.
const props = defineProps<{ items: KebabItem[] }>();

const options = computed<DropdownOption[]>(() =>
  props.items.map((it, i) => ({
    label: it.label,
    key: String(i),
    disabled: it.disabled,
    props: { class: it.danger ? 'menu-danger' : '' },
  })),
);

const onSelect = (key: string) => {
  props.items[Number(key)]?.onClick();
};
</script>

<template>
  <NDropdown trigger="click" :options="options" @select="onSelect">
    <NButton text size="small" title="Actions" @click.stop>⋮</NButton>
  </NDropdown>
</template>

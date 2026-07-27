<script setup lang="ts">
import { NTag } from 'naive-ui';
import { computed } from 'vue';

import { statusTone } from '../columns';

// A status/phase value coloured green/amber/red by health (Naive NTag); plain text otherwise.
const props = defineProps<{ text: string }>();

const TONE_TYPE = { ok: 'success', warn: 'warning', err: 'error' } as const;
const type = computed(() => TONE_TYPE[statusTone(props.text) as keyof typeof TONE_TYPE] ?? null);
</script>

<template>
  <NTag v-if="type" :type="type" size="small" :bordered="false" round>{{ text }}</NTag>
  <template v-else>{{ text }}</template>
</template>

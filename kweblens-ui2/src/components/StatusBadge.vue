<script setup lang="ts">
import { computed } from 'vue';

import { statusTone } from '../columns';

// A status/phase value coloured green/amber/red by health; plain text when unrecognised.
const props = defineProps<{ text: string }>();

const tone = computed(() => statusTone(props.text));
</script>

<template>
  <span v-if="tone" :class="'status-pill status-' + tone">{{ text }}</span>
  <template v-else>{{ text }}</template>
</template>

<script setup lang="ts">
import { computed } from 'vue';

import { yamlTokens } from '../yaml';

const props = defineProps<{ text: string }>();

const lines = computed(() => props.text.split('\n').map((line) => yamlTokens(line)));
</script>

<template>
  <pre class="yaml"><span v-for="(tokens, i) in lines" :key="i"><span
        v-for="(tok, j) in tokens"
        :key="j"
        :class="tok.cls"
      >{{ tok.text }}</span>{{ '\n' }}</span></pre>
</template>

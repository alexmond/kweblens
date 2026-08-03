<script setup lang="ts">
// One summary row of the Overview tab: a `<dt>`/`<dd>` fragment inside the enclosing `dl.kv`.
//
// Its own component because the wide layout (#232) renders the summary rows into TWO lists —
// the object's own facts in the main column, provenance in the aside — and a copy-pasted
// second block of this markup is how the two would drift apart.
//
// Emits (passed straight through to Overview.vue's own):
//   navigate     (kind: string, ns?: string)
//   helm-release (namespace: string, name: string)
import { NTag } from 'naive-ui';

import type { OverviewField, OvValue } from './overview';

defineProps<{ field: OverviewField; value: OvValue }>();
const emit = defineEmits<{
  (e: 'navigate', kind: string, ns?: string): void;
  (e: 'helm-release', namespace: string, name: string): void;
}>();
</script>

<template>
  <dt>{{ field.label }}</dt>
  <dd :class="field.mono ? 'mono' : undefined">
    <template v-if="value.kind === 'text'">{{ value.text }}</template>
    <button v-else-if="value.kind === 'nav'" class="cell-link" @click="emit('navigate', value.navKind, value.navNs)">
      {{ value.text }}
    </button>
    <template v-else-if="value.kind === 'helm'">
      <NTag size="small" type="info" :bordered="false">Helm</NTag>{{ ' ' }}
      <button
        class="cell-link"
        title="Open this release's resources"
        @click="emit('helm-release', value.rns, value.rel)"
      >
        {{ value.rel }}{{ value.rns ? ` (${value.rns})` : '' }}
      </button>
    </template>
    <template v-else-if="value.kind === 'owners'">
      <span v-for="(o, i) in value.owners" :key="o.kind + '/' + o.name">
        {{ i > 0 ? ', ' : '' }}
        <button class="cell-link" @click="emit('navigate', o.kind, o.ns)">{{ o.kind }}/{{ o.name }}</button>
      </span>
    </template>
  </dd>
</template>

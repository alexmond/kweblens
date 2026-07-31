<script setup lang="ts">
import { computed } from 'vue';

import type { StateCount } from '../types';

// One dashboard card: the kind, its total, a proportional bar, and the per-state breakdown.
//
// The bar is what a total cannot do — it makes "mostly fine" and "mostly broken"
// distinguishable across a whole row of cards without reading a single number. The list under
// it names each state, which is strictly more than "N need attention" and costs nothing: the
// server already classifies every object to produce the named-offenders list.
//
// Clickable when a destination is given. Renders as a real <button> in that case rather than a
// div with a @click, so it is keyboard-reachable and announced as interactive; a card with
// nowhere to go stays a plain div, so what is clickable is honestly signalled.
const props = defineProps<{
  value: string | number;
  label: string;
  states?: StateCount[];
  danger?: boolean;
  clickable?: boolean;
}>();
const emit = defineEmits<{ (e: 'select'): void }>();

/** Only states with objects in them get a bar segment; a zero-width segment is not a segment. */
const segments = computed(() => (props.states ?? []).filter((s) => s.count > 0));
const total = computed(() => segments.value.reduce((n, s) => n + s.count, 0));

/**
 * Percentage widths that always sum to 100.
 *
 * Rounding each independently leaves a gap or an overflow, which reads as a rendering fault on
 * a bar whose whole job is to look like one filled strip. The last segment takes the remainder.
 */
const bar = computed(() => {
  const t = total.value;
  if (t === 0) {
    return [];
  }
  let used = 0;
  return segments.value.map((s, i) => {
    const pct = i === segments.value.length - 1 ? 100 - used : Math.round((s.count / t) * 100);
    used += pct;
    return { ...s, pct };
  });
});
</script>

<template>
  <component
    :is="clickable ? 'button' : 'div'"
    :type="clickable ? 'button' : undefined"
    :class="'ov-card' + (clickable ? ' ov-card-link' : '') + (danger ? ' danger' : '')"
    @click="clickable && emit('select')"
  >
    <div class="ov-head">
      <span class="ov-kind">{{ label }}</span>
      <span class="ov-num">{{ value }}</span>
    </div>
    <!-- An empty kind still gets a bar, flat and neutral, so a row of cards keeps its rhythm
         instead of one of them collapsing to a gap. -->
    <div class="ov-bar" :aria-hidden="true">
      <span v-if="bar.length === 0" class="ov-seg tone-empty" :style="{ width: '100%' }" />
      <span
        v-for="s in bar"
        v-else
        :key="s.label"
        :class="'ov-seg tone-' + s.tone"
        :style="{ width: s.pct + '%' }"
        :title="`${s.count} ${s.label}`"
      />
    </div>
    <ul v-if="states && states.length" class="ov-states">
      <li v-for="s in states" :key="s.label" :class="'ov-state tone-' + s.tone">
        <span class="ov-state-n">{{ s.count }}</span>
        <span class="ov-state-l">{{ s.label }}</span>
      </li>
    </ul>
  </component>
</template>

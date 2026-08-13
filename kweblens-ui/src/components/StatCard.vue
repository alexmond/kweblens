<script setup lang="ts">
import { computed } from 'vue';

import type { StateCount } from '../types';
import { cardStates, stateAction } from './statCard';

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
//
// With `statesClickable` each populated state is its own button too (#338) — `3 Pending` opens
// the kind's list filtered to exactly those three. That is why the card has TWO shapes rather
// than one: a button cannot contain a button. Measured in Chromium rather than assumed, because
// Vue builds the DOM with createElement and so is not subject to the HTML parser's rule that
// would have split them: nested that way they survive, and both failures are real — a click on
// the inner one also fires the outer, and the outer's accessible name absorbs every state below
// it ("Pods 93 80 Running 6 Completed …"), so a screen reader is read the whole card as the name
// of one control. So when the states are links the card shell is a plain <div> and its own
// destination moves to the head — the kind and its total, which is the part #154 made clickable
// and the part a reader aims at anyway.
const props = defineProps<{
  value: string | number;
  label: string;
  states?: StateCount[];
  danger?: boolean;
  clickable?: boolean;
  /** Whether a state with objects in it opens the kind's list filtered to that state. */
  statesClickable?: boolean;
}>();
const emit = defineEmits<{ (e: 'select'): void; (e: 'select-state', query: string): void }>();

/** The state lines, each told whether it opens anything. */
const lines = computed(() => cardStates(props.states ?? [], props.statesClickable === true));
/**
 * Where the card's own destination sits. It cannot be the shell while the states are buttons —
 * see the header — so it moves to the head, and the shell goes back to being a div.
 */
const headIsLink = computed(() => props.clickable === true && lines.value.some((s) => s.query !== null));
const shellIsLink = computed(() => props.clickable === true && !headIsLink.value);

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
    :is="shellIsLink ? 'button' : 'div'"
    :type="shellIsLink ? 'button' : undefined"
    :class="'ov-card' + (shellIsLink ? ' ov-card-link' : '') + (danger ? ' danger' : '')"
    @click="shellIsLink && emit('select')"
  >
    <component
      :is="headIsLink ? 'button' : 'div'"
      :type="headIsLink ? 'button' : undefined"
      :class="'ov-head' + (headIsLink ? ' ov-head-link' : '')"
      :title="headIsLink ? `Show all ${label}` : undefined"
      @click="headIsLink && emit('select')"
    >
      <span class="ov-kind">{{ label }}</span>
      <span class="ov-num">{{ value }}</span>
    </component>
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
    <!-- A line with a query is a button; one without is the text it always was. The tag changes
         with the state, so the tone colours below it must be set explicitly — a <button> does
         not inherit `color`, and that is exactly how `.ov-card.danger` shipped at 1.34:1. -->
    <ul v-if="lines.length" class="ov-states">
      <li v-for="s in lines" :key="s.label" :class="'ov-state tone-' + s.tone">
        <component
          :is="s.query ? 'button' : 'span'"
          :type="s.query ? 'button' : undefined"
          :class="'ov-state-line' + (s.query ? ' ov-state-link' : '')"
          :aria-label="s.query ? stateAction(s, label) : undefined"
          @click="s.query && emit('select-state', s.query)"
        >
          <span class="ov-state-n">{{ s.count }}</span>
          <span class="ov-state-l">{{ s.label }}</span>
        </component>
      </li>
    </ul>
  </component>
</template>

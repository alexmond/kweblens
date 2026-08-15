<script setup lang="ts">
import { NTag } from 'naive-ui';
import { computed } from 'vue';

import type { StatusTone } from '../columns';
import { badgeTone, statusTone } from '../columns';
import { TONE_VARS } from '../statusTones';

// A value coloured amber/red by health; plain text when there is no tone.
//
// `tone` comes from the caller that already classified the cell. It used to be re-derived here
// from the TEXT, which silently dropped every classification that is not keyword-based:
// readyTone('1/3') says warn, but statusTone('1/3') matches no keyword, so the Ready column was
// badged and then rendered as plain text — its colour never appeared at all. Deriving from the
// text remains the fallback for callers that pass only a string.
//
// Colours come from the semantic tokens rather than from Naive's `type`. Naive's light-theme
// warning tag measured 1.9:1 for its own text on its own background — the tone that matters
// most on an events list was the least readable one. The tokens are the palette the rest of
// the app uses and are defined per theme, so this is both readable and consistent. What each
// tone paints lives in `statusTones.ts`, where it can be asserted on without a DOM.
const props = defineProps<{ text: string; tone?: StatusTone }>();

// Callers that pass a `tone` have already applied badgeTone; the text fallback applies it here
// so the callers that pass only a string (Helm release/history status, the node's pod list)
// follow the same one convention — a pill is an exception, an ordinary value is plain text.
const tone = computed(() => props.tone ?? badgeTone(statusTone(props.text)));
const colour = computed(() => TONE_VARS[tone.value] ?? null);
</script>

<template>
  <!-- `:color` is what PAINTS: it becomes an inline style, so the pill's colour has exactly one
       source and no rule in any stylesheet can drift from it. `tone-*` is a LABEL and nothing
       else — no rule anywhere styles it, and adding one would be the second source of truth
       this component was built to avoid.
       It exists because a colour delivered inline carries no name in the DOM, and a checker can
       only address what has a name: `contrast-check.mjs` could match `.n-tag` and nothing
       finer, so it sampled whichever tone the row order happened to put first — the danger tone
       against this box's cluster, the warn tone against the simulator. Every tone that got
       measured was measured by luck, and the warn tone's 4.51:1 in light (a pass by one
       hundredth) had been sitting under the name of whatever else sorted ahead of it (GH#393). -->
  <NTag v-if="colour" :class="['status-badge', `tone-${tone}`]" :color="colour" size="small" :bordered="false" round>{{
    text
  }}</NTag>
  <template v-else>{{ text }}</template>
</template>

<style scoped>
/* A pill is a SHAPE, and a shape must not be cut in half by the box it sits in (GH#341).
   Measured on the Pods list the day the Status column started rendering the server's state:
   `CrashLoopBackOff` is a 119.38px tag inside a 102.83px `.n-ellipsis`, and Naive's cell hides
   its overflow — so 16.55px of the pill was simply gone, ending in a straight edge, reading
   `CrashLoopBackO` with nothing on screen to say anything was missing. (`ui-measure`'s
   `sliced` check was written for it and reproduces those numbers.)

   The column is wide enough for that state now, but a width cannot be the whole answer: a
   waiting reason can be `CreateContainerConfigError`, and sizing every table for the longest
   string any cluster might produce is how a column budget gets spent. So the pill keeps
   itself inside its container and truncates its own TEXT with an ellipsis, which is the
   ordinary way a table says "there is more" — and the cell's `ellipsis: { tooltip: true }`
   already offers the full value on hover. */
.n-tag {
  max-width: 100%;
}
:deep(.n-tag__content) {
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>

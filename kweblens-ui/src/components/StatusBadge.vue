<script setup lang="ts">
import { NTag } from 'naive-ui';
import { computed } from 'vue';

import type { StatusTone } from '../columns';
import { badgeTone, statusTone } from '../columns';

// A value coloured green/amber/red by health; plain text when there is no tone.
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
// the app uses and are defined per theme, so this is both readable and consistent.
const props = defineProps<{ text: string; tone?: StatusTone }>();

const TONE_VARS: Record<string, { color: string; textColor: string }> = {
  ok: { color: 'var(--ok-tint)', textColor: 'var(--ok-fg)' },
  warn: { color: 'var(--warn-tint)', textColor: 'var(--warn-fg)' },
  err: { color: 'var(--danger-tint)', textColor: 'var(--danger-fg)' },
};

// Callers that pass a `tone` have already applied badgeTone; the text fallback applies it here
// so the callers that pass only a string (Helm release/history status, the node's pod list)
// follow the same one convention — a pill is an exception, an ordinary value is plain text.
const colour = computed(() => TONE_VARS[props.tone ?? badgeTone(statusTone(props.text))] ?? null);
</script>

<template>
  <NTag v-if="colour" :color="colour" size="small" :bordered="false" round>{{ text }}</NTag>
  <template v-else>{{ text }}</template>
</template>

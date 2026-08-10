<script setup lang="ts">
// A pane with nothing to render, saying so.
//
// The first user is the zero-cluster install (GH#298), where the content pane rendered
// literally nothing — `main.content` measured `childElementCount: 0` — while the server had
// already logged the explanation. An empty pane cannot be told apart from a broken one, so
// the title is a REQUIRED prop: a surface cannot adopt this component and forget to say
// what happened.
//
// The copy comes from `emptyState.ts` rather than being written inline, so the branching
// (loading vs failed vs genuinely empty, writer vs signed-out) is testable without a DOM.
// The action is a slot, because what a button does belongs to the surface that owns it.
//
// `variant` is size, not meaning. The default `page` is the centred card the zero-cluster
// state needs, which fills a content pane; `inline` is the same two sentences with the frame
// dropped, for the panes, modals and dropdowns that already have one of their own — a
// bordered card nested in a bordered card reads as a second dialog, and a 150px-tall chart
// box has no room for one at all. Making it a variant rather than a second component is the
// point of the sweep: one component means one required `title`, so no surface can adopt it
// and forget to say what happened.
defineProps<{ title: string; body?: string; variant?: 'page' | 'inline' }>();
</script>

<template>
  <div :class="'empty-state' + (variant === 'inline' ? ' empty-state-inline' : '')" role="status">
    <p class="empty-state-title">{{ title }}</p>
    <p v-if="body" class="empty-state-body">{{ body }}</p>
    <div v-if="$slots.action" class="empty-state-action"><slot name="action" /></div>
  </div>
</template>

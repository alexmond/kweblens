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
defineProps<{ title: string; body?: string }>();
</script>

<template>
  <div class="empty-state" role="status">
    <p class="empty-state-title">{{ title }}</p>
    <p v-if="body" class="empty-state-body">{{ body }}</p>
    <div v-if="$slots.action" class="empty-state-action"><slot name="action" /></div>
  </div>
</template>

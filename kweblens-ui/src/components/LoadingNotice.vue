<script setup lang="ts">
// "Still loading" — the third of the three sentences an all-but-empty pane can say, and the
// one `EmptyState` must never be used for.
//
// It exists as its own component because it used to share `.empty` with the other two: the
// same grey line, the same padding, the same colour, said "Loading…", "No pods scheduled on
// this node." and "No data." A reader could not tell a request in flight from an answered
// one, which is the whole defect R3 is about. Different claim, different rendering.
//
// No props: every site says the same word, and a per-site variation would be wording that
// lives in a template instead of in `emptyState.ts`. `aria-busy` is what carries the meaning
// to a screen reader, which cannot see that the text is dim.
</script>

<template>
  <p class="pane-loading" role="status" aria-busy="true">Loading…</p>
</template>

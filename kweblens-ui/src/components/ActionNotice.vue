<script setup lang="ts">
// An action that did not complete — and deliberately NO Retry.
//
// `ErrorNotice`, which this sits beside, exists because a failed fetch is the one error a
// reader can act on immediately: its Retry re-runs a GET. An action is not that. Repeating a
// Helm upgrade, a port-forward, a delete or a sign-in is a write, and a button offering to do
// it again without the operator saying so is the autonomous remediation this project's
// standing rule forbids (suggest → approve → apply). So the re-do stays where it started: the
// modal's own Apply / Start forward / Sign in button, still on screen, still under the reader.
//
// Three lines, because they are three different claims and the reader needs all of them:
// what was attempted, what came back, and what is now true of the cluster. The last is the
// one a plain error div could never say, and it is hedged where the truth is unknown — see
// `paneFailure.ts` for why a failed write is not a write that did not happen.
//
// Rendered as `role="alert"` and in the `.error` box, like ErrorNotice: it is the same
// severity, only a different offer.
import type { ActionFailure } from '../paneFailure';

defineProps<{ failure: ActionFailure }>();
</script>

<template>
  <div class="error action-notice" role="alert">
    <p class="action-notice-title">{{ failure.title }}</p>
    <p class="action-notice-message">{{ failure.message }}</p>
    <p v-if="failure.consequence" class="action-notice-consequence">{{ failure.consequence }}</p>
  </div>
</template>

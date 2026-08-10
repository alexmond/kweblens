<script setup lang="ts">
// One slot that two different kinds of failure land in, dispatched to the right one.
//
// Most panes know which they are: a drawer tab only ever fetches, a modal only ever acts, and
// those use `ErrorNotice` or `ActionNotice` directly. Two do not. `App.vue`'s single error
// slot is written by the clusters fetch, the nav fetch and the object-list fetch AND by every
// row action plus bulk delete; `PortForwards` writes both its 3-second poll and its Stop
// button into one `error`. For those the classification belongs to whichever code path
// failed, not to the pane, so they carry a `PaneFailure` and hand it here.
//
// A component rather than two lines of `v-if` in each template, for the reason `EmptyState`'s
// title is a required prop: this way the branch is written once and a pane cannot adopt the
// union and then render an action's failure with a Retry button on it.
//
// Emits: retry () — only ever reachable for a read; see mayRetry.
import type { PaneFailure } from '../paneFailure';
import ActionNotice from './ActionNotice.vue';
import ErrorNotice from './ErrorNotice.vue';

defineProps<{ failure: PaneFailure; retrying?: boolean }>();
const emit = defineEmits<{ (e: 'retry'): void }>();
</script>

<template>
  <ErrorNotice v-if="failure.kind === 'read'" :message="failure.message" :retrying="retrying" @retry="emit('retry')" />
  <ActionNotice v-else :failure="failure" />
</template>

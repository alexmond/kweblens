<script setup lang="ts">
// Type-to-filter switcher for clusters and kinds, opened with Ctrl/Cmd-K.
//
// Why it exists: the rail labels each cluster with the first two characters of its id, so
// prod-eu and prod-us both read "PR" and can only be told apart by hovering one at a time
// (docs/design/cluster-selection.md). Typing a name is unambiguous, and it scales past the
// handful of tiles the rail can show. It also covers jumping to a kind, which is the
// command-palette gap the competitive review flagged against k9s.
//
// Ranking and filtering live in ../commandPalette so they can be tested without a DOM.
// Emits: pick (command), cancel ()
import { NModal } from 'naive-ui';
import { computed, nextTick, ref, watch } from 'vue';

import { buildCommands, type Command, filterCommands, wrapIndex } from '../commandPalette';
import type { ClusterInfo, NavCategory } from '../types';

const props = defineProps<{
  show: boolean;
  clusters: ClusterInfo[];
  nav: NavCategory[];
  activeCluster: string | null;
}>();
const emit = defineEmits<{ (e: 'pick', command: Command): void; (e: 'cancel'): void }>();

const query = ref('');
const active = ref(0);
const input = ref<HTMLInputElement | null>(null);

const commands = computed(() => buildCommands(props.clusters, props.nav, props.activeCluster));
const hits = computed(() => filterCommands(commands.value, query.value));

// Reopening with the previous query still in the box would make the palette feel stale, so
// each open starts empty with the first row armed.
watch(
  () => props.show,
  (open) => {
    if (open) {
      query.value = '';
      active.value = 0;
      void nextTick(() => input.value?.focus());
    }
  },
);

// A narrowing query can leave the cursor past the end of the list.
watch(hits, () => (active.value = 0));

const move = (delta: number) => (active.value = wrapIndex(active.value + delta, hits.value.length));

const choose = (command: Command | undefined) => {
  if (command) {
    emit('pick', command);
  }
};
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    class="palette-card"
    :bordered="false"
    :closable="false"
    style="width: 560px"
    @update:show="(v: boolean) => !v && emit('cancel')"
  >
    <input
      ref="input"
      v-model="query"
      class="palette-input"
      type="text"
      placeholder="Switch cluster or jump to a resource…"
      aria-label="Command palette"
      @keydown.down.prevent="move(1)"
      @keydown.up.prevent="move(-1)"
      @keydown.enter.prevent="choose(hits[active])"
      @keydown.esc.prevent="emit('cancel')"
    />
    <ul v-if="hits.length" class="palette-list">
      <li
        v-for="(c, i) in hits"
        :key="c.key"
        :class="'palette-row' + (i === active ? ' active' : '')"
        @mouseenter="active = i"
        @click="choose(c)"
      >
        <span :class="'palette-kind palette-kind-' + c.kind">{{ c.kind === 'cluster' ? 'Cluster' : 'Kind' }}</span>
        <span class="palette-label">{{ c.label }}</span>
        <span class="palette-hint">{{ c.hint }}</span>
      </li>
    </ul>
    <p v-else class="palette-empty">No match for “{{ query }}”.</p>
  </NModal>
</template>

<script setup lang="ts">
// Type-to-filter switcher for clusters, kinds and — since GH#259 — OBJECTS, opened with
// Ctrl/Cmd-K.
//
// Why it exists: the rail labels each cluster with the first two characters of its id, so
// prod-eu and prod-us both read "PR" and can only be told apart by hovering one at a time
// (docs/design/cluster-selection.md). Typing a name is unambiguous, and it scales past the
// handful of tiles the rail can show. It also covers jumping to a kind, which is the
// command-palette gap the competitive review flagged against k9s.
//
// Global search rides the same box rather than a second one: the palette already has the
// keyboard model, the ranking and the tests, so one engine gets a proven surface (issue
// #259's own recommendation). A persistent top-bar box is a second entry point onto the
// same engine, and a separate change.
//
// Ranking, merging and the scope notes live in ../commandPalette so they can be tested
// without a DOM.
// Emits: pick (command), cancel ()
import { NModal } from 'naive-ui';
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';

import { api } from '../api';
import {
  buildCommands,
  type Command,
  filterCommands,
  mergeCommands,
  objectCommands,
  scopeNotes,
  shouldSearch,
  STRONG_MATCH,
  wrapIndex,
} from '../commandPalette';
import type { ClusterInfo, NavCategory, SearchResponse } from '../types';

const props = defineProps<{
  show: boolean;
  clusters: ClusterInfo[];
  nav: NavCategory[];
  activeCluster: string | null;
  /** The namespace filter in force, so search is scoped the same way the tables are. */
  namespace: string | null;
}>();
const emit = defineEmits<{ (e: 'pick', command: Command): void; (e: 'cancel'): void }>();

const query = ref('');
const active = ref(0);
const input = ref<HTMLInputElement | null>(null);

const commands = computed(() => buildCommands(props.clusters, props.nav, props.activeCluster));

// --- Object search ---------------------------------------------------------
// Debounced, because one request lists every kind in the bounded set: typing "sonarqube"
// undebounced is nine of them. `seq` guards against out-of-order responses — a slow answer
// to an earlier query must never overwrite a fast answer to the current one, which is how
// a search box ends up showing results for a prefix of what is in it.
const DEBOUNCE_MS = 250;
const result = ref<SearchResponse | null>(null);
const searching = ref(false);
const searchError = ref<string | null>(null);
let timer: ReturnType<typeof setTimeout> | undefined;
let inFlight: AbortController | undefined;
let seq = 0;

const cancelPending = () => {
  clearTimeout(timer);
  inFlight?.abort();
  inFlight = undefined;
};

const runSearch = (cluster: string, q: string, mine: number) => {
  inFlight = new AbortController();
  searching.value = true;
  api
    .search(cluster, q, { namespace: props.namespace, limit: 20, signal: inFlight.signal })
    .then((r) => {
      if (mine === seq) {
        result.value = r;
        searchError.value = null;
      }
    })
    .catch((e: unknown) => {
      // An aborted request is this component superseding itself, not a failure to report.
      if (mine === seq && !(e instanceof DOMException && e.name === 'AbortError')) {
        searchError.value = String(e);
        result.value = null;
      }
    })
    .finally(() => {
      if (mine === seq) {
        searching.value = false;
      }
    });
};

watch([query, () => props.show, () => props.activeCluster], () => {
  cancelPending();
  seq += 1;
  const mine = seq;
  const q = query.value.trim();
  const cluster = props.activeCluster;
  if (!props.show || !cluster || !shouldSearch(q)) {
    result.value = null;
    searchError.value = null;
    searching.value = false;
    return;
  }
  timer = setTimeout(() => runSearch(cluster, q, mine), DEBOUNCE_MS);
});

onBeforeUnmount(cancelPending);

const objectHits = computed(() => objectCommands(result.value?.hits ?? []));
// Once objects are on the list, navigation rows have to earn their place: the subsequence
// tier that makes "rs" find "ReplicaSets" also makes "sim" find "Persistent Volumes", and two
// rows of that above the objects you were looking for is the crowding this feature exists to
// avoid. With no objects the bar drops back — a loose match still beats an empty palette.
const navHits = computed(() =>
  filterCommands(commands.value, query.value, 30, objectHits.value.length > 0 ? STRONG_MATCH : 0),
);
const hits = computed(() => mergeCommands(navHits.value, objectHits.value));
const notes = computed(() => scopeNotes(result.value, objectHits.value.length));

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

const rowType = (kind: Command['kind']) => (kind === 'cluster' ? 'Cluster' : kind === 'nav' ? 'Kind' : 'Object');

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
      placeholder="Search objects, switch cluster, or jump to a resource…"
      aria-label="Command palette"
      @keydown.down.prevent="move(1)"
      @keydown.up.prevent="move(-1)"
      @keydown.enter.prevent="choose(hits[active])"
      @keydown.esc.prevent="emit('cancel')"
    />
    <!-- `mousemove`, NOT `mouseenter`, to arm a row under the mouse.
         The modal is vertically centred, so results arriving from the network make it grow
         and shift the whole list UP under a stationary cursor. The browser then fires
         mouseenter on whatever row has slid beneath the pointer, which re-arms it — measured
         here: typing `sim-pod-7` and pressing Enter opened `sim-pod-77`, the eighth row,
         because that is what ended up under a mouse that had not moved since the click on the
         input. mousemove only fires on actual pointer movement, so the armed row now changes
         when the reader moves the mouse and not when the layout moves the list. -->
    <ul v-if="hits.length" class="palette-list">
      <li
        v-for="(c, i) in hits"
        :key="c.key"
        :class="'palette-row' + (i === active ? ' active' : '')"
        @mousemove="active = i"
        @click="choose(c)"
      >
        <span :class="'palette-kind palette-kind-' + c.kind">{{ rowType(c.kind) }}</span>
        <span class="palette-label">{{ c.label }}</span>
        <span v-if="c.hit?.status" class="palette-status">{{ c.hit.status }}</span>
        <span class="palette-hint">{{ c.hint }}</span>
      </li>
    </ul>
    <p v-else-if="!searching" class="palette-empty">No match for “{{ query }}”.</p>

    <!-- What the search covered. A dropdown that shows rows and says nothing else implies it
         looked everywhere; it looked in a bounded set of kinds, and the rest are named here. -->
    <p v-if="searching" class="palette-scope">Searching objects…</p>
    <p v-else-if="searchError" class="palette-scope palette-scope-error">Object search failed: {{ searchError }}</p>
    <p v-else-if="notes.length" class="palette-scope">
      <span v-for="(n, i) in notes" :key="n.text" class="palette-note" :title="n.detail">
        <span v-if="i > 0" aria-hidden="true"> · </span>{{ n.text }}
      </span>
    </p>
    <p v-else-if="query.trim().length === 1" class="palette-scope">Type one more character to search objects.</p>
  </NModal>
</template>

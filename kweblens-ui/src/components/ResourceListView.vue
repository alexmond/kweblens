<script setup lang="ts">
import { NButton, NCheckbox, NInput, NPopover } from 'naive-ui';
import { computed } from 'vue';

import { resourceListEmpty } from '../emptyState';
import { activeQuery, FILTER_HELP, FILTER_HELP_NOTES, parseFilter } from '../objectFilter';
import type { RowAction } from '../rowActions';
import { controlAccess } from '../permissions';
import { listCountLabel } from '../shell';
import { statusChips } from '../statusChips';
import type { TableColumn } from '../table';
import type { KindAccess, KubeObject, NavItem } from '../types';
import ResourceTable from './ResourceTable.vue';

// The resource-list surface: header (search, create, columns), bulk bar, and the table.
// Emits: update:query, toggle-col(key), clear-selection, bulk-delete, update:selection(keys),
//        open(obj), namespace-click(ns), create, row-action(action,obj,container?)
const props = defineProps<{
  selected: NavItem;
  filtered: KubeObject[];
  objects: KubeObject[];
  /**
   * The rows every narrowing EXCEPT the query's positive `status:` terms already selects —
   * namespace, Helm scope, and the rest of the filter. The status chips count these, which is
   * what makes a chip's number the number of rows its click produces; see `statusChips.ts`.
   * The shell owns it because the Helm scope lives there and is not visible from here.
   */
  statusRows: KubeObject[];
  query: string;
  live: boolean;
  /** The Helm release the view is scoped to, or null. It narrows `filtered`, so it has to
   *  be visible to the count and the empty state or both describe a different list. */
  scope: string | null;
  /** The namespace filter, named in the empty state rather than left to be guessed. */
  namespace: string | null;
  /** Whether the list fetch failed: the shell renders that error, so the table stays quiet. */
  failed: boolean;
  tableCols: TableColumn[];
  visibleCols: TableColumn[];
  hiddenCols: Set<string>;
  /** Columns the picker pinned against the width rule (#238). */
  keptCols: Set<string>;
  /** Columns the width took away, reported back up by the table. */
  autoHiddenCols: Set<string>;
  selection: Set<string>;
  selectedKey: string | null;
  loading: boolean;
  fetchChildren?: (obj: KubeObject) => Promise<KubeObject[]>;
  /**
   * What the deployment's SERVICE ACCOUNT may do with this kind here (#354), or null when it
   * is not known. Null is not a refusal: every control stays enabled. See `permissions.ts`.
   */
  access?: KindAccess | null;
}>();
const emit = defineEmits<{
  (e: 'update:query', v: string): void;
  (e: 'toggle-col', key: string): void;
  (e: 'auto-hidden', keys: string[]): void;
  (e: 'clear-selection'): void;
  (e: 'bulk-delete'): void;
  (e: 'update:selection', keys: string[]): void;
  (e: 'open', obj: KubeObject): void;
  (e: 'namespace-click', ns: string): void;
  (e: 'create'): void;
  (e: 'row-action', action: RowAction, obj: KubeObject, container?: string): void;
}>();

// The filter expression behind the search box (objectFilter.ts owns the grammar). Parsed here
// as well as in `filterObjects` so the header can say WHY a pattern was not applied; parsing
// walks the query string, not the object list.
const filter = computed(() => parseFilter(props.query));
// A query that did not parse narrowed nothing, so the count and the empty state must not
// describe a filtered list — "0 of 137" over a full table is the header contradicting the body.
const narrowedBy = computed(() => activeQuery(props.query, filter.value));

const countLabel = computed(() =>
  listCountLabel(props.filtered.length, props.objects.length, narrowedBy.value, props.scope !== null),
);

// The states present in the rows, each a click away from being the filter (statusChips.ts owns
// the derivation and the promise its number makes). Recomputed when the rows or the query
// change — one pass over rows already in memory, no request and no watch of its own.
const chips = computed(() => statusChips(props.statusRows, props.query));

// Bulk delete asks the same question the row menu's Delete does, and answers it the same way:
// disabled ONLY on a real refusal, with the sentence that names the service account. A review
// that never answered leaves it enabled and lets the cluster be the one to say no.
const bulkDeleteAccess = computed(() => controlAccess(props.access, 'delete'));

// Why the table is empty, worked out here and rendered there — three unrelated situations
// used to share naive-ui's default "No Data".
const emptyCopy = computed(() =>
  resourceListEmpty({
    loading: props.loading,
    failed: props.failed,
    total: props.objects.length,
    query: narrowedBy.value,
    scope: props.scope,
    noun: props.selected.label,
    namespace: props.namespace,
  }),
);
</script>

<template>
  <div class="list-view">
    <div class="content-head">
      <h1>{{ selected.label }}</h1>
      <span class="count">{{ countLabel }}</span>
      <span v-if="live" class="live" title="Live-updating (SSE watch)"><span class="dot" /> live</span>
      <NInput
        :value="query"
        size="small"
        clearable
        :placeholder="`Search ${selected.label}…`"
        style="width: 220px"
        @update:value="(v) => emit('update:query', v)"
      />
      <!-- The syntax is opt-in and therefore invisible: the placeholder still says "Search",
           because a bare word is still a plain substring search and that is the common case.
           This is the affordance that says there is more, without changing the common one. -->
      <NPopover trigger="click" placement="bottom-start">
        <template #trigger>
          <NButton size="tiny" quaternary class="filter-help-btn" title="Filter syntax" aria-label="Filter syntax">
            ?
          </NButton>
        </template>
        <div class="filter-help">
          <div class="filter-help-title">Filter syntax</div>
          <div v-for="row in FILTER_HELP" :key="row.example" class="filter-help-row">
            <code>{{ row.example }}</code>
            <span>{{ row.meaning }}</span>
          </div>
          <p v-for="note in FILTER_HELP_NOTES" :key="note" class="filter-help-note">{{ note }}</p>
        </div>
      </NPopover>
      <div class="spacer" />
      <NButton size="small" type="primary" @click="emit('create')">+ Create</NButton>
      <span v-if="!selected.namespaced" class="ns-note">Cluster-scoped</span>
      <NPopover v-if="tableCols.length > 0" trigger="click" placement="bottom-end">
        <template #trigger>
          <NButton size="small">
            Columns ▾
            <!-- The count is on the TRIGGER, not only inside the popover: a column that
                 disappears silently reads as a missing feature, and the picker is where the
                 answer is. Saying how many were dropped is what makes it a choice (#238). -->
            <span v-if="autoHiddenCols.size > 0" class="cols-badge">{{ autoHiddenCols.size }}</span>
          </NButton>
        </template>
        <div class="cols-pop">
          <div v-if="autoHiddenCols.size > 0" class="cols-note">
            {{ autoHiddenCols.size }} hidden to fit this width. Check one to keep it — the table will scroll instead.
          </div>
          <NCheckbox
            v-for="c in tableCols"
            :key="c.key"
            :checked="!hiddenCols.has(c.key) && !autoHiddenCols.has(c.key)"
            @update:checked="emit('toggle-col', c.key)"
          >
            {{ c.header }}
            <span v-if="autoHiddenCols.has(c.key)" class="cols-why">too narrow</span>
            <span v-else-if="keptCols.has(c.key)" class="cols-why">kept</span>
          </NCheckbox>
        </div>
      </NPopover>
    </div>
    <!-- The states actually present, each a click away from being the filter (GH#341).
         Its OWN row, not another item in `.content-head`. #331 measured that row at the narrow
         end and found it needs 585.01px of a 689px content column with nothing else in it — a
         rail of four or five chips is 300px more, so putting it there would re-open exactly the
         defect that ticket closed, on the two pills it had just pinned. A wrapping row of its
         own costs one line of height and cannot squeeze anything.
         A button, not a span: it is the same click a link would be, it is reachable by keyboard
         and it says whether it is on. `aria-pressed` carries that to a screen reader, and the
         `.on` class carries it to everyone else with a border rather than only a colour. -->
    <div v-if="chips.length > 0" class="status-rail">
      <button
        v-for="c in chips"
        :key="c.label"
        type="button"
        class="status-chip"
        :class="[`tone-${c.tone}`, { on: c.active }]"
        :aria-pressed="c.active"
        :title="c.active ? `Showing only ${c.label} — click to clear` : `Show only ${c.label}`"
        @click="emit('update:query', c.query)"
      >
        <span class="status-chip-label">{{ c.label }}</span>
        <span class="status-chip-count">{{ c.count }}</span>
      </button>
    </div>
    <!-- A pattern that did not compile. NOT an ErrorNotice: nothing failed to load, its Retry
         would re-run a fetch that is fine, and the list below is complete rather than empty.
         It has to be said out loud all the same — silently showing every row would read as a
         filter that does nothing, and silently showing none would blame the cluster (#306). -->
    <div v-if="filter.error" class="filter-error">
      <span class="filter-error-msg">{{ filter.error }}</span>
      <!-- `filtered`, not `objects`: with a Helm scope on, the rows still on screen are the
           scoped ones, and the filter is the only narrowing that was dropped. -->
      <span class="filter-error-note">Showing all {{ filtered.length }} rows — the filter was not applied.</span>
    </div>
    <div v-if="selection.size > 0" class="bulk-bar">
      <span>{{ selection.size }} selected</span>
      <NButton size="small" type="error" :disabled="bulkDeleteAccess.disabled" @click="emit('bulk-delete')">
        Delete
      </NButton>
      <NButton size="small" @click="emit('clear-selection')">Clear</NButton>
      <!-- Why the button is dead, next to the button. Rendered rather than hovered: a
           disabled control with no sentence beside it is indistinguishable from a bug. -->
      <span v-if="bulkDeleteAccess.reason" class="bulk-denied">{{ bulkDeleteAccess.reason }}</span>
    </div>
    <ResourceTable
      class="list-table"
      :objects="filtered"
      :columns="visibleCols"
      :namespaced="selected.namespaced"
      :loading="loading"
      :selected-key="selectedKey"
      :selection="selection"
      :fetch-children="fetchChildren"
      :kept-cols="keptCols"
      :empty-copy="emptyCopy"
      :access="access"
      @auto-hidden="(k) => emit('auto-hidden', k)"
      @update:selection="(k) => emit('update:selection', k)"
      @open="(o) => emit('open', o)"
      @namespace-click="(ns) => emit('namespace-click', ns)"
      @row-action="(a, o, c) => emit('row-action', a, o, c)"
    />
  </div>
</template>

<style scoped>
/* ---- The status chips (GH#341) ------------------------------------------------------------
   Wraps rather than shrinks. Every chip is `flex: 0 0 auto` for #331's reason — a flex item's
   automatic minimum is its MIN-CONTENT, which for `CrashLoopBackOff 1` is the longest word, so
   a squeezed rail would silently stack the count under the label and turn a pill into a
   two-line box. Here there is somewhere for the overflow to go (the next line), which is why
   this row can hold an unbounded number of chips where `.content-head` could not hold one. */
.status-rail {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0 0 12px;
}

/* A `<button>` reset first: the browser's own chrome would fight every rule below. */
.status-chip {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  font: inherit;
  font-size: 12px;
  line-height: 1.6;
  padding: 1px 9px;
  border-radius: 11px;
  /* Transparent rather than absent, so turning a chip on changes a colour and not the layout —
     a rail that reflows under the pointer on every click is how a mis-click happens. */
  border: 1px solid transparent;
  background: var(--subtle-bg);
  color: var(--text);
  cursor: pointer;
}

/* Tone comes from the semantic tokens, which carry a designed dark value each (see styles.css)
   — the alternative, a literal per tone, is how `.chip` shipped at 1.75:1 in dark (#260). The
   tint composites over whatever panel it lands on. `idle` deliberately has no colour at all:
   finished and scaled-to-zero are neither healthy nor broken, and the overview mutes them for
   the same reason.

   The FOREGROUND is the token mixed toward the theme's own text, not the token itself. Measured
   here first: plain `var(--warn-fg)` on `var(--warn-tint)` read 4.19:1 in LIGHT — under AA, and
   passing at 8.11:1 in dark, which is the exact shape of a one-theme colour defect. That pair
   is a known bad one in this file already (`.filter-error` carries the same measurement at
   4.46:1 and answers it with `--text`), and it is a bad one because a state's `fg` was designed
   to read on the PANEL, not on its own tint — the mistake the nav, the palette and the drawer's
   Naive tag each made (3.80 / 3.02 / 3.36:1).
   `--text` is not the answer here the way it is for `.filter-error`, because there the tone is
   carried by a border and a wash and here the colour IS the tone: six chips of one grey say
   nothing. The mix is the construction `--bar-link` and `.chip` use — derive from the surface
   rather than re-pick a literal per theme — and it moves each tone toward black in light and
   toward white in dark, which is the direction that gains contrast in both. 65% keeps the hue
   plainly readable as green / amber / red; measured after it, ok/warn/err/idle read
   6.92 / 6.19 / 7.54 / 12.93:1 in light and 8.52 / 8.57 / 7.71 / 9.98:1 in dark, and an ACTIVE
   chip (the one whose border is on) 7.54:1 light / 7.71:1 dark. */
.status-chip.tone-ok {
  background: var(--ok-tint);
  color: color-mix(in srgb, var(--ok-fg) 65%, var(--text));
}
.status-chip.tone-warn {
  background: var(--warn-tint);
  color: color-mix(in srgb, var(--warn-fg) 65%, var(--text));
}
.status-chip.tone-err {
  background: var(--danger-tint);
  color: color-mix(in srgb, var(--danger-fg) 65%, var(--text));
}

/* On. `currentColor` rather than the accent: the border belongs to the state the chip names,
   and an accent ring would read as a fourth tone. Weight and border together, because colour
   alone is not a signal for everyone. */
.status-chip.on {
  border-color: currentcolor;
  font-weight: 600;
}

.status-chip:hover {
  border-color: currentcolor;
}

/* The count is the claim being made — "click and see this many" — so it is neither muted into
   secondary text nor faded with an `opacity`, which would quietly undo the contrast the tone
   tokens were measured for. Tabular figures so a rail of counts does not jitter under a watch. */
.status-chip-count {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

/* The filter-syntax popover. Same colour reasoning as `.cols-note` below: inside a Naive
   popover the panel is NOT the app's panel, so `--muted` measures under AA on its dark
   surface. `--text` for everything, hierarchy carried by size and weight. */
.filter-help {
  max-width: 460px;
  display: flex;
  flex-direction: column;
  gap: 5px;
  color: var(--text);
  font-size: 12px;
  line-height: 1.5;
}
.filter-help-title {
  font-weight: 600;
  padding-bottom: 3px;
  border-bottom: 1px solid var(--border);
}
.filter-help-row {
  display: grid;
  grid-template-columns: 150px 1fr;
  gap: 10px;
  align-items: baseline;
}
/* `justify-self: start` so the chip hugs its example. Stretched across the whole 150px grid
   column, a filled box with left-aligned monospace in it reads as an empty text INPUT — the
   first capture of this popover had eleven of them stacked above a real search box. */
.filter-help-row code {
  justify-self: start;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  padding: 1px 4px;
  border-radius: 3px;
  background: var(--subtle-bg);
  color: var(--text);
  white-space: nowrap;
  overflow-x: auto;
}
.filter-help-note {
  margin: 0;
  padding-top: 4px;
  border-top: 1px solid var(--border);
  color: var(--text);
  font-size: 11px;
}
.filter-help-btn {
  font-weight: 700;
}

/* A refused pattern, on the warn palette rather than the danger one: nothing broke, the
   query is simply not usable yet — and the row underneath says what is on screen instead. */
.filter-error {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  align-items: baseline;
  margin: 0 12px 6px;
  padding: 6px 10px;
  border: 1px solid var(--warn-line);
  border-radius: 4px;
  background: var(--warn-wash);
  font-size: 12px;
}
/* `--text`, not `--warn-fg`. Measured: #a35b00 on this box's own composited fill
   (rgb(242,238,225)) is 4.46:1 in light — under AA, and passing at 9.67:1 in dark, which is
   exactly the shape of a one-theme colour defect. The warn tone is carried by the border and
   the wash; weight carries the hierarchy. */
.filter-error-msg {
  color: var(--text);
  font-weight: 600;
}
.filter-error-note {
  color: var(--text);
}

.cols-pop {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 320px;
  overflow: auto;
}

/* Why a column is not on screen, said in the one place that can put it back (#238).
   `--text` rather than `--muted`, though both of these are secondary text: a popover is NOT
   the panel, and Naive paints its dark one at rgb(72,72,78), where `--muted` measured
   3.63:1 — under AA, and passing in light mode at 5.95:1, which is exactly how a
   dark-mode-only colour defect ships. Size carries the hierarchy instead. */
.cols-note {
  max-width: 260px;
  color: var(--text);
  font-size: 12px;
  line-height: 1.5;
  padding-bottom: 2px;
  border-bottom: 1px solid var(--border);
}

.cols-why {
  color: var(--text);
  font-size: 11px;
  margin-left: 6px;
}

.cols-badge {
  margin-left: 5px;
  padding: 0 5px;
  border-radius: 8px;
  background: var(--border);
  color: var(--text);
  font-size: 11px;
  line-height: 1.5;
}

/* Fill the content area and let the table's OWN body scroll (NDataTable flex-height), so
   the header + column titles stay pinned while a long list scrolls, instead of the whole
   page scrolling the header off-screen. The flex-height table must be the flex:1 child of
   a flex-column parent with a definite height (Naive's recipe). */
.list-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.list-table {
  flex: 1;
  min-height: 0;
}
</style>

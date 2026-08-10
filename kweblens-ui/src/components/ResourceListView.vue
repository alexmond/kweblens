<script setup lang="ts">
import { NButton, NCheckbox, NInput, NPopover } from 'naive-ui';
import { computed } from 'vue';

import { resourceListEmpty } from '../emptyState';
import { activeQuery, FILTER_HELP, FILTER_HELP_NOTES, parseFilter } from '../objectFilter';
import type { RowAction } from '../rowActions';
import { listCountLabel } from '../shell';
import type { TableColumn } from '../table';
import type { KubeObject, NavItem } from '../types';
import ResourceTable from './ResourceTable.vue';

// The resource-list surface: header (search, create, columns), bulk bar, and the table.
// Emits: update:query, toggle-col(key), clear-selection, bulk-delete, update:selection(keys),
//        open(obj), namespace-click(ns), create, row-action(action,obj,container?)
const props = defineProps<{
  selected: NavItem;
  filtered: KubeObject[];
  objects: KubeObject[];
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
      <NButton size="small" type="error" @click="emit('bulk-delete')">Delete</NButton>
      <NButton size="small" @click="emit('clear-selection')">Clear</NButton>
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
      @auto-hidden="(k) => emit('auto-hidden', k)"
      @update:selection="(k) => emit('update:selection', k)"
      @open="(o) => emit('open', o)"
      @namespace-click="(ns) => emit('namespace-click', ns)"
      @row-action="(a, o, c) => emit('row-action', a, o, c)"
    />
  </div>
</template>

<style scoped>
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

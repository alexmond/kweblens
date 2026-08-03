<script setup lang="ts">
import { NButton, NCheckbox, NInput, NPopover } from 'naive-ui';

import type { RowAction } from '../rowActions';
import type { TableColumn } from '../table';
import type { KubeObject, NavItem } from '../types';
import ResourceTable from './ResourceTable.vue';

// The resource-list surface: header (search, create, columns), bulk bar, and the table.
// Emits: update:query, toggle-col(key), clear-selection, bulk-delete, update:selection(keys),
//        open(obj), namespace-click(ns), create, row-action(action,obj,container?)
defineProps<{
  selected: NavItem;
  filtered: KubeObject[];
  objects: KubeObject[];
  query: string;
  live: boolean;
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
</script>

<template>
  <div class="list-view">
    <div class="content-head">
      <h1>{{ selected.label }}</h1>
      <span class="count">{{ query ? `${filtered.length} of ${objects.length}` : `${objects.length} items` }}</span>
      <span v-if="live" class="live" title="Live-updating (SSE watch)"><span class="dot" /> live</span>
      <NInput
        :value="query"
        size="small"
        clearable
        :placeholder="`Search ${selected.label}…`"
        style="width: 220px"
        @update:value="(v) => emit('update:query', v)"
      />
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
      @auto-hidden="(k) => emit('auto-hidden', k)"
      @update:selection="(k) => emit('update:selection', k)"
      @open="(o) => emit('open', o)"
      @namespace-click="(ns) => emit('namespace-click', ns)"
      @row-action="(a, o, c) => emit('row-action', a, o, c)"
    />
  </div>
</template>

<style scoped>
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

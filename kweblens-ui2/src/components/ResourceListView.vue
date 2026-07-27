<script setup lang="ts">
import type { RowAction } from '../rowActions';
import type { TableColumn } from '../table';
import type { KubeObject, NavItem } from '../types';
import ResourceTable from './ResourceTable.vue';

// The resource-list surface: header (search, create, columns), bulk bar, and the table.
// Emits: update:query, toggle-col(key), clear-selection, bulk-delete, toggle-row(key),
//        toggle-all(keys), open(obj), namespace-click(ns), create, row-action(action,obj,container?)
defineProps<{
  selected: NavItem;
  filtered: KubeObject[];
  objects: KubeObject[];
  query: string;
  live: boolean;
  tableCols: TableColumn[];
  visibleCols: TableColumn[];
  hiddenCols: Set<string>;
  selection: Set<string>;
  selectedKey: string | null;
  loading: boolean;
  fetchChildren?: (obj: KubeObject) => Promise<KubeObject[]>;
}>();
const emit = defineEmits<{
  (e: 'update:query', v: string): void;
  (e: 'toggle-col', key: string): void;
  (e: 'clear-selection'): void;
  (e: 'bulk-delete'): void;
  (e: 'toggle-row', key: string): void;
  (e: 'toggle-all', keys: string[]): void;
  (e: 'open', obj: KubeObject): void;
  (e: 'namespace-click', ns: string): void;
  (e: 'create'): void;
  (e: 'row-action', action: RowAction, obj: KubeObject, container?: string): void;
}>();
</script>

<template>
  <div class="content-head">
    <h1>{{ selected.label }}</h1>
    <span class="count">{{ query ? `${filtered.length} of ${objects.length}` : `${objects.length} items` }}</span>
    <span v-if="live" class="live" title="Live-updating (SSE watch)"><span class="dot" /> live</span>
    <input
      class="search"
      type="search"
      :placeholder="`Search ${selected.label}…`"
      :value="query"
      @input="emit('update:query', ($event.target as HTMLInputElement).value)"
    />
    <div class="spacer" />
    <button class="btn create-btn" @click="emit('create')">+ Create</button>
    <span v-if="!selected.namespaced" class="ns-note">Cluster-scoped</span>
    <details v-if="tableCols.length > 0" class="cols-menu">
      <summary>Columns ▾</summary>
      <ul>
        <li v-for="c in tableCols" :key="c.key">
          <label class="col-toggle">
            <input type="checkbox" :checked="!hiddenCols.has(c.key)" @change="emit('toggle-col', c.key)" />
            {{ c.header }}
          </label>
        </li>
      </ul>
    </details>
  </div>
  <div v-if="selection.size > 0" class="bulk-bar">
    <span>{{ selection.size }} selected</span>
    <button class="btn danger" @click="emit('bulk-delete')">Delete</button>
    <button class="btn" @click="emit('clear-selection')">Clear</button>
  </div>
  <ResourceTable
    :objects="filtered"
    :columns="visibleCols"
    :namespaced="selected.namespaced"
    :loading="loading"
    :selected-key="selectedKey"
    :selection="selection"
    :fetch-children="fetchChildren"
    @toggle-row="(k) => emit('toggle-row', k)"
    @toggle-all="(k) => emit('toggle-all', k)"
    @open="(o) => emit('open', o)"
    @namespace-click="(ns) => emit('namespace-click', ns)"
    @row-action="(a, o, c) => emit('row-action', a, o, c)"
  />
</template>

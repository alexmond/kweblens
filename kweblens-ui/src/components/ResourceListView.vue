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
  (e: 'update:selection', keys: string[]): void;
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
      <template #trigger><NButton size="small">Columns ▾</NButton></template>
      <div class="cols-pop">
        <NCheckbox
          v-for="c in tableCols"
          :key="c.key"
          :checked="!hiddenCols.has(c.key)"
          @update:checked="emit('toggle-col', c.key)"
        >
          {{ c.header }}
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
    :objects="filtered"
    :columns="visibleCols"
    :namespaced="selected.namespaced"
    :loading="loading"
    :selected-key="selectedKey"
    :selection="selection"
    :fetch-children="fetchChildren"
    @update:selection="(k) => emit('update:selection', k)"
    @open="(o) => emit('open', o)"
    @namespace-click="(ns) => emit('namespace-click', ns)"
    @row-action="(a, o, c) => emit('row-action', a, o, c)"
  />
</template>

<style scoped>
.cols-pop {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 320px;
  overflow: auto;
}
</style>

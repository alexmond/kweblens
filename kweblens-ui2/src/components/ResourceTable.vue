<script setup lang="ts">
import { computed, ref } from 'vue';

import { age } from '../columns';
import { containerNames, objKey, objName, objNs } from '../kube';
import type { RowAction } from '../rowActions';
import type { TableColumn } from '../table';
import type { KubeObject } from '../types';
import ContainerSquares from './ContainerSquares.vue';
import ResourceCell from './ResourceCell.vue';
import RowMenu from './RowMenu.vue';
import StatusBadge from './StatusBadge.vue';

// Emits: toggle-row(key), toggle-all(keys), open(obj), namespace-click(ns), row-action(action, obj, container?)
const props = defineProps<{
  objects: KubeObject[];
  columns: TableColumn[];
  namespaced: boolean;
  loading: boolean;
  selectedKey: string | null;
  selection: Set<string>;
  fetchChildren?: (obj: KubeObject) => Promise<KubeObject[]>;
}>();
const emit = defineEmits<{
  (e: 'toggle-row', key: string): void;
  (e: 'toggle-all', keys: string[]): void;
  (e: 'open', obj: KubeObject): void;
  (e: 'namespace-click', ns: string): void;
  (e: 'row-action', action: RowAction, obj: KubeObject, container?: string): void;
}>();

const sort = ref<{ key: string; dir: number }>({ key: 'name', dir: 1 });
const expanded = ref<Set<string>>(new Set());
const children = ref<Record<string, KubeObject[] | null>>({});

const showNs = computed(() => props.namespaced && props.objects.some((o) => objNs(o)));
// Some CRD printer columns already include an Age column; don't render ours twice.
const showAge = computed(() => !props.columns.some((c) => c.header.toLowerCase() === 'age'));

const headerCols = computed(() => [
  { key: 'name', header: 'Name' },
  ...(showNs.value ? [{ key: 'namespace', header: 'Namespace' }] : []),
  ...props.columns.map((c) => ({ key: c.key, header: c.header })),
  ...(showAge.value ? [{ key: 'age', header: 'Age' }] : []),
]);

const textValue = (o: KubeObject, key: string): string => {
  if (key === 'name') {
    return objName(o);
  }
  if (key === 'namespace') {
    return objNs(o) ?? '';
  }
  const c = props.columns.find((x) => x.key === key);
  if (!c) {
    return '';
  }
  return c.sortText ? c.sortText(o) : c.render(o);
};

const sorted = computed(() =>
  [...props.objects].sort((a, b) => {
    if (sort.value.key === 'age') {
      const ta = Date.parse(a.metadata?.creationTimestamp ?? '') || 0;
      const tb = Date.parse(b.metadata?.creationTimestamp ?? '') || 0;
      return (ta - tb) * sort.value.dir;
    }
    return (
      textValue(a, sort.value.key).localeCompare(textValue(b, sort.value.key), undefined, { numeric: true }) *
      sort.value.dir
    );
  }),
);
const clickHeader = (key: string) => {
  sort.value = sort.value.key === key ? { key, dir: -sort.value.dir } : { key, dir: 1 };
};

const sortedKeys = computed(() => sorted.value.map(objKey));
const allSelected = computed(
  () => sortedKeys.value.length > 0 && sortedKeys.value.every((k) => props.selection.has(k)),
);
const totalCols = computed(() => 1 + headerCols.value.length + 1);

const toggleExpand = (o: KubeObject) => {
  const k = objKey(o);
  const next = new Set(expanded.value);
  if (next.has(k)) {
    next.delete(k);
  } else {
    next.add(k);
    if (children.value[k] === undefined && props.fetchChildren) {
      children.value = { ...children.value, [k]: null };
      props.fetchChildren(o).then(
        (kids) => (children.value = { ...children.value, [k]: kids }),
        () => (children.value = { ...children.value, [k]: [] }),
      );
    }
  }
  expanded.value = next;
};

const childrenOf = (o: KubeObject): KubeObject[] | null => children.value[objKey(o)] ?? null;
const rowClass = (o: KubeObject): string =>
  (objKey(o) === props.selectedKey ? 'row-active' : '') + (props.selection.has(objKey(o)) ? ' row-checked' : '');
const isSuspended = (o: KubeObject): boolean => Boolean((o.spec as Record<string, unknown>)?.suspend);
const childInfo = (p: KubeObject) => {
  const st = (p.status as Record<string, unknown>) ?? {};
  const cs = (st.containerStatuses as Record<string, unknown>[]) ?? [];
  return {
    restarts: cs.reduce((n, c) => n + Number(c.restartCount ?? 0), 0),
    phase: String(st.phase ?? ''),
    node: String((p.spec as Record<string, unknown>)?.nodeName ?? ''),
  };
};
</script>

<template>
  <div v-if="loading" class="empty">Loading…</div>
  <div v-else-if="objects.length === 0" class="empty">No resources.</div>
  <table v-else class="grid clickable">
    <thead>
      <tr>
        <th class="chk"><input type="checkbox" :checked="allSelected" @change="emit('toggle-all', sortedKeys)" /></th>
        <th v-for="h in headerCols" :key="h.key" class="sortable" @click="clickHeader(h.key)">
          {{ h.header }}<span v-if="sort.key === h.key" class="sort-ind">{{ sort.dir === 1 ? ' ▲' : ' ▼' }}</span>
        </th>
        <th class="rowmenu-cell" />
      </tr>
    </thead>
    <tbody>
      <template v-for="o in sorted" :key="objKey(o)">
        <tr :class="rowClass(o)" @click="emit('open', o)">
          <td class="chk" @click.stop>
            <input type="checkbox" :checked="selection.has(objKey(o))" @change="emit('toggle-row', objKey(o))" />
          </td>
          <td class="name">
            <button
              v-if="fetchChildren"
              class="tree-toggle"
              :title="expanded.has(objKey(o)) ? 'Collapse' : 'Show pods'"
              @click.stop="toggleExpand(o)"
            >
              {{ expanded.has(objKey(o)) ? '▾' : '▸' }}
            </button>
            {{ objName(o) }}
          </td>
          <td v-if="showNs">
            <button v-if="objNs(o)" class="cell-link" @click.stop="emit('namespace-click', objNs(o) as string)">
              {{ objNs(o) }}
            </button>
            <template v-else>—</template>
          </td>
          <td v-for="c in columns" :key="c.key"><ResourceCell :col="c" :obj="o" /></td>
          <td v-if="showAge">{{ age(o.metadata?.creationTimestamp) }}</td>
          <td class="rowmenu-cell" @click.stop>
            <RowMenu
              :kind="o.kind ?? ''"
              :suspended="isSuspended(o)"
              :containers="containerNames(o)"
              @action="(a, c) => emit('row-action', a, o, c)"
            />
          </td>
        </tr>
        <template v-if="expanded.has(objKey(o))">
          <tr v-if="childrenOf(o) === null" class="child-row">
            <td :colspan="totalCols" class="child-msg">Loading pods…</td>
          </tr>
          <tr v-else-if="childrenOf(o)!.length === 0" class="child-row">
            <td :colspan="totalCols" class="child-msg">No pods.</td>
          </tr>
          <tr
            v-for="p in childrenOf(o) || []"
            v-else
            :key="objKey(o) + '>' + objKey(p)"
            class="child-row"
            @click="emit('open', p)"
          >
            <td :colspan="totalCols">
              <div class="child-pod">
                <span class="child-name">↳ {{ objName(p) }}</span>
                <ContainerSquares :obj="p" />
                <StatusBadge v-if="childInfo(p).phase" :text="childInfo(p).phase" />
                <span class="dim">↻ {{ childInfo(p).restarts }}</span>
                <span v-if="childInfo(p).node" class="dim">{{ childInfo(p).node }}</span>
                <span class="dim">{{ age(p.metadata?.creationTimestamp) }}</span>
              </div>
            </td>
          </tr>
        </template>
      </template>
    </tbody>
  </table>
</template>

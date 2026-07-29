<script setup lang="ts">
import { NButton, NDataTable, NDropdown } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { shallowRef, computed, h } from 'vue';

import { age } from '../columns';
import { containerNames, objKey, objName, objNs } from '../kube';
import type { RowAction } from '../rowActions';
import { ROW_ACTIONS } from '../rowActions';
import type { CellSpec, TableColumn } from '../table';
import { toneFor } from '../table';
import type { KubeObject } from '../types';
import ContainerSquares from './ContainerSquares.vue';
import StatusBadge from './StatusBadge.vue';
import UsageBar from './UsageBar.vue';

// Naive UI NDataTable replaces the hand-rolled table: sort, multi-select (checked-row-keys),
// and row-expansion are built-in props; the row kebab is an NDropdown.
// Emits: update:selection(keys), open(obj), namespace-click(ns), row-action(action, obj, container?)
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
  (e: 'update:selection', keys: string[]): void;
  (e: 'open', obj: KubeObject): void;
  (e: 'namespace-click', ns: string): void;
  (e: 'row-action', action: RowAction, obj: KubeObject, container?: string): void;
}>();

const showNs = computed(() => props.namespaced && props.objects.some((o) => objNs(o)));
const showAge = computed(() => !props.columns.some((c) => c.header.toLowerCase() === 'age'));

const renderCell = (spec: CellSpec, row: KubeObject) => {
  if (spec.type === 'usagebar') {
    return h(UsageBar, { fraction: spec.fraction, color: spec.color, text: spec.text });
  }
  if (spec.type === 'containers') {
    return h(ContainerSquares, { obj: row });
  }
  return spec.tone ? h(StatusBadge, { text: spec.text }) : spec.text;
};

const menuOptions = (row: KubeObject) => {
  const ctx = { kind: row.kind ?? '', suspended: Boolean((row.spec as Record<string, unknown>)?.suspend) };
  const containers = containerNames(row);
  const apply = ROW_ACTIONS.filter((a) => a.applies(ctx));
  const toOpt = (a: (typeof ROW_ACTIONS)[number]) =>
    a.containerScoped && containers.length > 1
      ? { label: a.label, key: a.id, children: containers.map((c) => ({ label: c, key: `${a.id}::${c}` })) }
      : { label: a.label, key: a.id, props: { class: a.danger ? 'menu-danger' : '' } };
  const main = apply.filter((a) => a.section === 'main').map(toOpt);
  const life = apply.filter((a) => a.section === 'lifecycle').map(toOpt);
  return main.length && life.length ? [...main, { type: 'divider', key: 'd' }, ...life] : [...main, ...life];
};
const onMenu = (key: string, row: KubeObject) => {
  const [id, container] = key.split('::');
  emit('row-action', id as RowAction, row, container);
};

const columns = computed<DataTableColumns<KubeObject>>(() => {
  const cols: DataTableColumns<KubeObject> = [{ type: 'selection' }];
  // Name is the tree column: when fetchChildren is set, Naive renders the expand
  // arrow + indent here, so child pods align under this column and every other one.
  cols.push({ title: 'Name', key: 'name', sorter: 'default', render: (row) => objName(row) });
  if (showNs.value) {
    cols.push({
      title: 'Namespace',
      key: 'namespace',
      sorter: (a, b) => (objNs(a) ?? '').localeCompare(objNs(b) ?? ''),
      render: (row) =>
        objNs(row)
          ? h(
              'a',
              {
                class: 'cell-link',
                onClick: (e: MouseEvent) => {
                  e.stopPropagation();
                  emit('namespace-click', objNs(row) as string);
                },
              },
              objNs(row),
            )
          : '—',
    });
  }
  props.columns.forEach((c) => {
    cols.push({
      title: c.header,
      key: c.key,
      sorter: (a, b) => sortVal(a, c).localeCompare(sortVal(b, c), undefined, { numeric: true }),
      render: (row) =>
        c.cell
          ? renderCell(c.cell(row), row)
          : renderCell({ type: 'text', text: c.render(row), tone: toneFor(c.key, c.render(row)) }, row),
    });
  });
  if (showAge.value) {
    cols.push({
      title: 'Age',
      key: 'age',
      sorter: (a, b) =>
        (Date.parse(a.metadata?.creationTimestamp ?? '') || 0) - (Date.parse(b.metadata?.creationTimestamp ?? '') || 0),
      render: (row) => age(row.metadata?.creationTimestamp),
    });
  }
  cols.push({
    title: '',
    key: '_menu',
    width: 44,
    // Stop the click bubbling to the row (which opens the detail drawer), so the kebab
    // dropdown actually opens instead of being pre-empted / overlapped by the drawer.
    render: (row) =>
      h('div', { onClick: (e: MouseEvent) => e.stopPropagation() }, [
        h(
          NDropdown,
          { trigger: 'click', options: menuOptions(row), onSelect: (k: string) => onMenu(k, row) },
          { default: () => h(NButton, { text: true, size: 'small' }, () => '⋮') },
        ),
      ]),
  });
  return cols;
});

const sortVal = (o: KubeObject, c: TableColumn): string => (c.sortText ? c.sortText(o) : c.render(o));

// Tree data: expandable workloads carry their child pods as real rows so they line up
// under every column and are individually clickable. Pods are lazy-loaded on expand and
// kept in childCache (keyed by objKey) so a live-refresh of `objects` doesn't drop them.
type TreeRow = KubeObject & { children?: KubeObject[]; isLeaf?: boolean };
const childCache = shallowRef<Record<string, KubeObject[]>>({});
const treeData = computed<TreeRow[]>(() => {
  if (!props.fetchChildren) {
    return props.objects;
  }
  return props.objects.map((o) => {
    const kids = childCache.value[objKey(o)];
    const row: TreeRow = { ...o, isLeaf: false };
    if (kids && kids.length) {
      row.children = kids;
    }
    return row;
  });
});
// Naive awaits this promise to clear the row's loading spinner; populate the cache so the
// computed re-derives `children`. Keyed by objKey → Naive won't re-fire it after a refresh.
const onLoad = (row: KubeObject) =>
  props.fetchChildren
    ? props.fetchChildren(row).then((pods) => {
        childCache.value = { ...childCache.value, [objKey(row)]: pods };
      })
    : Promise.resolve();

const checkedKeys = computed(() => [...props.selection]);
const rowKey = (row: KubeObject) => objKey(row);
const rowProps = (row: KubeObject) => ({
  class: objKey(row) === props.selectedKey ? 'row-active' : '',
  style: 'cursor: pointer',
  // Open the detail drawer on row click — but not when the click lands on an interactive
  // control (checkbox, expand toggle, the kebab menu, or a namespace link).
  onClick: (e: MouseEvent) => {
    const t = e.target as HTMLElement;
    if (t.closest('.n-checkbox, .n-data-table-expand-trigger, button, a, .n-dropdown')) {
      return;
    }
    emit('open', row);
  },
});
</script>

<template>
  <NDataTable
    :columns="columns"
    :data="treeData"
    :loading="loading"
    :row-key="rowKey"
    :checked-row-keys="checkedKeys"
    :row-props="rowProps"
    :on-load="fetchChildren ? onLoad : undefined"
    :indent="18"
    :cascade="false"
    flex-height
    size="small"
    @update:checked-row-keys="(keys) => emit('update:selection', keys as string[])"
  />
</template>

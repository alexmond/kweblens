<script setup lang="ts">
import { NButton, NDataTable, NDropdown } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { shallowRef, computed, h } from 'vue';

import { age } from '../columns';
import { containerNames, objKey, objName, objNs } from '../kube';
import type { RowAction } from '../rowActions';
import { ALL_CONTAINERS, ROW_ACTIONS } from '../rowActions';
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
  return spec.tone ? h(StatusBadge, { text: spec.text, tone: spec.tone }) : spec.text;
};

const menuOptions = (row: KubeObject) => {
  const ctx = { kind: row.kind ?? '', suspended: Boolean((row.spec as Record<string, unknown>)?.suspend) };
  const containers = containerNames(row);
  const apply = ROW_ACTIONS.filter((a) => a.applies(ctx));
  const toOpt = (a: (typeof ROW_ACTIONS)[number]) => {
    if (!a.containerScoped || containers.length <= 1) {
      return { label: a.label, key: a.id, props: { class: a.danger ? 'menu-danger' : '' } };
    }
    // Logs can span containers, so it leads with "All containers"; a shell or attach can
    // only ever target one, so those stay a plain container list.
    const children = containers.map((c) => ({ label: c, key: `${a.id}::${c}` }));
    if (a.id === 'logs') {
      children.unshift({ label: 'All containers', key: `${a.id}::${ALL_CONTAINERS}` });
    }
    return { label: a.label, key: a.id, children };
  };
  const main = apply.filter((a) => a.section === 'main').map(toOpt);
  const life = apply.filter((a) => a.section === 'lifecycle').map(toOpt);
  return main.length && life.length ? [...main, { type: 'divider', key: 'd' }, ...life] : [...main, ...life];
};
const onMenu = (key: string, row: KubeObject) => {
  const [id, container] = key.split('::');
  emit('row-action', id as RowAction, row, container);
};

// Column sizing. Without hints Naive spreads width evenly, so short columns (Taints "0",
// Age "7d") hog space while long names wrap over several lines. Give Name a generous
// min-width + ellipsis (truncate with a tooltip, never wrap), keep other columns above a
// readable floor, and let the table scroll horizontally when the total exceeds the viewport.
const NAME_MIN_WIDTH = 260;
const NS_MIN_WIDTH = 150;
const DATA_MIN_WIDTH = 110;
const AGE_WIDTH = 80;
const MENU_WIDTH = 44;
const SELECT_WIDTH = 40;
const EXPAND_EXTRA = 34;

const columns = computed<DataTableColumns<KubeObject>>(() => {
  const cols: DataTableColumns<KubeObject> = [{ type: 'selection', width: SELECT_WIDTH }];
  // Name is the tree column: when fetchChildren is set, Naive renders the expand
  // arrow + indent here, so child pods align under this column and every other one.
  cols.push({
    title: 'Name',
    key: 'name',
    sorter: 'default',
    // Explicit width (not minWidth): with scroll-x set, Naive honours widths — a bare
    // minWidth collapsed Name to ~130px and over-truncated names.
    width: NAME_MIN_WIDTH + (props.fetchChildren ? EXPAND_EXTRA : 0),
    ellipsis: { tooltip: true },
    render: (row) => objName(row),
  });
  if (showNs.value) {
    cols.push({
      title: 'Namespace',
      key: 'namespace',
      width: NS_MIN_WIDTH,
      ellipsis: { tooltip: true },
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
      // A column's own `width` (short values like Taints) wins; otherwise a readable floor.
      width: c.width ?? DATA_MIN_WIDTH,
      align: c.numeric ? 'right' : undefined,
      className: c.numeric ? 'kw-num' : undefined,
      ellipsis: { tooltip: true },
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
      width: AGE_WIDTH,
      sorter: (a, b) =>
        (Date.parse(a.metadata?.creationTimestamp ?? '') || 0) - (Date.parse(b.metadata?.creationTimestamp ?? '') || 0),
      render: (row) => age(row.metadata?.creationTimestamp),
    });
  }
  cols.push({
    title: '',
    key: '_menu',
    width: MENU_WIDTH,
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

/**
 * Row height NDataTable assumes when windowing. Measured, not guessed: a 300-row list
 * reported a 11,999px scrollHeight, i.e. ~40px a row.
 */
const ROW_HEIGHT = 40;

/**
 * Below this many rows, render the old way.
 *
 * <p>Windowing costs something — it fixes row height and re-renders on scroll — and a list
 * of eighty pods was never the problem. The measured problem (#215) is that the table put
 * one DOM row on the page per object: 300 objects meant 14,353 nodes and a 12,000px table,
 * which extrapolates to roughly 715,000 nodes at 15,000 objects. Above the threshold the
 * DOM cost becomes a constant instead.
 */
const VIRTUAL_FROM = 150;

// Tree rows are opt-in per kind (workload -> its pods). Naive windows the FLAT rendered
// list, so an expanded parent's children are windowed too — but the row-height assumption
// has to hold for children as well, and a child row is the same height as its parent here.
const virtual = computed(() => props.objects.length >= VIRTUAL_FROM);

// Total width the columns want. Handed to NDataTable as scroll-x so a wide column set
// scrolls horizontally instead of being squeezed (which is what forced text to wrap).
const scrollX = computed(() => {
  const dataWidth = props.columns.reduce((sum, c) => sum + (c.width ?? DATA_MIN_WIDTH), 0);
  return (
    SELECT_WIDTH +
    NAME_MIN_WIDTH +
    (props.fetchChildren ? EXPAND_EXTRA : 0) +
    (showNs.value ? NS_MIN_WIDTH : 0) +
    dataWidth +
    (showAge.value ? AGE_WIDTH : 0) +
    MENU_WIDTH
  );
});

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
    :scroll-x="scrollX"
    :virtual-scroll="virtual"
    :min-row-height="ROW_HEIGHT"
    flex-height
    size="small"
    @update:checked-row-keys="(keys) => emit('update:selection', keys as string[])"
  />
</template>

<script setup lang="ts">
import { NButton, NDataTable, NDropdown } from 'naive-ui';
import type { DataTableColumns, DropdownOption } from 'naive-ui';
import { shallowRef, computed, h, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { COL_WIDTH, autoHiddenCols, chromeWidth, tableWidth } from '../columnFit';
import { age } from '../columns';
import { objKey, objName, objNs } from '../kube';
import type { RowAction } from '../rowActions';
import { parseRowActionKey, rowActionOptions } from '../rowActions';
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
  /** Columns the Columns picker pinned: never taken away by width (#238). */
  keptCols?: Set<string>;
}>();
const emit = defineEmits<{
  (e: 'update:selection', keys: string[]): void;
  (e: 'open', obj: KubeObject): void;
  (e: 'namespace-click', ns: string): void;
  (e: 'row-action', action: RowAction, obj: KubeObject, container?: string): void;
  /** Which columns the width took away, so the picker can say so. */
  (e: 'auto-hidden', keys: string[]): void;
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

// Which actions an object offers is decided once, in rowActions.ts, because the detail
// drawer renders the same menu (#233).
// Cast at the boundary: rowActions.ts stays framework-agnostic (so the projection is
// testable without a DOM), which means it describes the shape rather than importing
// Naive's `DropdownOption` union.
const menuOptions = (row: KubeObject) => rowActionOptions(row) as unknown as DropdownOption[];
const onMenu = (key: string, row: KubeObject) => {
  const { action, container } = parseRowActionKey(key);
  emit('row-action', action, row, container);
};

// Column sizing. Without hints Naive spreads width evenly, so short columns (Taints "0",
// Age "7d") hog space while long names wrap over several lines. Give Name a generous
// min-width + ellipsis (truncate with a tooltip, never wrap), keep other columns above a
// readable floor, and let the table scroll horizontally when the total exceeds the viewport.
// The widths themselves live in `columnFit.ts`, which is also what decides — from these
// same numbers — which columns fit the space the table actually has (#238).

// ---- How much room the table has (#238) ----
//
// A number, not a breakpoint: the fit depends on the kind's column set, and `columnFit.ts`
// says why at length. The measurement is a ResizeObserver on the table's own root, which is
// the container width and NOT the width of the columns: with `scroll-x` set, Naive keeps
// the root at the container's width and scrolls the inner table, so hiding a column cannot
// change what is measured and the observer cannot chase its own tail. `0` until the first
// layout, which `autoHiddenCols` reads as "no width known yet" and answers by hiding
// nothing — a table that blinks down to one column for a frame is not an improvement.
const tableRef = ref<{ $el?: HTMLElement } | null>(null);
const availableWidth = ref(0);
let observer: ResizeObserver | null = null;
onMounted(() => {
  const el = tableRef.value?.$el;
  if (!el || typeof ResizeObserver === 'undefined') {
    return;
  }
  observer = new ResizeObserver(([entry]) => {
    availableWidth.value = Math.round(entry.contentRect.width);
  });
  observer.observe(el);
  availableWidth.value = Math.round(el.getBoundingClientRect().width);
});
onBeforeUnmount(() => observer?.disconnect());

const chrome = computed(() =>
  chromeWidth({ expandable: !!props.fetchChildren, namespace: showNs.value, age: showAge.value }),
);

const autoHidden = computed(() =>
  autoHiddenCols(props.columns, {
    available: availableWidth.value,
    chrome: chrome.value,
    keep: props.keptCols,
  }),
);

/** The columns actually rendered: what the caller asked for, minus what does not fit. */
const fitCols = computed(() => props.columns.filter((c) => !autoHidden.value.has(c.key)));

// The picker is a different component and needs to say which columns the width took away.
// Emitted on change rather than exposed, so there is one owner of the set (the shell) and
// the table stays the only thing that knows how wide it is.
watch(autoHidden, (keys) => emit('auto-hidden', [...keys]), { immediate: true });

const columns = computed<DataTableColumns<KubeObject>>(() => {
  const cols: DataTableColumns<KubeObject> = [{ type: 'selection', width: COL_WIDTH.select }];
  // Name is the tree column: when fetchChildren is set, Naive renders the expand
  // arrow + indent here, so child pods align under this column and every other one.
  cols.push({
    title: 'Name',
    key: 'name',
    sorter: 'default',
    // Explicit width (not minWidth): with scroll-x set, Naive honours widths — a bare
    // minWidth collapsed Name to ~130px and over-truncated names.
    width: COL_WIDTH.name + (props.fetchChildren ? COL_WIDTH.expand : 0),
    ellipsis: { tooltip: true },
    render: (row) => objName(row),
  });
  if (showNs.value) {
    cols.push({
      title: 'Namespace',
      key: 'namespace',
      width: COL_WIDTH.namespace,
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
  fitCols.value.forEach((c) => {
    cols.push({
      title: c.header,
      key: c.key,
      // A column's own `width` (short values like Taints) wins; otherwise a readable floor.
      width: c.width ?? COL_WIDTH.data,
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
      width: COL_WIDTH.age,
      sorter: (a, b) =>
        (Date.parse(a.metadata?.creationTimestamp ?? '') || 0) - (Date.parse(b.metadata?.creationTimestamp ?? '') || 0),
      render: (row) => age(row.metadata?.creationTimestamp),
    });
  }
  cols.push({
    title: '',
    key: '_menu',
    width: COL_WIDTH.menu,
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
 * <p>The original problem (#215) was one DOM row per object: 300 objects meant 14,353 nodes
 * and a 12,000px table, extrapolating to ~715,000 nodes at 15,000 objects. Windowing makes
 * that a constant.
 *
 * <p><b>This was 150, on the stated assumption that "a list of eighty pods was never the
 * problem". Measured against the live cluster, that was wrong</b> — 88 pods blocked the main
 * thread for 1,519 ms, which is a visible freeze, while a 168-object Replica Sets list
 * blocked 687 ms precisely because it was over the threshold and windowed. `perf-sweep`, six
 * kinds, budget block&lt;1500 ms:
 *
 * <pre>
 *              rows            150          50
 *   Events      99 -&gt; 20   1707 ms      441 ms
 *   Pods        88 -&gt; 20   1519 ms      391 ms
 *   Deployments 60 -&gt; 20   1729 ms      295 ms
 *   ConfigMaps  99 -&gt; 20    953 ms      185 ms
 * </pre>
 *
 * Three of six were over budget at 150; none are at 50.
 *
 * <p>50 rather than the 30 that also passed (441 vs 462 ms — noise): at a 40px row a ~900px
 * viewport shows ~20 rows, so 50 is about two and a half screens. Past that most rows are
 * off-screen and windowing is paying for itself; below it, fixing the row height constrains
 * a list that was never expensive. The number is a screenful argument, not the first value
 * that passed.
 */
const VIRTUAL_FROM = 50;

// Tree rows are opt-in per kind (workload -> its pods). Naive windows the FLAT rendered
// list, so an expanded parent's children are windowed too — but the row-height assumption
// has to hold for children as well, and a child row is the same height as its parent here.
const virtual = computed(() => props.objects.length >= VIRTUAL_FROM);

// Total width the columns want. Handed to NDataTable as scroll-x so a wide column set
// scrolls horizontally instead of being squeezed (which is what forced text to wrap).
// Computed from the columns actually rendered, so hiding one for width genuinely returns
// its pixels — a scroll-x left at the full column set would keep the horizontal scrollbar
// #238 is about, with fewer columns behind it.
const scrollX = computed(() => tableWidth(fitCols.value, chrome.value));

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
    ref="tableRef"
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

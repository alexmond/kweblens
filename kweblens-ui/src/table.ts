import type { ColumnDef, StatusTone } from './columns';
import { readyTone, statusTone } from './columns';
import { gib, objKey, objName, parseCpuCores, parseMemBytes } from './kube';
import type { KubeObject, NodeDiskUsage, UsageSummary } from './types';

// The React table let a column's render() return a ReactNode (string OR a rich element like a
// usage bar / container squares). A .ts file can't hold JSX, so the table instead models the
// rich cells as DATA — a CellSpec the NDataTable column render fn switches on. Text columns
// (from columns.ts) render as before; only Pods/Nodes add the rich `cell`.

export type CellSpec =
  | { type: 'text'; text: string; tone?: StatusTone }
  | { type: 'usagebar'; fraction: number; color: string; text: string }
  | { type: 'containers' };

/** A table column: the shared ColumnDef plus an optional rich-cell descriptor. */
export interface TableColumn extends ColumnDef {
  cell?: (o: KubeObject) => CellSpec;
}

/** Tone for a text cell, keyed off the stable column key (not the display header). */
export function toneFor(key: string, text: string): StatusTone {
  if (key === 'status') {
    return statusTone(text);
  }
  if (key === 'ready') {
    return readyTone(text);
  }
  return '';
}

const alloc = (o: KubeObject): Record<string, string> =>
  ((o.status as Record<string, unknown>)?.allocatable as Record<string, string>) ?? {};

/** Node table: append live CPU / Memory / Disk usage-bar columns (metrics-server + node-exporter). */
function nodeUsageColumns(usage: Record<string, UsageSummary>, nodeDisk: Record<string, NodeDiskUsage>): TableColumn[] {
  return [
    {
      key: 'm-cpu',
      header: 'CPU',
      sortText: (o) => String(parseCpuCores(usage[objKey(o)]?.cpu)),
      render: (o) => {
        const u = usage[objKey(o)];
        if (!u) {
          return '—';
        }
        return `${parseCpuCores(u.cpu).toFixed(2)} / ${parseCpuCores(alloc(o).cpu).toFixed(2)}`;
      },
      cell: (o) => {
        const u = usage[objKey(o)];
        if (!u) {
          return { type: 'text', text: '—' };
        }
        const used = parseCpuCores(u.cpu);
        const total = parseCpuCores(alloc(o).cpu);
        return {
          type: 'usagebar',
          fraction: total ? used / total : 0,
          color: '#2e9e8f',
          text: `${used.toFixed(2)} / ${total.toFixed(2)}`,
        };
      },
    },
    {
      key: 'm-mem',
      header: 'Memory',
      sortText: (o) => String(parseMemBytes(usage[objKey(o)]?.memory)),
      render: (o) => {
        const u = usage[objKey(o)];
        if (!u) {
          return '—';
        }
        return `${gib(parseMemBytes(u.memory))} / ${gib(parseMemBytes(alloc(o).memory))}`;
      },
      cell: (o) => {
        const u = usage[objKey(o)];
        if (!u) {
          return { type: 'text', text: '—' };
        }
        const used = parseMemBytes(u.memory);
        const total = parseMemBytes(alloc(o).memory);
        return {
          type: 'usagebar',
          fraction: total ? used / total : 0,
          color: '#c026a8',
          text: `${gib(used)} / ${gib(total)}`,
        };
      },
    },
    {
      key: 'm-disk',
      header: 'Disk',
      sortText: (o) => String(nodeDisk[objName(o)]?.usedBytes ?? 0),
      render: (o) => {
        const d = nodeDisk[objName(o)];
        return d ? `${gib(d.usedBytes)} / ${gib(d.totalBytes)}` : '—';
      },
      cell: (o) => {
        const d = nodeDisk[objName(o)];
        if (!d) {
          return { type: 'text', text: '—' };
        }
        return {
          type: 'usagebar',
          fraction: d.totalBytes ? d.usedBytes / d.totalBytes : 0,
          color: '#e08a1e',
          text: `${gib(d.usedBytes)} / ${gib(d.totalBytes)}`,
        };
      },
    },
  ];
}

/** Pod table: a per-container status-square column plus raw CPU / Memory usage. */
function podUsageColumns(cols: TableColumn[], usage: Record<string, UsageSummary>): TableColumn[] {
  const containersCol: TableColumn = {
    key: 'containers',
    header: 'Containers',
    sortText: (o) =>
      String(
        (((o.status as Record<string, unknown>)?.containerStatuses as { ready?: boolean }[]) ?? []).filter(
          (c) => c.ready,
        ).length,
      ),
    render: () => '',
    cell: () => ({ type: 'containers' }),
  };
  const withContainers: TableColumn[] = [];
  cols.forEach((c) => {
    withContainers.push(c);
    if (c.key === 'ready') {
      withContainers.push(containersCol);
    }
  });
  return [
    ...withContainers,
    { key: 'm-cpu', header: 'CPU', render: (o) => usage[objKey(o)]?.cpu ?? '—' },
    { key: 'm-mem', header: 'Memory', render: (o) => usage[objKey(o)]?.memory ?? '—' },
  ];
}

/** The columns for the selected kind, with live metrics columns for Pods/Nodes. */
export function buildResourceColumns(
  id: string | undefined,
  cols: TableColumn[],
  usage: Record<string, UsageSummary>,
  nodeDisk: Record<string, NodeDiskUsage>,
): TableColumn[] {
  if (id === 'nodes') {
    return [...cols, ...nodeUsageColumns(usage, nodeDisk)];
  }
  if (id === 'pods') {
    return podUsageColumns(cols, usage);
  }
  return cols;
}

import type { ReactNode } from 'react';
import { useState } from 'react';

import { age, readyTone, statusTone } from './columns';
import type { ColumnDef } from './columns';
import { containerNames, objKey, objName, objNs } from './kube';
import { RowMenu } from './rowMenu';
import type { RowAction } from './rowMenu';
import { ContainerSquares, StatusBadge } from './ui';
import type { KubeObject } from './types';

type RowCtx = {
  cols: ColumnDef[];
  showNs: boolean;
  showAge: boolean;
  totalCols: number;
  selection: Set<string>;
  selectedKey: string | null;
  expanded: Set<string>;
  childMap: Record<string, KubeObject[] | null>;
  onOpen: (o: KubeObject) => void;
  onToggleRow: (key: string) => void;
  onNamespaceClick?: (ns: string) => void;
  onRowAction: (action: RowAction, obj: KubeObject, container?: string) => void;
  fetchChildren?: (obj: KubeObject) => Promise<KubeObject[]>;
  toggleExpand: (o: KubeObject) => void;
};

function childPodRow(p: KubeObject, parentKey: string, totalCols: number, onOpen: (o: KubeObject) => void) {
  const cs = ((p.status as Record<string, unknown>)?.containerStatuses as Record<string, unknown>[]) ?? [];
  const restarts = cs.reduce((n, c) => n + Number(c.restartCount ?? 0), 0);
  const phase = String((p.status as Record<string, unknown>)?.phase ?? '');
  const node = String((p.spec as Record<string, unknown>)?.nodeName ?? '');
  return (
    <tr key={parentKey + '>' + objKey(p)} className="child-row" onClick={() => onOpen(p)}>
      <td colSpan={totalCols}>
        <div className="child-pod">
          <span className="child-name">↳ {objName(p)}</span>
          <ContainerSquares obj={p} />
          {phase && <StatusBadge text={phase} />}
          <span className="dim">↻ {restarts}</span>
          {node && <span className="dim">{node}</span>}
          <span className="dim">{age(p.metadata?.creationTimestamp)}</span>
        </div>
      </td>
    </tr>
  );
}

/** The main <tr> for one object (checkbox, name/disclosure, columns with tone, row menu). */
function mainRow(o: KubeObject, ctx: RowCtx) {
  const isExpanded = ctx.expanded.has(objKey(o));
  const nsCell =
    ctx.onNamespaceClick && objNs(o) ? (
      <td>
        <button
          className="cell-link"
          onClick={(e) => {
            e.stopPropagation();
            ctx.onNamespaceClick?.(objNs(o) as string);
          }}
        >
          {objNs(o)}
        </button>
      </td>
    ) : (
      <td>{objNs(o) ?? '—'}</td>
    );
  return (
    <tr
      key={objKey(o)}
      className={
        (objKey(o) === ctx.selectedKey ? 'row-active' : '') + (ctx.selection.has(objKey(o)) ? ' row-checked' : '')
      }
      onClick={() => ctx.onOpen(o)}
    >
      <td className="chk" onClick={(e) => e.stopPropagation()}>
        <input type="checkbox" checked={ctx.selection.has(objKey(o))} onChange={() => ctx.onToggleRow(objKey(o))} />
      </td>
      <td className="name">
        {ctx.fetchChildren && (
          <button
            className="tree-toggle"
            title={isExpanded ? 'Collapse' : 'Show pods'}
            onClick={(e) => {
              e.stopPropagation();
              ctx.toggleExpand(o);
            }}
          >
            {isExpanded ? '▾' : '▸'}
          </button>
        )}
        {objName(o)}
      </td>
      {ctx.showNs && nsCell}
      {ctx.cols.map((c) => {
        const cell = c.render(o);
        const text = typeof cell === 'string' ? cell : null;
        // Tone keys off the stable column key (a code id), not the display header.
        const tone =
          text === null ? '' : c.key === 'status' ? statusTone(text) : c.key === 'ready' ? readyTone(text) : '';
        return <td key={c.key}>{tone ? <span className={'status-pill status-' + tone}>{text}</span> : cell}</td>;
      })}
      {ctx.showAge && <td>{age(o.metadata?.creationTimestamp)}</td>}
      <td className="rowmenu-cell" onClick={(e) => e.stopPropagation()}>
        <RowMenu
          kind={o.kind ?? ''}
          suspended={Boolean((o.spec as Record<string, unknown>)?.suspend)}
          containers={containerNames(o)}
          onAction={(a, c) => ctx.onRowAction(a, o, c)}
        />
      </td>
    </tr>
  );
}

/** One object's rows: its main row plus, when expanded, a loading/empty message or its pods. */
function objectRows(o: KubeObject, ctx: RowCtx): ReactNode[] {
  const rowKey = objKey(o);
  const rows: ReactNode[] = [mainRow(o, ctx)];
  if (!ctx.expanded.has(rowKey)) {
    return rows;
  }
  const kids = ctx.childMap[rowKey];
  if (kids === null || kids === undefined) {
    rows.push(
      <tr key={rowKey + '>loading'} className="child-row">
        <td colSpan={ctx.totalCols} className="child-msg">
          Loading pods…
        </td>
      </tr>,
    );
  } else if (kids.length === 0) {
    rows.push(
      <tr key={rowKey + '>empty'} className="child-row">
        <td colSpan={ctx.totalCols} className="child-msg">
          No pods.
        </td>
      </tr>,
    );
  } else {
    kids.forEach((p) => rows.push(childPodRow(p, rowKey, ctx.totalCols, ctx.onOpen)));
  }
  return rows;
}

export function ResourceTable(props: {
  objects: KubeObject[];
  columns: ColumnDef[];
  namespaced: boolean;
  loading: boolean;
  selectedKey: string | null;
  selection: Set<string>;
  onToggleRow: (key: string) => void;
  onToggleAll: (keys: string[]) => void;
  onOpen: (obj: KubeObject) => void;
  onNamespaceClick?: (ns: string) => void;
  authed: boolean;
  onRowAction: (action: RowAction, obj: KubeObject, container?: string) => void;
  fetchChildren?: (obj: KubeObject) => Promise<KubeObject[]>;
}) {
  const {
    objects,
    columns: cols,
    namespaced,
    loading,
    selectedKey,
    selection,
    onToggleRow,
    onToggleAll,
    onOpen,
    onNamespaceClick,
    onRowAction,
    fetchChildren,
  } = props;
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [children, setChildren] = useState<Record<string, KubeObject[] | null>>({});
  const toggleExpand = (o: KubeObject) => {
    const k = objKey(o);
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(k)) {
        next.delete(k);
      } else {
        next.add(k);
        if (children[k] === undefined && fetchChildren) {
          setChildren((c) => ({ ...c, [k]: null }));
          fetchChildren(o)
            .then((kids) => setChildren((c) => ({ ...c, [k]: kids })))
            .catch(() => setChildren((c) => ({ ...c, [k]: [] })));
        }
      }
      return next;
    });
  };
  const [sort, setSort] = useState<{ key: string; dir: number }>({ key: 'name', dir: 1 });
  if (loading) {
    return <div className="empty">Loading…</div>;
  }
  if (objects.length === 0) {
    return <div className="empty">No resources.</div>;
  }
  const showNs = namespaced && objects.some((o) => objNs(o));
  // Some CRD printer columns already include an Age column; don't render ours twice.
  const showAge = !cols.some((c) => c.header.toLowerCase() === 'age');

  const headerCols: { key: string; header: string }[] = [
    { key: 'name', header: 'Name' },
    ...(showNs ? [{ key: 'namespace', header: 'Namespace' }] : []),
    ...cols.map((c) => ({ key: c.key, header: c.header })),
    ...(showAge ? [{ key: 'age', header: 'Age' }] : []),
  ];
  const textValue = (o: KubeObject, key: string): string => {
    if (key === 'name') {
      return objName(o);
    }
    if (key === 'namespace') {
      return objNs(o) ?? '';
    }
    const c = cols.find((x) => x.key === key);
    if (!c) {
      return '';
    }
    if (c.sortText) {
      return c.sortText(o);
    }
    const rendered = c.render(o);
    return typeof rendered === 'string' ? rendered : '';
  };
  const sorted = [...objects].sort((a, b) => {
    if (sort.key === 'age') {
      const ta = Date.parse(a.metadata?.creationTimestamp ?? '') || 0;
      const tb = Date.parse(b.metadata?.creationTimestamp ?? '') || 0;
      return (ta - tb) * sort.dir;
    }
    return textValue(a, sort.key).localeCompare(textValue(b, sort.key), undefined, { numeric: true }) * sort.dir;
  });
  const clickHeader = (key: string) =>
    setSort((prev) => (prev.key === key ? { key, dir: -prev.dir } : { key, dir: 1 }));

  const sortedKeys = sorted.map(objKey);
  const allSelected = sortedKeys.length > 0 && sortedKeys.every((k) => selection.has(k));
  const totalCols = 1 + headerCols.length + 1;
  const ctx: RowCtx = {
    cols,
    showNs,
    showAge,
    totalCols,
    selection,
    selectedKey,
    expanded,
    childMap: children,
    onOpen,
    onToggleRow,
    onNamespaceClick,
    onRowAction,
    fetchChildren,
    toggleExpand,
  };

  return (
    <table className="grid clickable">
      <thead>
        <tr>
          <th className="chk">
            <input type="checkbox" checked={allSelected} onChange={() => onToggleAll(sortedKeys)} />
          </th>
          {headerCols.map((h) => (
            <th key={h.key} className="sortable" onClick={() => clickHeader(h.key)}>
              {h.header}
              {sort.key === h.key && <span className="sort-ind">{sort.dir === 1 ? ' ▲' : ' ▼'}</span>}
            </th>
          ))}
          <th className="rowmenu-cell" />
        </tr>
      </thead>
      <tbody>{sorted.flatMap((o) => objectRows(o, ctx))}</tbody>
    </table>
  );
}

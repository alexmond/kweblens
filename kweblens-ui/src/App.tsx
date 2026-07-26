import type { FormEvent, ReactNode } from 'react';
import { Fragment, useEffect, useMemo, useRef, useState } from 'react';

import { ApiError, api } from './api';
import { auth } from './auth';
import type { ColumnDef } from './columns';
import { age, columnsFor, printerColumnDefs, readyTone, statusTone } from './columns';
import type {
  ClusterInfo,
  EventSummary,
  HelmChart,
  HelmMutationResult,
  HelmRelease,
  HelmResourceRef,
  KubeObject,
  MetricSeries,
  NavCategory,
  NavItem,
  NodeDiskUsage,
  PortForward,
  UsageSummary,
} from './types';

function initials(id: string): string {
  return (id.length >= 2 ? id.slice(0, 2) : id).toUpperCase();
}

const objName = (o: KubeObject): string => o.metadata?.name ?? '';
const objNs = (o: KubeObject): string | undefined => o.metadata?.namespace;
const objKey = (o: KubeObject): string => (objNs(o) ?? '') + '/' + objName(o);

// Ports worth suggesting when starting a forward: a Pod's containerPorts, a Service's ports.
function objectPorts(kind: string, o: KubeObject): number[] {
  const spec = (o.spec as Record<string, unknown>) ?? {};
  const ports = new Set<number>();
  if (kind === 'Service') {
    for (const p of (spec.ports as { port?: number }[]) ?? []) {
      if (typeof p.port === 'number') {
        ports.add(p.port);
      }
    }
  } else {
    for (const c of (spec.containers as { ports?: { containerPort?: number }[] }[]) ?? []) {
      for (const p of c.ports ?? []) {
        if (typeof p.containerPort === 'number') {
          ports.add(p.containerPort);
        }
      }
    }
  }
  return [...ports];
}

// Synthetic nav items are client-only views (dashboards, Helm) rather than resource kinds.
const isSynthetic = (id: string): boolean =>
  id.startsWith('overview:') || id.startsWith('helm:') || id.startsWith('portforward:');

// All nav items across categories and their nested sub-groups (Custom Resources).
function allNavItems(categories: NavCategory[]): NavItem[] {
  return categories.flatMap((c) => [...c.items, ...(c.subgroups ?? []).flatMap((g) => g.items)]);
}

// Parse a Kubernetes CPU quantity (e.g. "245m", "2", "500n") to cores.
function parseCpuCores(q: string | undefined): number {
  if (!q) {
    return 0;
  }
  const n = Number.parseFloat(q);
  if (Number.isNaN(n)) {
    return 0;
  }
  if (q.endsWith('m')) {
    return n / 1000;
  }
  if (q.endsWith('u')) {
    return n / 1e6;
  }
  if (q.endsWith('n')) {
    return n / 1e9;
  }
  return n;
}

const MEM_UNITS: Record<string, number> = {
  '': 1,
  Ki: 1024,
  Mi: 1024 ** 2,
  Gi: 1024 ** 3,
  Ti: 1024 ** 4,
  Pi: 1024 ** 5,
  k: 1e3,
  K: 1e3,
  M: 1e6,
  G: 1e9,
  T: 1e12,
};

// Parse a Kubernetes memory quantity (e.g. "4637Mi", "4046264Ki", "4Gi") to bytes.
function parseMemBytes(q: string | undefined): number {
  if (!q) {
    return 0;
  }
  const m = /^([0-9.]+)\s*([A-Za-z]*)$/.exec(q.trim());
  if (!m) {
    return 0;
  }
  return Number.parseFloat(m[1]) * (MEM_UNITS[m[2]] ?? 1);
}

const gib = (bytes: number): string => (bytes / 1024 ** 3).toFixed(1) + 'Gi';

// Remove the noisy metadata.managedFields block from a manifest (text-level, so it works
// without a YAML parser). Drops the `managedFields:` key and its whole nested/sequence body.
function stripManagedFields(yaml: string): string {
  const lines = yaml.split('\n');
  const out: string[] = [];
  let blockIndent = -1;
  for (const line of lines) {
    if (blockIndent >= 0) {
      const indent = line.search(/\S/);
      const isSeqItem = indent === blockIndent && line[indent] === '-';
      if (indent === -1 || indent > blockIndent || isSeqItem) {
        continue;
      }
      blockIndent = -1;
    }
    const m = /^(\s*)managedFields:\s*$/.exec(line);
    if (m) {
      blockIndent = m[1].length;
      continue;
    }
    out.push(line);
  }
  return out.join('\n');
}

// Split one YAML line into coloured tokens (indent · list-dash · key · value/comment).
function yamlTokens(line: string): ReactNode[] {
  const m = /^(\s*)(-\s+)?(?:([\w.\-/]+)(:))?(\s*)(.*)$/.exec(line);
  if (!m) {
    return [line];
  }
  const [, indent, dash, key, colon, gap, rest] = m;
  const nodes: ReactNode[] = [indent];
  if (dash) {
    nodes.push(
      <span className="yk-dash" key="d">
        {dash}
      </span>,
    );
  }
  if (key) {
    nodes.push(
      <span className="yk-key" key="k">
        {key}
      </span>,
      colon,
    );
  }
  nodes.push(gap);
  if (rest) {
    const t = rest.trim();
    let cls = 'yk-str';
    if (t.startsWith('#')) {
      cls = 'yk-comment';
    } else if (/^-?\d+(\.\d+)?$/.test(t)) {
      cls = 'yk-num';
    } else if (/^(true|false|null|~)$/i.test(t) || t === '|' || t === '>') {
      cls = 'yk-bool';
    }
    nodes.push(
      <span className={cls} key="v">
        {rest}
      </span>,
    );
  }
  return nodes;
}

function YamlView(props: { text: string }) {
  return (
    <pre className="yaml">
      {props.text.split('\n').map((line, i) => (
        <span key={i}>
          {yamlTokens(line)}
          {'\n'}
        </span>
      ))}
    </pre>
  );
}

// One coloured square per container, by state (Freelens-style), with a hover tooltip.
function containerSquare(cs: Record<string, unknown>): { tone: string; title: string } {
  const name = String(cs.name ?? '');
  const state = (cs.state as Record<string, Record<string, unknown>>) ?? {};
  const ready = Boolean(cs.ready);
  const restarts = Number(cs.restartCount ?? 0);
  let tone = 'wait';
  const lines = [name];
  if (state.running) {
    tone = ready ? 'ok' : 'warn';
    lines.push(ready ? 'Running' : 'Running (not ready)');
    if (state.running.startedAt) {
      lines.push('Started At  ' + String(state.running.startedAt));
    }
  } else if (state.terminated) {
    const t = state.terminated;
    tone = t.exitCode === 0 ? 'done' : 'err';
    lines.push(`Terminated · ${String(t.reason ?? '')} (exit ${Number(t.exitCode ?? 0)})`);
  } else if (state.waiting) {
    const reason = String(state.waiting.reason ?? 'Waiting');
    tone = /crashloop|imagepull|errimage|error|invalid/i.test(reason) ? 'err' : 'wait';
    lines.push('Waiting · ' + reason);
  }
  if (restarts > 0) {
    lines.push('Restarts: ' + restarts);
  }
  return { tone, title: lines.join('\n') };
}

function ContainerSquares(props: { obj: KubeObject }) {
  const { obj } = props;
  const statuses = ((obj.status as Record<string, unknown>)?.containerStatuses as Record<string, unknown>[]) ?? [];
  const specContainers = ((obj.spec as Record<string, unknown>)?.containers as Record<string, unknown>[]) ?? [];
  const list = statuses.length > 0 ? statuses : specContainers.map((c) => ({ name: c.name, ready: false, state: {} }));
  if (list.length === 0) {
    return <>—</>;
  }
  return (
    <span className="csquares">
      {list.map((cs, i) => {
        const { tone, title } = containerSquare(cs);
        return <span key={String(cs.name ?? i)} className={'csq csq-' + tone} title={title} />;
      })}
    </span>
  );
}

function containerNames(o: KubeObject): string[] {
  return (((o.spec as Record<string, unknown>)?.containers as { name?: string }[]) ?? [])
    .map((c) => c.name ?? '')
    .filter(Boolean);
}

type RowAction = 'logs' | 'edit' | 'delete' | 'forceDelete';

// Per-row kebab (⋮) actions menu (Freelens-style): Logs (pods) / Edit / Delete / Force Delete.
function RowMenu(props: { authed: boolean; isPod: boolean; onAction: (a: RowAction) => void }) {
  const { authed, isPod, onAction } = props;
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) {
      return;
    }
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, [open]);

  if (!isPod && !authed) {
    return null;
  }
  const item = (label: string, action: RowAction, danger?: boolean) => (
    <button
      className={'menu-item' + (danger ? ' danger' : '')}
      onClick={(e) => {
        e.stopPropagation();
        setOpen(false);
        onAction(action);
      }}
    >
      {label}
    </button>
  );
  return (
    <div className="rowmenu" ref={ref} onClick={(e) => e.stopPropagation()}>
      <button
        className="kebab"
        title="Actions"
        onClick={(e) => {
          e.stopPropagation();
          setOpen((v) => !v);
        }}
      >
        ⋮
      </button>
      {open && (
        <div className="menu">
          {isPod && item('Logs', 'logs')}
          {authed && item('Edit', 'edit')}
          {authed && item('Delete', 'delete', true)}
          {authed && item('Force Delete', 'forceDelete', true)}
        </div>
      )}
    </div>
  );
}

// A status/phase value coloured green/amber/red by health; plain text when unrecognised.
function StatusBadge(props: { text: string }) {
  const { text } = props;
  const tone = statusTone(text);
  if (!tone) {
    return <>{text}</>;
  }
  return <span className={'status-pill status-' + tone}>{text}</span>;
}

// A used/total value with a proportional fill bar (vCenter/Freelens style).
function UsageBar(props: { fraction: number; color: string; text: string }) {
  const { fraction, color, text } = props;
  const pct = Math.max(0, Math.min(100, fraction * 100));
  return (
    <div className="ubar" title={`${Math.round(pct)}%`}>
      <div className="ubar-track">
        <div className="ubar-fill" style={{ width: pct + '%', background: color }} />
      </div>
      <div className="ubar-text">{text}</div>
    </div>
  );
}

// Favorites are pinned nav-item ids, persisted per cluster.
function loadFavorites(cluster: string): string[] {
  try {
    return JSON.parse(localStorage.getItem('kw-fav-' + cluster) ?? '[]') as string[];
  } catch {
    return [];
  }
}
function saveFavorites(cluster: string, favorites: string[]): void {
  try {
    localStorage.setItem('kw-fav-' + cluster, JSON.stringify(favorites));
  } catch {
    // storage unavailable — favorites just won't persist
  }
}

// Inject a "Overview" dashboard item at the top of the Workloads category, and a Helm
// section (client-only views, not resource kinds).
function withSyntheticNav(cats: NavCategory[]): NavCategory[] {
  const withOverview = cats.map((c) => {
    if (c.label === 'Cluster') {
      return { ...c, items: [{ id: 'overview:cluster', label: 'Overview', kind: '', namespaced: false }, ...c.items] };
    }
    if (c.label === 'Workloads') {
      return { ...c, items: [{ id: 'overview:workloads', label: 'Overview', kind: '', namespaced: false }, ...c.items] };
    }
    if (c.label === 'Network') {
      return { ...c, items: [...c.items, { id: 'portforward:list', label: 'Port Forwards', kind: '', namespaced: false }] };
    }
    return c;
  });
  const helm: NavCategory = {
    label: 'Helm',
    icon: 'bi-hexagon',
    items: [{ id: 'helm:home', label: 'Helm', kind: '', namespaced: false }],
  };
  return [...withOverview, helm];
}

export function App() {
  const [clusters, setClusters] = useState<ClusterInfo[]>([]);
  const [cluster, setCluster] = useState<string | null>(null);
  const [nav, setNav] = useState<NavCategory[]>([]);
  const [counts, setCounts] = useState<Record<string, number>>({});
  const [favorites, setFavorites] = useState<string[]>([]);
  const [namespaces, setNamespaces] = useState<string[]>([]);
  const [namespace, setNamespace] = useState<string | null>(null);
  const [helmReleaseList, setHelmReleaseList] = useState<HelmRelease[]>([]);
  const [helmRelease, setHelmRelease] = useState<{ namespace: string; name: string } | null>(null);
  const [helmScope, setHelmScope] = useState<Set<string> | null>(null);
  const [selected, setSelected] = useState<NavItem | null>(null);
  const [objects, setObjects] = useState<KubeObject[]>([]);
  const [loading, setLoading] = useState(false);
  const [live, setLive] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [detail, setDetail] = useState<{ resourceId: string; obj: KubeObject; edit?: boolean } | null>(null);
  const [cols, setCols] = useState<ColumnDef[]>([]);
  const [usage, setUsage] = useState<Record<string, UsageSummary>>({});
  const [nodeDisk, setNodeDisk] = useState<Record<string, NodeDiskUsage>>({});
  const [hiddenCols, setHiddenCols] = useState<Set<string>>(new Set());
  const [selection, setSelection] = useState<Set<string>>(new Set());
  const [query, setQuery] = useState('');
  const [authUser, setAuthUser] = useState<string | null>(null);
  const [showLogin, setShowLogin] = useState(false);
  const [showCreate, setShowCreate] = useState(false);
  const [terminal, setTerminal] = useState<{ namespace: string; pod: string; containers: string[] } | null>(null);
  const [logs, setLogs] = useState<{ namespace: string; pod: string; containers: string[] } | null>(null);
  const [forward, setForward] = useState<{ kind: string; namespace: string; name: string; ports: number[] } | null>(
    null,
  );
  const [helmTarget, setHelmTarget] = useState<{ namespace: string; name: string } | null>(null);

  useEffect(() => {
    api
      .clusters()
      .then((cs) => {
        setClusters(cs);
        setCluster((prev) => prev ?? cs[0]?.id ?? null);
      })
      .catch((e) => setError(String(e)));
  }, []);

  useEffect(() => {
    if (!cluster) {
      return;
    }
    setNav([]);
    setCounts({});
    setFavorites(loadFavorites(cluster));
    setNamespaces([]);
    setNamespace(null);
    setHelmRelease(null);
    setSelected(null);
    setObjects([]);
    setError(null);
    api
      .nav(cluster)
      .then((cats) => setNav(withSyntheticNav(cats)))
      .catch((e) => setError(String(e)));
    api
      .namespaces(cluster)
      .then((ns) => setNamespaces(ns.map((r) => r.name).sort()))
      .catch(() => setNamespaces([]));
    api
      .helmReleases(cluster)
      .then(setHelmReleaseList)
      .catch(() => setHelmReleaseList([]));
  }, [cluster]);

  // Resolve the selected Helm release to the set of objects it manages (kind/name), for
  // scoping lists and counts.
  useEffect(() => {
    if (!cluster || !helmRelease) {
      setHelmScope(null);
      return;
    }
    let cancelled = false;
    api
      .helmReleaseResources(cluster, helmRelease.namespace, helmRelease.name)
      .then((refs) => !cancelled && setHelmScope(new Set(refs.map((r) => `${r.kind}/${r.name}`))))
      .catch(() => !cancelled && setHelmScope(new Set()));
    return () => {
      cancelled = true;
    };
  }, [cluster, helmRelease]);

  // Count badges track the active scope: a Helm release → counts from its manifest;
  // otherwise the (namespace-aware) server counts.
  useEffect(() => {
    if (!cluster) {
      return;
    }
    if (helmScope) {
      const kindToId = new Map<string, string>();
      allNavItems(nav).forEach((i) => i.kind && kindToId.set(i.kind, i.id));
      const c: Record<string, number> = {};
      helmScope.forEach((key) => {
        const id = kindToId.get(key.split('/')[0]);
        if (id) {
          c[id] = (c[id] ?? 0) + 1;
        }
      });
      setCounts(c);
      return;
    }
    let cancelled = false;
    api
      .counts(cluster, namespace ?? undefined)
      .then((c) => !cancelled && setCounts(c))
      .catch(() => !cancelled && setCounts({}));
    return () => {
      cancelled = true;
    };
  }, [cluster, namespace, helmScope, nav]);

  // Fetch the selected kind's raw objects on kind/namespace change.
  useEffect(() => {
    if (!cluster || !selected || isSynthetic(selected.id)) {
      setObjects([]);
      return;
    }
    const ns = selected.namespaced ? namespace ?? undefined : undefined;
    let cancelled = false;
    setDetail(null);
    setQuery('');
    setHiddenCols(new Set());
    setSelection(new Set());
    setLoading(true);
    setError(null);
    api
      .objects(cluster, selected.id, ns)
      .then((r) => {
        if (!cancelled) {
          setObjects(r);
        }
      })
      .catch((e) => {
        if (!cancelled) {
          setError(String(e));
          setObjects([]);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [cluster, selected, namespace]);

  // Columns for the selected kind: built-in registry, or the CRD's printer columns for
  // custom kinds (their id is "group.plural"), falling back to Name/Namespace/Age.
  useEffect(() => {
    if (!cluster || !selected || isSynthetic(selected.id)) {
      setCols([]);
      return;
    }
    if (selected.id.includes('.')) {
      let cancelled = false;
      api
        .printerColumns(cluster, selected.id)
        .then((pc) => {
          if (!cancelled) {
            setCols(pc.length ? printerColumnDefs(pc) : []);
          }
        })
        .catch(() => setCols([]));
      return () => {
        cancelled = true;
      };
    }
    setCols(columnsFor(selected.id));
    return undefined;
  }, [cluster, selected]);

  // In-list metrics-server usage for Pods/Nodes; refreshed every 15s.
  useEffect(() => {
    if (!cluster || !selected || (selected.id !== 'pods' && selected.id !== 'nodes')) {
      setUsage({});
      setNodeDisk({});
      return;
    }
    let cancelled = false;
    const load = () => {
      const p =
        selected.id === 'nodes'
          ? api.nodeMetrics(cluster)
          : api.podMetrics(cluster, selected.namespaced ? namespace ?? undefined : undefined);
      p.then((list) => {
        if (cancelled) {
          return;
        }
        const map: Record<string, UsageSummary> = {};
        list.forEach((u) => {
          map[(u.namespace ?? '') + '/' + u.name] = u;
        });
        setUsage(map);
      }).catch(() => {
        if (!cancelled) {
          setUsage({});
        }
      });
      // Disk (Prometheus/node-exporter) is node-only and lives on a separate endpoint.
      if (selected.id === 'nodes') {
        api
          .nodeDisk(cluster)
          .then((list) => {
            if (cancelled) {
              return;
            }
            const dmap: Record<string, NodeDiskUsage> = {};
            list.forEach((d) => {
              dmap[d.node] = d;
            });
            setNodeDisk(dmap);
          })
          .catch(() => !cancelled && setNodeDisk({}));
      }
    };
    load();
    const timer = window.setInterval(load, 15000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [cluster, selected, namespace]);

  // Live object stream: patch the table in place.
  useEffect(() => {
    if (!cluster || !selected || isSynthetic(selected.id)) {
      return;
    }
    const ns = selected.namespaced ? namespace ?? undefined : undefined;
    const url =
      `/api/v1/clusters/${encodeURIComponent(cluster)}/resources/${encodeURIComponent(selected.id)}/objects/watch` +
      (ns ? `?namespace=${encodeURIComponent(ns)}` : '');
    const es = new EventSource(url);
    const upsert = (e: MessageEvent) => {
      const obj = JSON.parse(e.data) as KubeObject;
      setObjects((prev) => {
        const key = objKey(obj);
        const idx = prev.findIndex((o) => objKey(o) === key);
        if (idx === -1) {
          return [...prev, obj];
        }
        const next = prev.slice();
        next[idx] = obj;
        return next;
      });
    };
    const remove = (e: MessageEvent) => {
      const obj = JSON.parse(e.data) as KubeObject;
      setObjects((prev) => prev.filter((o) => objKey(o) !== objKey(obj)));
    };
    es.addEventListener('ADDED', upsert as EventListener);
    es.addEventListener('MODIFIED', upsert as EventListener);
    es.addEventListener('DELETED', remove as EventListener);
    es.onopen = () => setLive(true);
    es.onerror = () => setLive(false);
    return () => {
      setLive(false);
      es.close();
    };
  }, [cluster, selected, namespace]);

  const activeCluster = useMemo(() => clusters.find((c) => c.id === cluster) ?? null, [clusters, cluster]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    const scoped = helmScope ? objects.filter((o) => helmScope.has(`${o.kind ?? ''}/${objName(o)}`)) : objects;
    if (!q) {
      return scoped;
    }
    return scoped.filter(
      (o) =>
        objName(o).toLowerCase().includes(q) ||
        (objNs(o) ?? '').toLowerCase().includes(q) ||
        (o.kind ?? '').toLowerCase().includes(q),
    );
  }, [objects, query, helmScope]);

  // For Pods/Nodes, append live CPU/Memory columns backed by metrics-server usage. Nodes
  // show used/allocatable with a proportional bar; Pods show the raw usage value.
  const tableCols = useMemo<ColumnDef[]>(() => {
    if (selected?.id === 'nodes') {
      const alloc = (o: KubeObject) => ((o.status as Record<string, unknown>)?.allocatable as Record<string, string>) ?? {};
      return [
        ...cols,
        {
          key: 'm-cpu',
          header: 'CPU',
          sortText: (o) => String(parseCpuCores(usage[objKey(o)]?.cpu)),
          render: (o) => {
            const used = parseCpuCores(usage[objKey(o)]?.cpu);
            const total = parseCpuCores(alloc(o).cpu);
            if (!usage[objKey(o)]) {
              return '—';
            }
            return (
              <UsageBar
                fraction={total ? used / total : 0}
                color="#2e9e8f"
                text={`${used.toFixed(2)} / ${total.toFixed(2)}`}
              />
            );
          },
        },
        {
          key: 'm-mem',
          header: 'Memory',
          sortText: (o) => String(parseMemBytes(usage[objKey(o)]?.memory)),
          render: (o) => {
            const used = parseMemBytes(usage[objKey(o)]?.memory);
            const total = parseMemBytes(alloc(o).memory);
            if (!usage[objKey(o)]) {
              return '—';
            }
            return (
              <UsageBar
                fraction={total ? used / total : 0}
                color="#c026a8"
                text={`${gib(used)} / ${gib(total)}`}
              />
            );
          },
        },
        {
          key: 'm-disk',
          header: 'Disk',
          sortText: (o) => String(nodeDisk[objName(o)]?.usedBytes ?? 0),
          render: (o) => {
            const d = nodeDisk[objName(o)];
            if (!d) {
              return '—';
            }
            return (
              <UsageBar
                fraction={d.totalBytes ? d.usedBytes / d.totalBytes : 0}
                color="#e08a1e"
                text={`${gib(d.usedBytes)} / ${gib(d.totalBytes)}`}
              />
            );
          },
        },
      ];
    }
    if (selected?.id === 'pods') {
      const containersCol: ColumnDef = {
        key: 'containers',
        header: 'Containers',
        sortText: (o) =>
          String((((o.status as Record<string, unknown>)?.containerStatuses as { ready?: boolean }[]) ?? []).filter((c) => c.ready).length),
        render: (o) => <ContainerSquares obj={o} />,
      };
      const withContainers: ColumnDef[] = [];
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
    return cols;
  }, [cols, usage, nodeDisk, selected]);

  const visibleCols = useMemo(() => tableCols.filter((c) => !hiddenCols.has(c.key)), [tableCols, hiddenCols]);

  // Map a Kubernetes kind to its nav item, so cross-links (owner refs) can navigate.
  const kindNav = useMemo(() => {
    const map = new Map<string, NavItem>();
    allNavItems(nav).forEach((i) => i.kind && map.set(i.kind, i));
    return map;
  }, [nav]);

  const navigateToKind = (kind: string, ns?: string) => {
    const item = kindNav.get(kind);
    if (item) {
      setDetail(null);
      setSelected(item);
      setNamespace(item.namespaced && ns ? ns : null);
    }
  };

  const navigateToPortForwards = () => {
    setDetail(null);
    setSelected({ id: 'portforward:list', label: 'Port Forwards', kind: '', namespaced: false });
  };

  // The pods a workload owns — matched by its spec.selector.matchLabels in its namespace.
  const fetchWorkloadPods = async (obj: KubeObject): Promise<KubeObject[]> => {
    if (!cluster) {
      return [];
    }
    const sel = ((obj.spec as Record<string, unknown>)?.selector as { matchLabels?: Record<string, string> })
      ?.matchLabels ?? {};
    const keys = Object.keys(sel);
    if (keys.length === 0) {
      return [];
    }
    const pods = await api.objects(cluster, 'pods', objNs(obj) ?? undefined);
    return pods.filter((p) => {
      const labels = p.metadata?.labels ?? {};
      return keys.every((k) => labels[k] === sel[k]);
    });
  };

  // Per-row kebab actions: open logs, jump to YAML edit, or delete / force-delete.
  const handleRowAction = (resourceId: string, action: RowAction, obj: KubeObject) => {
    const ns = objNs(obj) ?? '';
    const nm = objName(obj);
    if (action === 'logs') {
      setTerminal(null);
      setLogs({ namespace: ns, pod: nm, containers: containerNames(obj) });
      return;
    }
    if (action === 'edit') {
      setDetail({ resourceId, obj, edit: true });
      return;
    }
    const force = action === 'forceDelete';
    if (!cluster || !window.confirm(`${force ? 'Force delete' : 'Delete'} ${obj.kind} ${nm}? This cannot be undone.`)) {
      return;
    }
    api.del(cluster, resourceId, ns, nm, force).then(
      () => setObjects((prev) => prev.filter((o) => objKey(o) !== objKey(obj))),
      (e) => setError(String(e)),
    );
  };

  // From a resource's "Managed By: Helm" link → open the owning release's Resources view.
  const navigateToHelmRelease = (namespace: string, name: string) => {
    setDetail(null);
    setSelected({ id: 'helm:home', label: 'Helm', kind: '', namespaced: false });
    setHelmTarget({ namespace, name });
  };

  const toggleFavorite = (id: string) =>
    setFavorites((prev) => {
      const next = prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id];
      if (cluster) {
        saveFavorites(cluster, next);
      }
      return next;
    });

  const toggleCol = (key: string) =>
    setHiddenCols((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });

  const toggleRow = (key: string) =>
    setSelection((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });

  const toggleAll = (keys: string[]) =>
    setSelection((prev) => (prev.size >= keys.length && keys.length > 0 ? new Set() : new Set(keys)));

  const bulkDelete = async () => {
    if (!cluster || !selected || selection.size === 0) {
      return;
    }
    if (!authUser) {
      setShowLogin(true);
      return;
    }
    if (!window.confirm(`Delete ${selection.size} ${selected.label}? This cannot be undone.`)) {
      return;
    }
    const targets = objects.filter((o) => selection.has(objKey(o)) && objNs(o));
    for (const o of targets) {
      try {
        await api.del(cluster, selected.id, objNs(o) as string, objName(o));
      } catch (e) {
        if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
          auth.clear();
          setAuthUser(null);
          setShowLogin(true);
          break;
        }
      }
    }
    setSelection(new Set());
  };

  return (
    <div className="app">
      <header className="brandbar">
        <div className="brand">
          <span className="logo">◆</span> kweblens
          <span className="tag">web Kubernetes IDE · SPA</span>
        </div>
        {cluster && (
          <div className="bar-filters">
            <label className="bar-filter">
              <span>Namespace</span>
              <select value={namespace ?? ''} onChange={(e) => setNamespace(e.target.value || null)}>
                <option value="">All namespaces</option>
                {namespaces.map((n) => (
                  <option key={n} value={n}>
                    {n}
                  </option>
                ))}
              </select>
            </label>
            <label className="bar-filter">
              <span>Helm</span>
              <select
                value={helmRelease ? `${helmRelease.namespace}/${helmRelease.name}` : ''}
                onChange={(e) => {
                  const v = e.target.value;
                  if (!v) {
                    setHelmRelease(null);
                  } else {
                    const slash = v.indexOf('/');
                    setHelmRelease({ namespace: v.slice(0, slash), name: v.slice(slash + 1) });
                  }
                }}
              >
                <option value="">All releases</option>
                {helmReleaseList.map((r) => (
                  <option key={`${r.namespace}/${r.name}`} value={`${r.namespace}/${r.name}`}>
                    {r.name} · {r.namespace}
                  </option>
                ))}
              </select>
            </label>
          </div>
        )}
        <div className="bar-right">
          {authUser ? (
            <span className="authbox">
              <i className="user-dot" /> {authUser}
              <button
                className="linkbtn"
                onClick={() => {
                  auth.clear();
                  setAuthUser(null);
                }}
              >
                Sign out
              </button>
            </span>
          ) : (
            <button className="linkbtn" onClick={() => setShowLogin(true)}>
              Sign in
            </button>
          )}
          <a className="switch" href="/">
            Classic UI ↗
          </a>
        </div>
      </header>

      <div className="body">
        <nav className="rail" aria-label="Clusters">
          {clusters.map((c) => (
            <button
              key={c.id}
              className={'tile' + (c.id === cluster ? ' active' : '')}
              title={c.name}
              onClick={() => setCluster(c.id)}
            >
              {initials(c.id)}
            </button>
          ))}
        </nav>

        <aside className="nav">
          <div className="nav-title">{activeCluster?.name ?? cluster ?? '—'}</div>
          {cluster && (
            <NavTree
              categories={nav}
              counts={counts}
              favorites={favorites}
              selected={selected?.id ?? null}
              onSelect={setSelected}
              onToggleFavorite={toggleFavorite}
            />
          )}
        </aside>

        <main className="content">
          {error && <div className="error">{error}</div>}
          {(!selected || selected.id === 'overview:cluster') && !error && cluster && (
            <ClusterOverview
              cluster={cluster}
              name={activeCluster?.name ?? cluster}
              masterUrl={activeCluster?.masterUrl}
              namespaceCount={namespaces.length}
            />
          )}
          {cluster && selected?.id === 'overview:workloads' && <WorkloadsOverview cluster={cluster} />}
          {cluster && selected?.id?.startsWith('helm:') && (
            <HelmView
              cluster={cluster}
              authed={!!authUser}
              onNavigate={navigateToKind}
              openResources={helmTarget}
              onResourcesConsumed={() => setHelmTarget(null)}
              onRequireAuth={() => setShowLogin(true)}
              onAuthExpired={() => {
                auth.clear();
                setAuthUser(null);
              }}
            />
          )}
          {cluster && selected?.id === 'portforward:list' && (
            <PortForwards cluster={cluster} authed={!!authUser} onRequireAuth={() => setShowLogin(true)} />
          )}
          {selected && !isSynthetic(selected.id) && (
            <>
              <div className="content-head">
                <h1>{selected.label}</h1>
                <span className="count">
                  {query ? `${filtered.length} of ${objects.length}` : `${objects.length} items`}
                </span>
                {live && (
                  <span className="live" title="Live-updating (SSE watch)">
                    <span className="dot" /> live
                  </span>
                )}
                <input
                  className="search"
                  type="search"
                  placeholder={`Search ${selected.label}…`}
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                />
                <div className="spacer" />
                <button
                  className="btn create-btn"
                  onClick={() => (authUser ? setShowCreate(true) : setShowLogin(true))}
                >
                  + Create
                </button>
                {!selected.namespaced && <span className="ns-note">Cluster-scoped</span>}
                {tableCols.length > 0 && (
                  <details className="cols-menu">
                    <summary>Columns ▾</summary>
                    <ul>
                      {tableCols.map((c) => (
                        <li key={c.key}>
                          <label className="col-toggle">
                            <input
                              type="checkbox"
                              checked={!hiddenCols.has(c.key)}
                              onChange={() => toggleCol(c.key)}
                            />
                            {c.header}
                          </label>
                        </li>
                      ))}
                    </ul>
                  </details>
                )}
              </div>
              {selection.size > 0 && (
                <div className="bulk-bar">
                  <span>{selection.size} selected</span>
                  <button className="btn danger" onClick={bulkDelete}>
                    Delete
                  </button>
                  <button className="btn" onClick={() => setSelection(new Set())}>
                    Clear
                  </button>
                </div>
              )}
              <ResourceTable
                objects={filtered}
                columns={visibleCols}
                namespaced={selected.namespaced}
                loading={loading}
                selectedKey={detail ? objKey(detail.obj) : null}
                selection={selection}
                onToggleRow={toggleRow}
                onToggleAll={toggleAll}
                onOpen={(obj) => setDetail({ resourceId: selected.id, obj })}
                onNamespaceClick={selected.namespaced ? (ns) => setNamespace(ns) : undefined}
                authed={authUser !== null}
                onRowAction={(action, obj) => handleRowAction(selected.id, action, obj)}
                fetchChildren={
                  ['deployments', 'statefulsets', 'daemonsets', 'replicasets'].includes(selected.id)
                    ? fetchWorkloadPods
                    : undefined
                }
              />
            </>
          )}
        </main>

        {cluster && detail && (
          <Detail
            cluster={cluster}
            key={detail.resourceId + '/' + objKey(detail.obj)}
            resourceId={detail.resourceId}
            obj={detail.obj}
            initialEdit={detail.edit ?? false}
            authed={authUser !== null}
            onNavigate={navigateToKind}
            onHelmRelease={navigateToHelmRelease}
            onTerminal={(namespace, pod, containers) => {
              setLogs(null);
              setTerminal({ namespace, pod, containers });
            }}
            onLogs={(namespace, pod, containers) => {
              setTerminal(null);
              setLogs({ namespace, pod, containers });
            }}
            onForward={(kind, namespace, name, ports) => setForward({ kind, namespace, name, ports })}
            onRequireAuth={() => setShowLogin(true)}
            onAuthExpired={() => {
              auth.clear();
              setAuthUser(null);
            }}
            onClose={() => setDetail(null)}
          />
        )}
      </div>

      {showLogin && (
        <LoginModal
          onCancel={() => setShowLogin(false)}
          onSubmit={async (user, pass) => {
            auth.set(user, pass);
            try {
              await api.verifySession();
              setAuthUser(user);
              setShowLogin(false);
              return true;
            } catch {
              auth.clear();
              return false;
            }
          }}
        />
      )}

      {showCreate && cluster && (
        <CreateModal
          cluster={cluster}
          onClose={() => setShowCreate(false)}
          onAuthExpired={() => {
            auth.clear();
            setAuthUser(null);
            setShowCreate(false);
            setShowLogin(true);
          }}
        />
      )}

      {cluster && terminal && (
        <TerminalDock
          cluster={cluster}
          namespace={terminal.namespace}
          pod={terminal.pod}
          containers={terminal.containers}
          onClose={() => setTerminal(null)}
        />
      )}

      {cluster && logs && (
        <LogsDock
          cluster={cluster}
          namespace={logs.namespace}
          pod={logs.pod}
          containers={logs.containers}
          onClose={() => setLogs(null)}
        />
      )}

      {cluster && forward && (
        <ForwardModal
          cluster={cluster}
          kind={forward.kind}
          namespace={forward.namespace}
          name={forward.name}
          ports={forward.ports}
          onClose={() => setForward(null)}
          onStarted={() => {
            setForward(null);
            navigateToPortForwards();
          }}
          onAuthExpired={() => {
            auth.clear();
            setAuthUser(null);
          }}
        />
      )}
    </div>
  );
}

function TerminalDock(props: {
  cluster: string;
  namespace: string;
  pod: string;
  containers: string[];
  onClose: () => void;
}) {
  const { cluster, namespace, pod, containers, onClose } = props;
  const ref = useRef<HTMLDivElement>(null);
  const [container, setContainer] = useState(containers[0] ?? '');

  useEffect(() => {
    let cleanup = () => undefined as void;
    let cancelled = false;
    const enc = encodeURIComponent;
    (async () => {
      const [{ Terminal }, { FitAddon }] = await Promise.all([import('@xterm/xterm'), import('@xterm/addon-fit')]);
      if (cancelled || !ref.current) {
        return;
      }
      const term = new Terminal({ fontSize: 13, cursorBlink: true, theme: { background: '#0f172a' } });
      const fit = new FitAddon();
      term.loadAddon(fit);
      term.open(ref.current);
      fit.fit();
      const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
      const url = `${proto}://${window.location.host}/ws/exec?cluster=${enc(cluster)}&namespace=${enc(namespace)}&pod=${enc(pod)}&container=${enc(container)}`;
      const ws = new WebSocket(url);
      ws.onmessage = (e) => {
        if (typeof e.data === 'string') {
          term.write(e.data);
        }
      };
      ws.onclose = () => term.write('\r\n\x1b[90m[session closed]\x1b[0m\r\n');
      ws.onerror = () => term.write('\r\n\x1b[31m[connection error]\x1b[0m\r\n');
      term.onData((d) => {
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(d);
        }
      });
      const onResize = () => {
        try {
          fit.fit();
        } catch {
          // terminal not ready to fit yet
        }
      };
      window.addEventListener('resize', onResize);
      cleanup = () => {
        window.removeEventListener('resize', onResize);
        ws.close();
        term.dispose();
      };
    })();
    return () => {
      cancelled = true;
      cleanup();
    };
  }, [cluster, namespace, pod, container]);

  return (
    <div className="term-dock">
      <div className="term-head">
        <span className="term-title">
          <i className="term-dot" /> {namespace}/{pod}
          {containers.length > 1 && (
            <select className="dock-select" value={container} onChange={(e) => setContainer(e.target.value)}>
              {containers.map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          )}
        </span>
        <button className="term-close" onClick={onClose} title="Close terminal">
          ×
        </button>
      </div>
      <div className="term-body" ref={ref} />
    </div>
  );
}

function LogsDock(props: {
  cluster: string;
  namespace: string;
  pod: string;
  containers: string[];
  onClose: () => void;
}) {
  const { cluster, namespace, pod, containers, onClose } = props;
  const [container, setContainer] = useState(containers[0] ?? '');
  const [lines, setLines] = useState<string[]>([]);
  const [wrap, setWrap] = useState(false);
  const bodyRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let cancelled = false;
    setLines([]);
    const enc = encodeURIComponent;
    const base = `/api/v1/clusters/${enc(cluster)}/pods/${enc(namespace)}/${enc(pod)}/log`;
    const cq = container ? `container=${enc(container)}&` : '';
    // Tail snapshot, then follow via SSE.
    fetch(`${base}?${cq}tailLines=500`)
      .then((r) => r.text())
      .then((t) => {
        if (!cancelled) {
          setLines(t ? t.replace(/\n$/, '').split('\n') : []);
        }
      })
      .catch(() => undefined);
    const es = new EventSource(`${base}/stream?${cq}`);
    es.onmessage = (e) => !cancelled && setLines((prev) => [...prev, e.data].slice(-5000));
    return () => {
      cancelled = true;
      es.close();
    };
  }, [cluster, namespace, pod, container]);

  useEffect(() => {
    const el = bodyRef.current;
    if (el) {
      el.scrollTop = el.scrollHeight;
    }
  }, [lines]);

  return (
    <div className="term-dock">
      <div className="term-head">
        <span className="term-title">
          <i className="term-dot" /> logs · {namespace}/{pod}
          {containers.length > 1 && (
            <select className="dock-select" value={container} onChange={(e) => setContainer(e.target.value)}>
              {containers.map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          )}
          <label className="dock-toggle">
            <input type="checkbox" checked={wrap} onChange={(e) => setWrap(e.target.checked)} /> wrap
          </label>
        </span>
        <button className="term-close" onClick={onClose} title="Close logs">
          ×
        </button>
      </div>
      <div className={'term-body log-body' + (wrap ? ' wrap' : '')} ref={bodyRef}>
        {lines.length === 0 ? <div className="log-line dim">(no output yet)</div> : null}
        {lines.map((l, i) => (
          <div className="log-line" key={i}>
            {l}
          </div>
        ))}
      </div>
    </div>
  );
}

function CreateModal(props: { cluster: string; onClose: () => void; onAuthExpired: () => void }) {
  const { cluster, onClose, onAuthExpired } = props;
  const [draft, setDraft] = useState(
    'apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: example\n  namespace: default\ndata:\n  key: value\n',
  );
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [err, setErr] = useState(false);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const apply = async () => {
    setBusy(true);
    setMsg(null);
    setErr(false);
    try {
      const r = await api.apply(cluster, draft);
      setMsg(`created ${r.kind}/${r.name}`);
      window.setTimeout(onClose, 700);
    } catch (e) {
      setErr(true);
      if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
        onAuthExpired();
      } else {
        setMsg(String(e));
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal wide" onClick={(e) => e.stopPropagation()}>
        <h2>Create from YAML</h2>
        <p className="modal-note">Server-side apply — paste or edit a manifest, then Apply.</p>
        <textarea className="yaml-edit tall" value={draft} spellCheck={false} onChange={(e) => setDraft(e.target.value)} />
        {msg && <div className={'act-msg' + (err ? ' err' : '')}>{msg}</div>}
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button type="button" className="btn primary" onClick={apply} disabled={busy}>
            Apply
          </button>
        </div>
      </div>
    </div>
  );
}

function categoryBadge(cat: NavCategory, counts: Record<string, number>): string {
  const known = cat.items.filter((i) => counts[i.id] !== undefined);
  if (known.length > 0) {
    return String(known.reduce((sum, i) => sum + counts[i.id], 0));
  }
  // No live counts (e.g. a CRD group) — show how many kinds it holds, including nested
  // sub-groups (Custom Resources counts every custom kind across its API groups).
  const nested = (cat.subgroups ?? []).reduce((sum, g) => sum + g.items.length, 0);
  return String(cat.items.length + nested);
}

function NavLeaf(props: {
  item: NavItem;
  selected: string | null;
  count?: number;
  favorited: boolean;
  onSelect: (item: NavItem) => void;
  onToggleFavorite: (id: string) => void;
}) {
  const { item, selected, count, favorited, onSelect, onToggleFavorite } = props;
  return (
    <button className={'leaf' + (item.id === selected ? ' active' : '')} onClick={() => onSelect(item)}>
      <span className="leaf-label">{item.label}</span>
      {count !== undefined && <span className="nav-badge">{count}</span>}
      <span
        className={'fav-star' + (favorited ? ' on' : '')}
        title={favorited ? 'Unpin' : 'Pin to Favorites'}
        onClick={(e) => {
          e.stopPropagation();
          onToggleFavorite(item.id);
        }}
      >
        {favorited ? '★' : '☆'}
      </span>
    </button>
  );
}

function NavTree(props: {
  categories: NavCategory[];
  counts: Record<string, number>;
  favorites: string[];
  selected: string | null;
  onSelect: (item: NavItem) => void;
  onToggleFavorite: (id: string) => void;
}) {
  const { categories, counts, favorites, selected, onSelect, onToggleFavorite } = props;
  const [open, setOpen] = useState<Set<string>>(new Set());

  const allItems = allNavItems(categories);
  const favItems = favorites.map((id) => allItems.find((i) => i.id === id)).filter((i): i is NavItem => Boolean(i));

  // Open the category (and, for a nested custom-resource kind, its API-group sub-group)
  // that holds the current selection.
  useEffect(() => {
    const labelsToOpen: string[] = [];
    categories.forEach((c) => {
      const directHit = c.items.some((i) => i.id === selected);
      const group = (c.subgroups ?? []).find((g) => g.items.some((i) => i.id === selected));
      if (directHit || group) {
        labelsToOpen.push(c.label);
      }
      if (group) {
        labelsToOpen.push(group.label);
      }
    });
    if (labelsToOpen.length > 0) {
      setOpen((prev) => {
        if (labelsToOpen.every((l) => prev.has(l))) {
          return prev;
        }
        const next = new Set(prev);
        labelsToOpen.forEach((l) => next.add(l));
        return next;
      });
    }
  }, [categories, selected]);

  const toggle = (label: string, isOpen: boolean) =>
    setOpen((prev) => {
      const next = new Set(prev);
      if (isOpen) {
        next.add(label);
      } else {
        next.delete(label);
      }
      return next;
    });

  return (
    <div className="tree">
      {favItems.length > 0 && (
        <div className="fav-section">
          <div className="fav-header">★ Favorites</div>
          <ul>
            {favItems.map((it) => (
              <li key={it.id}>
                <NavLeaf
                  item={it}
                  selected={selected}
                  count={counts[it.id]}
                  favorited
                  onSelect={onSelect}
                  onToggleFavorite={onToggleFavorite}
                />
              </li>
            ))}
          </ul>
        </div>
      )}
      {categories.map((cat) => {
        // A category with a single kind and no sub-groups (Cluster→Nodes, Namespaces,
        // Events) is redundant as a collapsible group — render it as a top-level leaf.
        if (cat.items.length === 1 && (cat.subgroups?.length ?? 0) === 0) {
          const it = cat.items[0];
          return (
            <div className="top-leaf" key={cat.label}>
              <NavLeaf
                item={it}
                selected={selected}
                count={counts[it.id]}
                favorited={favorites.includes(it.id)}
                onSelect={onSelect}
                onToggleFavorite={onToggleFavorite}
              />
            </div>
          );
        }
        return renderGroup(cat, false);
      })}
    </div>
  );

  function renderLeaves(items: NavItem[]) {
    return (
      <ul>
        {items.map((it) => (
          <li key={it.id}>
            <NavLeaf
              item={it}
              selected={selected}
              count={counts[it.id]}
              favorited={favorites.includes(it.id)}
              onSelect={onSelect}
              onToggleFavorite={onToggleFavorite}
            />
          </li>
        ))}
      </ul>
    );
  }

  function renderGroup(cat: NavCategory, nested: boolean) {
    const subgroups = cat.subgroups ?? [];
    const holdsSelected =
      cat.items.some((i) => i.id === selected) || subgroups.some((g) => g.items.some((i) => i.id === selected));
    return (
      <details
        key={cat.label}
        className={'group' + (nested ? ' subgroup' : '') + (holdsSelected ? ' holds-selected' : '')}
        open={open.has(cat.label)}
        onToggle={(e) => toggle(cat.label, (e.currentTarget as HTMLDetailsElement).open)}
      >
        <summary>
          <span className="chev">▸</span>
          <span className="cat-label">{cat.label}</span>
          <span className="nav-badge">{categoryBadge(cat, counts)}</span>
        </summary>
        {cat.items.length > 0 && renderLeaves(cat.items)}
        {subgroups.map((g) => renderGroup(g, true))}
      </details>
    );
  }
}

function ResourceTable(props: {
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
  onRowAction: (action: RowAction, obj: KubeObject) => void;
  fetchChildren?: (obj: KubeObject) => Promise<KubeObject[]>;
}) {
  const { objects, columns: cols, namespaced, loading, selectedKey, selection, onToggleRow, onToggleAll, onOpen, onNamespaceClick, authed, onRowAction, fetchChildren } =
    props;
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

  const childPodRow = (p: KubeObject, parentKey: string) => {
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
      <tbody>
        {sorted.flatMap((o) => {
          const rowKey = objKey(o);
          const isExpanded = expanded.has(rowKey);
          const rows = [
          <tr
            key={rowKey}
            className={
              (objKey(o) === selectedKey ? 'row-active' : '') + (selection.has(objKey(o)) ? ' row-checked' : '')
            }
            onClick={() => onOpen(o)}
          >
            <td className="chk" onClick={(e) => e.stopPropagation()}>
              <input
                type="checkbox"
                checked={selection.has(objKey(o))}
                onChange={() => onToggleRow(objKey(o))}
              />
            </td>
            <td className="name">
              {fetchChildren && (
                <button
                  className="tree-toggle"
                  title={isExpanded ? 'Collapse' : 'Show pods'}
                  onClick={(e) => {
                    e.stopPropagation();
                    toggleExpand(o);
                  }}
                >
                  {isExpanded ? '▾' : '▸'}
                </button>
              )}
              {objName(o)}
            </td>
            {showNs &&
              (onNamespaceClick && objNs(o) ? (
                <td>
                  <button
                    className="cell-link"
                    onClick={(e) => {
                      e.stopPropagation();
                      onNamespaceClick(objNs(o) as string);
                    }}
                  >
                    {objNs(o)}
                  </button>
                </td>
              ) : (
                <td>{objNs(o) ?? '—'}</td>
              ))}
            {cols.map((c) => {
              const cell = c.render(o);
              const text = typeof cell === 'string' ? cell : null;
              const tone =
                text === null ? '' : c.header === 'Status' ? statusTone(text) : c.header === 'Ready' ? readyTone(text) : '';
              return (
                <td key={c.key}>{tone ? <span className={'status-pill status-' + tone}>{text}</span> : cell}</td>
              );
            })}
            {showAge && <td>{age(o.metadata?.creationTimestamp)}</td>}
            <td className="rowmenu-cell" onClick={(e) => e.stopPropagation()}>
              <RowMenu authed={authed} isPod={(o.kind ?? '') === 'Pod'} onAction={(a) => onRowAction(a, o)} />
            </td>
          </tr>,
          ];
          if (isExpanded) {
            const kids = children[rowKey];
            if (kids === null || kids === undefined) {
              rows.push(
                <tr key={rowKey + '>loading'} className="child-row">
                  <td colSpan={totalCols} className="child-msg">
                    Loading pods…
                  </td>
                </tr>,
              );
            } else if (kids.length === 0) {
              rows.push(
                <tr key={rowKey + '>empty'} className="child-row">
                  <td colSpan={totalCols} className="child-msg">
                    No pods.
                  </td>
                </tr>,
              );
            } else {
              kids.forEach((p) => rows.push(childPodRow(p, rowKey)));
            }
          }
          return rows;
        })}
      </tbody>
    </table>
  );
}

const SCALABLE = ['Deployment', 'StatefulSet', 'ReplicaSet'];
const RESTARTABLE = ['Deployment', 'StatefulSet', 'DaemonSet'];
const ROLLBACKABLE = ['Deployment', 'StatefulSet'];

function Detail(props: {
  cluster: string;
  resourceId: string;
  obj: KubeObject;
  initialEdit: boolean;
  authed: boolean;
  onRequireAuth: () => void;
  onAuthExpired: () => void;
  onNavigate: (kind: string, ns?: string) => void;
  onHelmRelease: (namespace: string, name: string) => void;
  onTerminal: (namespace: string, pod: string, containers: string[]) => void;
  onLogs: (namespace: string, pod: string, containers: string[]) => void;
  onForward: (kind: string, namespace: string, name: string, ports: number[]) => void;
  onClose: () => void;
}) {
  const { cluster, resourceId, obj, initialEdit, authed, onRequireAuth, onAuthExpired, onNavigate, onHelmRelease, onTerminal, onLogs, onForward, onClose } =
    props;
  const [tab, setTab] = useState<'overview' | 'yaml' | 'events' | 'metrics'>(initialEdit ? 'yaml' : 'overview');
  const [yaml, setYaml] = useState<string | null>(null);
  const [yamlError, setYamlError] = useState<string | null>(null);
  const [hideManaged, setHideManaged] = useState(true);
  const [copied, setCopied] = useState(false);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');
  const [events, setEvents] = useState<EventSummary[] | null>(null);
  const [eventsError, setEventsError] = useState<string | null>(null);
  const [replicas, setReplicas] = useState(1);
  const [busy, setBusy] = useState(false);
  const [actionMsg, setActionMsg] = useState<string | null>(null);
  const [actionErr, setActionErr] = useState(false);

  const kind = obj.kind ?? '';
  const name = objName(obj);
  const ns = objNs(obj) ?? '';
  const isNode = kind === 'Node';
  const suspendable = kind === 'CronJob' || kind === 'Job';
  const suspended = Boolean((obj.spec as Record<string, unknown>)?.suspend);
  const podContainers = ((obj.spec as Record<string, unknown>)?.containers as { name?: string }[] | undefined ?? [])
    .map((c) => c.name ?? '')
    .filter(Boolean);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  useEffect(() => {
    if (tab !== 'yaml' || yaml !== null || yamlError !== null) {
      return;
    }
    let cancelled = false;
    api
      .yaml(cluster, resourceId, name, ns || undefined)
      .then((t) => {
        if (!cancelled) {
          setYaml(t);
        }
      })
      .catch((e) => {
        if (!cancelled) {
          setYamlError(String(e));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [tab, yaml, yamlError, cluster, resourceId, name, ns]);

  // Opened via the row "Edit" action → drop straight into YAML edit once it loads.
  const autoEditDone = useRef(false);
  useEffect(() => {
    if (initialEdit && !autoEditDone.current && yaml !== null && !yamlError) {
      autoEditDone.current = true;
      setDraft(hideManaged ? stripManagedFields(yaml) : yaml);
      setEditing(true);
    }
  }, [initialEdit, yaml, yamlError, hideManaged]);

  useEffect(() => {
    if (tab !== 'events' || events !== null || eventsError !== null) {
      return;
    }
    let cancelled = false;
    api
      .objectEvents(cluster, kind, name, ns || undefined)
      .then((e) => {
        if (!cancelled) {
          setEvents(e);
        }
      })
      .catch((e) => {
        if (!cancelled) {
          setEventsError(String(e));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [tab, events, eventsError, cluster, kind, name, ns]);

  const displayYaml = yaml === null ? null : hideManaged ? stripManagedFields(yaml) : yaml;
  const copy = () => {
    if (displayYaml) {
      navigator.clipboard?.writeText(displayYaml).then(
        () => {
          setCopied(true);
          window.setTimeout(() => setCopied(false), 1200);
        },
        () => undefined,
      );
    }
  };

  const applyYaml = async () => {
    setBusy(true);
    setActionMsg(null);
    setActionErr(false);
    try {
      const r = await api.apply(cluster, draft);
      setYaml(draft);
      setEditing(false);
      setActionMsg(`applied ${r.kind}/${r.name}`);
    } catch (e) {
      setActionErr(true);
      if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
        onAuthExpired();
        setActionMsg('Authentication failed — sign in again.');
      } else {
        setActionMsg(String(e));
      }
    } finally {
      setBusy(false);
    }
  };

  const act = async (fn: () => Promise<{ result: string }>, opts?: { confirm?: string; closeOnDone?: boolean }) => {
    if (opts?.confirm && !window.confirm(opts.confirm)) {
      return;
    }
    setBusy(true);
    setActionMsg(null);
    setActionErr(false);
    try {
      const r = await fn();
      setActionMsg(r.result);
      if (opts?.closeOnDone) {
        onClose();
      }
    } catch (e) {
      setActionErr(true);
      if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
        onAuthExpired();
        setActionMsg('Authentication failed — sign in again.');
      } else {
        setActionMsg(String(e));
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="drawer" role="dialog" aria-label={`${kind} ${name}`}>
      <div className="drawer-head">
        <div className="drawer-title">
          <span className="drawer-kind">{kind}</span>
          <span className="drawer-name">{name}</span>
        </div>
        <button className="drawer-close" title="Close (Esc)" onClick={onClose}>
          ×
        </button>
      </div>
      <div className="tabs">
        <button className={'tab' + (tab === 'overview' ? ' active' : '')} onClick={() => setTab('overview')}>
          Overview
        </button>
        <button className={'tab' + (tab === 'yaml' ? ' active' : '')} onClick={() => setTab('yaml')}>
          YAML
        </button>
        <button className={'tab' + (tab === 'events' ? ' active' : '')} onClick={() => setTab('events')}>
          Events
        </button>
        {kind === 'Pod' && (
          <button className={'tab' + (tab === 'metrics' ? ' active' : '')} onClick={() => setTab('metrics')}>
            Metrics
          </button>
        )}
      </div>
      <div className="drawer-body">
        {tab === 'overview' && <Overview obj={obj} onNavigate={onNavigate} onHelmRelease={onHelmRelease} />}
        {tab === 'events' && <EventsPane events={events} error={eventsError} />}
        {tab === 'metrics' && (
          <div className="charts vertical">
            <MetricChart cluster={cluster} target="pod-cpu" namespace={ns} name={name} label="CPU (cores)" />
            <MetricChart cluster={cluster} target="pod-mem" namespace={ns} name={name} label="Memory" />
          </div>
        )}
        {tab === 'yaml' && (
          <div className="yaml-pane">
            <div className="yaml-toolbar">
              {!editing ? (
                <>
                  <button className="btn" onClick={copy} disabled={!yaml}>
                    {copied ? 'Copied' : 'Copy'}
                  </button>
                  {authed && (
                    <button
                      className="btn"
                      disabled={!yaml}
                      onClick={() => {
                        setDraft(displayYaml ?? '');
                        setEditing(true);
                      }}
                    >
                      Edit
                    </button>
                  )}
                  <label className="yaml-toggle" title="Hide the verbose metadata.managedFields block">
                    <input type="checkbox" checked={hideManaged} onChange={(e) => setHideManaged(e.target.checked)} />
                    <span className="switch" />
                    Hide Managed Fields
                  </label>
                </>
              ) : (
                <>
                  <button className="btn primary" disabled={busy} onClick={applyYaml}>
                    Apply
                  </button>
                  <button className="btn" disabled={busy} onClick={() => setEditing(false)}>
                    Cancel
                  </button>
                </>
              )}
            </div>
            {yamlError && <div className="error">{yamlError}</div>}
            {!yamlError && yaml === null && <div className="empty">Loading…</div>}
            {displayYaml !== null && !editing && <YamlView text={displayYaml} />}
            {editing && (
              <textarea
                className="yaml-edit"
                value={draft}
                spellCheck={false}
                onChange={(e) => setDraft(e.target.value)}
              />
            )}
          </div>
        )}
      </div>

      <div className="drawer-actions">
        {!authed && (
          <button className="linkbtn strong" onClick={onRequireAuth}>
            Sign in to run actions
          </button>
        )}
        {authed && SCALABLE.includes(kind) && (
          <span className="act">
            <input
              type="number"
              min={0}
              className="repl"
              value={replicas}
              disabled={busy}
              onChange={(e) => setReplicas(Math.max(0, Number.parseInt(e.target.value || '0', 10)))}
            />
            <button className="btn" disabled={busy} onClick={() => act(() => api.scale(cluster, resourceId, ns, name, replicas))}>
              Scale
            </button>
          </span>
        )}
        {authed && RESTARTABLE.includes(kind) && (
          <button
            className="btn"
            disabled={busy}
            onClick={() => act(() => api.restart(cluster, resourceId, ns, name), { confirm: `Rolling-restart ${name}?` })}
          >
            Restart
          </button>
        )}
        {authed && ROLLBACKABLE.includes(kind) && (
          <button
            className="btn"
            disabled={busy}
            onClick={() =>
              act(() => api.rollback(cluster, resourceId, ns, name), {
                confirm: `Roll ${kind} ${name} back to its previous revision?`,
              })
            }
          >
            Rollback
          </button>
        )}
        {kind === 'Pod' && ns && (
          <button className="btn" onClick={() => onLogs(ns, name, podContainers)}>
            Logs
          </button>
        )}
        {authed && kind === 'Pod' && ns && (
          <button className="btn" onClick={() => onTerminal(ns, name, podContainers)}>
            Terminal
          </button>
        )}
        {authed && (kind === 'Pod' || kind === 'Service') && ns && (
          <button className="btn" onClick={() => onForward(kind, ns, name, objectPorts(kind, obj))}>
            Forward
          </button>
        )}
        {authed && isNode && (
          <>
            <button className="btn" disabled={busy} onClick={() => act(() => api.cordon(cluster, name), { confirm: `Cordon ${name}?` })}>
              Cordon
            </button>
            <button className="btn" disabled={busy} onClick={() => act(() => api.uncordon(cluster, name))}>
              Uncordon
            </button>
            <button
              className="btn danger"
              disabled={busy}
              onClick={() =>
                act(() => api.drain(cluster, name), {
                  confirm: `Drain ${name}? This cordons the node and evicts its pods (DaemonSet and mirror pods are kept).`,
                })
              }
            >
              Drain
            </button>
          </>
        )}
        {authed && kind === 'CronJob' && ns && (
          <button
            className="btn"
            disabled={busy}
            onClick={() => act(() => api.trigger(cluster, resourceId, ns, name), { confirm: `Trigger a manual run of ${name}?` })}
          >
            Trigger
          </button>
        )}
        {authed && suspendable && ns && (
          <button
            className="btn"
            disabled={busy}
            onClick={() =>
              act(() => api.suspend(cluster, resourceId, ns, name, !suspended), {
                confirm: `${suspended ? 'Resume' : 'Suspend'} ${kind} ${name}?`,
              })
            }
          >
            {suspended ? 'Resume' : 'Suspend'}
          </button>
        )}
        {authed && !isNode && ns && (
          <button
            className="btn danger"
            disabled={busy}
            onClick={() =>
              act(() => api.del(cluster, resourceId, ns, name), {
                confirm: `Delete ${kind} ${name}? This cannot be undone.`,
                closeOnDone: true,
              })
            }
          >
            Delete
          </button>
        )}
        {actionMsg && <span className={'act-msg' + (actionErr ? ' err' : '')}>{actionMsg}</span>}
      </div>
    </div>
  );
}

/** Collapsible Overview section — a Freelens-style accordion. */
function Accordion(props: { title: string; count?: number; defaultOpen?: boolean; children: ReactNode }) {
  const [open, setOpen] = useState(props.defaultOpen ?? true);
  return (
    <section className="ov-sec acc">
      <h3 className="acc-head" onClick={() => setOpen((o) => !o)}>
        <span className={'acc-caret' + (open ? ' open' : '')}>▸</span>
        {props.title}
        {props.count !== undefined && <span className="acc-count">{props.count}</span>}
      </h3>
      {open && <div className="acc-body">{props.children}</div>}
    </section>
  );
}

/** Secret data table — base64 values are masked until per-key Reveal decodes them. */
function SecretData(props: { data: Record<string, string> }) {
  const [revealed, setRevealed] = useState<Record<string, boolean>>({});
  const decode = (v: string) => {
    try {
      return atob(v);
    } catch {
      return '‹binary›';
    }
  };
  return (
    <table className="mini">
      <thead>
        <tr>
          <th>Key</th>
          <th>Value</th>
          <th />
        </tr>
      </thead>
      <tbody>
        {Object.keys(props.data).map((k) => {
          const shown = revealed[k];
          return (
            <tr key={k}>
              <td className="mono">{k}</td>
              <td className="mono">{shown ? decode(props.data[k]) : '••••••••'}</td>
              <td>
                <button className="linkbtn" onClick={() => setRevealed((r) => ({ ...r, [k]: !r[k] }))}>
                  {shown ? 'Hide' : 'Reveal'}
                </button>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function chipsOf(map: Record<string, string>) {
  return (
    <div className="chips">
      {Object.entries(map).map(([k, v]) => (
        <span className="chip" key={k}>
          {k}={v}
        </span>
      ))}
    </div>
  );
}

function Overview(props: {
  obj: KubeObject;
  onNavigate: (kind: string, ns?: string) => void;
  onHelmRelease: (namespace: string, name: string) => void;
}) {
  const { obj, onNavigate, onHelmRelease } = props;
  const kind = obj.kind ?? '';
  const md = obj.metadata ?? {};
  const spec = (obj.spec as Record<string, unknown>) ?? {};
  const status = (obj.status as Record<string, unknown>) ?? {};
  const labels = md.labels ?? {};
  const annotations = md.annotations ?? {};
  const owners = md.ownerReferences ?? [];
  // Helm stamps managed objects with these; surface the owning release.
  const helmRelease = annotations['meta.helm.sh/release-name'];
  const helmReleaseNs = annotations['meta.helm.sh/release-namespace'] ?? md.namespace ?? '';
  const conditions = (status.conditions as Record<string, unknown>[]) ?? [];
  const containers = (spec.containers as Record<string, unknown>[]) ?? [];
  const containerStatuses = (status.containerStatuses as Record<string, unknown>[]) ?? [];
  const statusFor = (n: string) => containerStatuses.find((c) => c.name === n);
  const restartsFor = (n: string) => Number(statusFor(n)?.restartCount ?? 0);
  const readyFor = (n: string) => Boolean(statusFor(n)?.ready);

  // Kind-specific projections.
  const nodeName = spec.nodeName as string | undefined;
  const podIP = status.podIP as string | undefined;
  const hostIP = status.hostIP as string | undefined;
  const qos = status.qosClass as string | undefined;
  const svcType = spec.type as string | undefined;
  const clusterIP = spec.clusterIP as string | undefined;
  const nodeInfo = status.nodeInfo as Record<string, string> | undefined;
  const addresses = (status.addresses as { type: string; address: string }[] | undefined) ?? [];
  const internalIP = addresses.find((a) => a.type === 'InternalIP')?.address;
  const capacity = status.capacity as Record<string, string> | undefined;
  const allocatable = status.allocatable as Record<string, string> | undefined;
  const taints = (spec.taints as Record<string, unknown>[] | undefined) ?? [];
  const tolerations = (spec.tolerations as Record<string, unknown>[] | undefined) ?? [];
  const nodeSelector = (spec.nodeSelector as Record<string, string> | undefined) ?? {};
  const volumes = (spec.volumes as Record<string, unknown>[] | undefined) ?? [];
  const servicePorts = (spec.ports as Record<string, unknown>[] | undefined) ?? [];
  // Service uses a flat selector; workloads nest it under matchLabels.
  const rawSelector = spec.selector as Record<string, unknown> | undefined;
  const selector = ((rawSelector?.matchLabels as Record<string, string> | undefined) ??
    (rawSelector as Record<string, string> | undefined) ??
    {}) as Record<string, string>;
  const selectorEntries = Object.entries(selector).filter(([, v]) => typeof v === 'string');
  const configData = (kind === 'ConfigMap' ? (obj.data as Record<string, string> | undefined) : undefined) ?? {};
  const secretData = (kind === 'Secret' ? (obj.data as Record<string, string> | undefined) : undefined) ?? {};
  const secretType = obj.type as string | undefined;
  const ingressRules = (spec.rules as Record<string, unknown>[] | undefined) ?? [];
  const ingressTls = (spec.tls as Record<string, unknown>[] | undefined) ?? [];

  const containerPorts = (c: Record<string, unknown>) =>
    ((c.ports as Record<string, unknown>[] | undefined) ?? [])
      .map((p) => `${p.containerPort}${p.protocol && p.protocol !== 'TCP' ? '/' + p.protocol : ''}`)
      .join(', ');
  const containerResources = (c: Record<string, unknown>) => {
    const req = (c.resources as Record<string, unknown> | undefined)?.requests as Record<string, string> | undefined;
    if (!req) {
      return '—';
    }
    return [req.cpu && `cpu ${req.cpu}`, req.memory && `mem ${req.memory}`].filter(Boolean).join(', ') || '—';
  };

  return (
    <div className="ov">
      <dl className="kv">
        <dt>Kind</dt>
        <dd>{obj.kind ?? '—'}</dd>
        {md.namespace && (
          <>
            <dt>Namespace</dt>
            <dd>{md.namespace}</dd>
          </>
        )}
        <dt>Name</dt>
        <dd>{md.name ?? '—'}</dd>
        {typeof status.phase === 'string' && (
          <>
            <dt>Status</dt>
            <dd>{status.phase as string}</dd>
          </>
        )}
        {nodeName && (
          <>
            <dt>Node</dt>
            <dd>
              <button className="cell-link" onClick={() => onNavigate('Node')}>
                {nodeName}
              </button>
            </dd>
          </>
        )}
        {podIP && (
          <>
            <dt>Pod IP</dt>
            <dd className="mono">{podIP}</dd>
          </>
        )}
        {hostIP && (
          <>
            <dt>Host IP</dt>
            <dd className="mono">{hostIP}</dd>
          </>
        )}
        {qos && (
          <>
            <dt>QoS Class</dt>
            <dd>{qos}</dd>
          </>
        )}
        {svcType && (
          <>
            <dt>Type</dt>
            <dd>{svcType}</dd>
          </>
        )}
        {clusterIP && (
          <>
            <dt>Cluster IP</dt>
            <dd className="mono">{clusterIP}</dd>
          </>
        )}
        {internalIP && (
          <>
            <dt>Internal IP</dt>
            <dd className="mono">{internalIP}</dd>
          </>
        )}
        {nodeInfo?.kubeletVersion && (
          <>
            <dt>Kubelet</dt>
            <dd>{nodeInfo.kubeletVersion}</dd>
          </>
        )}
        {secretType && (
          <>
            <dt>Secret Type</dt>
            <dd className="mono">{secretType}</dd>
          </>
        )}
        <dt>Created</dt>
        <dd>
          {age(md.creationTimestamp)}
          {md.creationTimestamp ? ` · ${md.creationTimestamp}` : ''}
        </dd>
        {helmRelease && (
          <>
            <dt>Managed By</dt>
            <dd>
              <span className="helm-badge">Helm</span>{' '}
              <button
                className="cell-link"
                title="Open this release's resources"
                onClick={() => onHelmRelease(helmReleaseNs, helmRelease)}
              >
                {helmRelease}
                {helmReleaseNs ? ` (${helmReleaseNs})` : ''}
              </button>
            </dd>
          </>
        )}
        {owners.length > 0 && (
          <>
            <dt>Controlled By</dt>
            <dd>
              {owners.map((o, i) => (
                <span key={o.kind + '/' + o.name}>
                  {i > 0 ? ', ' : ''}
                  <button className="cell-link" onClick={() => onNavigate(o.kind, md.namespace)}>
                    {o.kind}/{o.name}
                  </button>
                </span>
              ))}
            </dd>
          </>
        )}
      </dl>

      {Object.keys(labels).length > 0 && (
        <Accordion title="Labels" count={Object.keys(labels).length} defaultOpen={false}>
          <div className="chips">
            {Object.entries(labels).map(([k, v]) => (
              <span className="chip" key={k}>
                {k}={v}
              </span>
            ))}
          </div>
        </Accordion>
      )}

      {Object.keys(annotations).length > 0 && (
        <Accordion title="Annotations" count={Object.keys(annotations).length} defaultOpen={false}>
          <div className="chips">
            {Object.entries(annotations).map(([k, v]) => (
              <span className="chip subtle" key={k} title={`${k}=${v}`}>
                {k}={v.length > 48 ? v.slice(0, 48) + '…' : v}
              </span>
            ))}
          </div>
        </Accordion>
      )}

      {containers.length > 0 && (
        <Accordion title="Containers" count={containers.length}>
          <table className="mini">
            <thead>
              <tr>
                <th>Name</th>
                <th>Image</th>
                <th>Ports</th>
                <th>Requests</th>
                <th>Ready</th>
                <th>Restarts</th>
              </tr>
            </thead>
            <tbody>
              {containers.map((c) => {
                const cn = String(c.name ?? '');
                return (
                  <tr key={cn}>
                    <td>{cn}</td>
                    <td className="mono">{String(c.image ?? '')}</td>
                    <td>{containerPorts(c) || '—'}</td>
                    <td>{containerResources(c)}</td>
                    <td>{readyFor(cn) ? 'Yes' : 'No'}</td>
                    <td>{restartsFor(cn)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </Accordion>
      )}

      {servicePorts.length > 0 && (
        <Accordion title="Ports" count={servicePorts.length}>
          <table className="mini">
            <thead>
              <tr>
                <th>Name</th>
                <th>Port</th>
                <th>Target</th>
                <th>Protocol</th>
                <th>Node Port</th>
              </tr>
            </thead>
            <tbody>
              {servicePorts.map((p, i) => (
                <tr key={String(p.name ?? i)}>
                  <td>{String(p.name ?? '—')}</td>
                  <td>{String(p.port ?? '')}</td>
                  <td>{String(p.targetPort ?? '')}</td>
                  <td>{String(p.protocol ?? 'TCP')}</td>
                  <td>{String(p.nodePort ?? '—')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Accordion>
      )}

      {selectorEntries.length > 0 && (
        <Accordion title="Selector" count={selectorEntries.length}>
          {chipsOf(Object.fromEntries(selectorEntries))}
        </Accordion>
      )}

      {ingressRules.length > 0 && (
        <Accordion title="Rules" count={ingressRules.length}>
          <table className="mini">
            <thead>
              <tr>
                <th>Host</th>
                <th>Path</th>
                <th>Backend</th>
              </tr>
            </thead>
            <tbody>
              {ingressRules.flatMap((r, ri) => {
                const host = String(r.host ?? '*');
                const paths = ((r.http as Record<string, unknown>)?.paths as Record<string, unknown>[] | undefined) ?? [];
                if (paths.length === 0) {
                  return [
                    <tr key={ri}>
                      <td>{host}</td>
                      <td>—</td>
                      <td>—</td>
                    </tr>,
                  ];
                }
                return paths.map((p, pi) => {
                  const svc = (p.backend as Record<string, unknown>)?.service as Record<string, unknown> | undefined;
                  const port = (svc?.port as Record<string, unknown> | undefined)?.number;
                  return (
                    <tr key={ri + '-' + pi}>
                      <td>{host}</td>
                      <td className="mono">{String(p.path ?? '/')}</td>
                      <td className="mono">
                        {svc ? `${svc.name}${port ? ':' + port : ''}` : '—'}
                      </td>
                    </tr>
                  );
                });
              })}
            </tbody>
          </table>
          {ingressTls.length > 0 && (
            <div className="chips" style={{ marginTop: 8 }}>
              {ingressTls.flatMap((t, i) =>
                ((t.hosts as string[] | undefined) ?? []).map((h) => (
                  <span className="chip" key={i + h}>
                    TLS: {h}
                  </span>
                )),
              )}
            </div>
          )}
        </Accordion>
      )}

      {Object.keys(configData).length > 0 && (
        <Accordion title="Data" count={Object.keys(configData).length}>
          <table className="mini">
            <thead>
              <tr>
                <th>Key</th>
                <th>Value</th>
              </tr>
            </thead>
            <tbody>
              {Object.entries(configData).map(([k, v]) => (
                <tr key={k}>
                  <td className="mono">{k}</td>
                  <td className="mono">{v.length > 200 ? v.slice(0, 200) + '…' : v}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Accordion>
      )}

      {Object.keys(secretData).length > 0 && (
        <Accordion title="Data" count={Object.keys(secretData).length}>
          <SecretData data={secretData} />
        </Accordion>
      )}

      {Object.keys(nodeSelector).length > 0 && (
        <Accordion title="Node Selector" count={Object.keys(nodeSelector).length} defaultOpen={false}>
          {chipsOf(nodeSelector)}
        </Accordion>
      )}

      {tolerations.length > 0 && (
        <Accordion title="Tolerations" count={tolerations.length} defaultOpen={false}>
          <table className="mini">
            <thead>
              <tr>
                <th>Key</th>
                <th>Operator</th>
                <th>Value</th>
                <th>Effect</th>
              </tr>
            </thead>
            <tbody>
              {tolerations.map((t, i) => (
                <tr key={String(t.key ?? i)}>
                  <td className="mono">{String(t.key ?? '*')}</td>
                  <td>{String(t.operator ?? '')}</td>
                  <td>{String(t.value ?? '—')}</td>
                  <td>{String(t.effect ?? 'All')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Accordion>
      )}

      {volumes.length > 0 && (
        <Accordion title="Volumes" count={volumes.length} defaultOpen={false}>
          <table className="mini">
            <thead>
              <tr>
                <th>Name</th>
                <th>Type</th>
              </tr>
            </thead>
            <tbody>
              {volumes.map((v, i) => {
                const type = Object.keys(v).find((key) => key !== 'name') ?? '—';
                return (
                  <tr key={String(v.name ?? i)}>
                    <td>{String(v.name ?? '')}</td>
                    <td className="mono">{type}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </Accordion>
      )}

      {taints.length > 0 && (
        <Accordion title="Taints" count={taints.length}>
          <table className="mini">
            <thead>
              <tr>
                <th>Key</th>
                <th>Value</th>
                <th>Effect</th>
              </tr>
            </thead>
            <tbody>
              {taints.map((t, i) => (
                <tr key={String(t.key ?? i)}>
                  <td className="mono">{String(t.key ?? '')}</td>
                  <td>{String(t.value ?? '—')}</td>
                  <td>{String(t.effect ?? '')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Accordion>
      )}

      {nodeInfo && (
        <Accordion title="Node Info" defaultOpen={false}>
          <dl className="kv">
            {[
              ['OS Image', nodeInfo.osImage],
              ['Architecture', nodeInfo.architecture],
              ['Kernel', nodeInfo.kernelVersion],
              ['Container Runtime', nodeInfo.containerRuntimeVersion],
              ['Kube-Proxy', nodeInfo.kubeProxyVersion],
            ]
              .filter(([, v]) => v)
              .map(([k, v]) => (
                <Fragment key={k}>
                  <dt>{k}</dt>
                  <dd>{v}</dd>
                </Fragment>
              ))}
          </dl>
        </Accordion>
      )}

      {(capacity || allocatable) && (
        <Accordion title="Capacity" defaultOpen={false}>
          <table className="mini">
            <thead>
              <tr>
                <th>Resource</th>
                <th>Capacity</th>
                <th>Allocatable</th>
              </tr>
            </thead>
            <tbody>
              {Array.from(new Set([...Object.keys(capacity ?? {}), ...Object.keys(allocatable ?? {})])).map((r) => (
                <tr key={r}>
                  <td className="mono">{r}</td>
                  <td>{capacity?.[r] ?? '—'}</td>
                  <td>{allocatable?.[r] ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Accordion>
      )}

      {conditions.length > 0 && (
        <Accordion title="Conditions" count={conditions.length}>
          <table className="mini">
            <thead>
              <tr>
                <th>Type</th>
                <th>Status</th>
                <th>Reason</th>
              </tr>
            </thead>
            <tbody>
              {conditions.map((c, i) => (
                <tr key={String(c.type ?? i)}>
                  <td>{String(c.type ?? '')}</td>
                  <td>{String(c.status ?? '')}</td>
                  <td>{String(c.reason ?? '')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Accordion>
      )}
    </div>
  );
}

function EventsPane(props: { events: EventSummary[] | null; error: string | null }) {
  const { events, error } = props;
  const { sorted, sort, clickHeader } = useTableSort(events ?? [], 'age', (e, k) =>
    k === 'age' ? ageToSeconds(e.age) : (e[k as keyof EventSummary] as string) ?? '',
  );
  if (error) {
    return <div className="error">{error}</div>;
  }
  if (events === null) {
    return <div className="empty">Loading…</div>;
  }
  if (events.length === 0) {
    return <div className="empty">No events.</div>;
  }
  return (
    <table className="mini">
      <thead>
        <tr>
          <SortTh label="Type" colKey="type" sort={sort} onClick={clickHeader} />
          <SortTh label="Reason" colKey="reason" sort={sort} onClick={clickHeader} />
          <SortTh label="Message" colKey="message" sort={sort} onClick={clickHeader} />
          <SortTh label="Age" colKey="age" sort={sort} onClick={clickHeader} />
        </tr>
      </thead>
      <tbody>
        {sorted.map((e, i) => (
          <tr key={i} className={e.type === 'Warning' ? 'warn' : ''}>
            <td>{e.type}</td>
            <td>{e.reason}</td>
            <td>{e.message}</td>
            <td>{e.age}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function fmtValue(unit: string, v: number): string {
  if (unit === 'bytes') {
    return Math.round(v / 1048576) + 'Mi';
  }
  if (unit === 'cores') {
    return v < 1 ? Math.round(v * 1000) + 'm' : v.toFixed(2);
  }
  return String(Math.round(v));
}

function Sparkline(props: { series: MetricSeries | null }) {
  const { series } = props;
  if (series === null) {
    return <div className="empty">Loading…</div>;
  }
  if (!series.available) {
    return <div className="empty">Graphs need a Prometheus / VictoriaMetrics backend.</div>;
  }
  if (series.points.length === 0) {
    return <div className="empty">No data.</div>;
  }
  const width = 600;
  const height = 120;
  const pad = 6;
  const vals = series.points.map((p) => p.v);
  const min = Math.min(...vals);
  const max = Math.max(...vals);
  const span = max - min || 1;
  const t0 = series.points[0].t;
  const tspan = series.points[series.points.length - 1].t - t0 || 1;
  const x = (t: number) => pad + ((t - t0) / tspan) * (width - 2 * pad);
  const y = (v: number) => height - pad - ((v - min) / span) * (height - 2 * pad);
  const line = series.points.map((p, i) => (i ? 'L' : 'M') + x(p.t).toFixed(1) + ' ' + y(p.v).toFixed(1)).join(' ');
  const area =
    `M${x(t0).toFixed(1)} ${(height - pad).toFixed(1)} ` +
    series.points.map((p) => 'L' + x(p.t).toFixed(1) + ' ' + y(p.v).toFixed(1)).join(' ') +
    ` L${x(series.points[series.points.length - 1].t).toFixed(1)} ${(height - pad).toFixed(1)} Z`;
  return (
    <div className="spark">
      <svg className="spark-svg" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none">
        <path className="spark-area" d={area} />
        <path className="spark-line" d={line} />
      </svg>
      <div className="spark-meta">
        now {fmtValue(series.unit, vals[vals.length - 1])} · peak {fmtValue(series.unit, max)}
      </div>
    </div>
  );
}

function MetricChart(props: { cluster: string; target: string; namespace?: string; name?: string; label: string }) {
  const { cluster, target, namespace, name, label } = props;
  const [series, setSeries] = useState<MetricSeries | null>(null);
  useEffect(() => {
    let cancelled = false;
    setSeries(null);
    api
      .metricGraph(cluster, target, { namespace, name, minutes: 60 })
      .then((s) => !cancelled && setSeries(s))
      .catch(() => !cancelled && setSeries({ available: false, unit: '', points: [] }));
    return () => {
      cancelled = true;
    };
  }, [cluster, target, namespace, name]);
  return (
    <div className="chart">
      <div className="chart-title">{label}</div>
      <Sparkline series={series} />
    </div>
  );
}

// Parse a compact age string (e.g. "45s", "5m", "2h", "18d") to seconds, for chronological sorting.
function ageToSeconds(age: string): number {
  const m = /^(\d+)([smhd])$/.exec(age.trim());
  if (!m) {
    return 0;
  }
  const mult: Record<string, number> = { s: 1, m: 60, h: 3600, d: 86400 };
  return Number(m[1]) * (mult[m[2]] ?? 1);
}

// Reusable column-sorting for the simple record tables (mirrors ResourceTable's UX).
type SortState = { key: string; dir: number };

function useTableSort<T>(rows: T[], initialKey: string, value: (row: T, key: string) => string | number) {
  const [sort, setSort] = useState<SortState>({ key: initialKey, dir: 1 });
  const sorted = [...rows].sort((a, b) => {
    const va = value(a, sort.key);
    const vb = value(b, sort.key);
    if (typeof va === 'number' && typeof vb === 'number') {
      return (va - vb) * sort.dir;
    }
    return String(va).localeCompare(String(vb), undefined, { numeric: true }) * sort.dir;
  });
  const clickHeader = (key: string) => setSort((prev) => (prev.key === key ? { key, dir: -prev.dir } : { key, dir: 1 }));
  return { sorted, sort, clickHeader };
}

function SortTh(props: { label: string; colKey: string; sort: SortState; onClick: (key: string) => void }) {
  const { label, colKey, sort, onClick } = props;
  return (
    <th className="sortable" onClick={() => onClick(colKey)}>
      {label}
      {sort.key === colKey && <span className="sort-ind">{sort.dir === 1 ? ' ▲' : ' ▼'}</span>}
    </th>
  );
}

function PortForwards(props: { cluster: string; authed: boolean; onRequireAuth: () => void }) {
  const { cluster, authed, onRequireAuth } = props;
  const [forwards, setForwards] = useState<PortForward[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const refresh = () =>
    api
      .portForwards(cluster)
      .then((f) => setForwards(f))
      .catch((e) => setError(String(e)));

  // Poll so status (Active/Closed/Failed) stays current as connections come and go.
  useEffect(() => {
    let cancelled = false;
    setForwards(null);
    setError(null);
    const tick = () => {
      if (cancelled) {
        return;
      }
      api
        .portForwards(cluster)
        .then((f) => !cancelled && setForwards(f))
        .catch((e) => !cancelled && setError(String(e)));
    };
    tick();
    const timer = window.setInterval(tick, 3000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [cluster]);

  const stop = (id: string) => {
    if (!authed) {
      onRequireAuth();
      return;
    }
    setBusy(id);
    api
      .stopPortForward(cluster, id)
      .then(() => refresh())
      .catch((e) => setError(String(e)))
      .finally(() => setBusy(null));
  };

  const { sorted, sort, clickHeader } = useTableSort(forwards ?? [], 'name', (f, k) => {
    if (k === 'remotePort') {
      return f.remotePort;
    }
    if (k === 'localPort') {
      return f.localPort;
    }
    return (f[k as keyof PortForward] as string) ?? '';
  });

  return (
    <div className="overview">
      <div className="content-head">
        <h1>Port Forwards</h1>
        <span className="count">{forwards ? `${forwards.length} items` : ''}</span>
      </div>
      <p className="modal-note">
        Forwards bind on the kweblens host. Reach a forward at <code>host:localPort</code> (loopback unless configured
        otherwise). Start one from a Pod or Service detail.
      </p>
      {error && <div className="error">{error}</div>}
      {forwards === null ? (
        <div className="empty">Loading…</div>
      ) : forwards.length === 0 ? (
        <div className="empty">No active forwards.</div>
      ) : (
        <table className="grid">
          <thead>
            <tr>
              <SortTh label="Name" colKey="name" sort={sort} onClick={clickHeader} />
              <SortTh label="Namespace" colKey="namespace" sort={sort} onClick={clickHeader} />
              <SortTh label="Kind" colKey="kind" sort={sort} onClick={clickHeader} />
              <SortTh label="Pod Port" colKey="remotePort" sort={sort} onClick={clickHeader} />
              <SortTh label="Local Port" colKey="localPort" sort={sort} onClick={clickHeader} />
              <SortTh label="Protocol" colKey="protocol" sort={sort} onClick={clickHeader} />
              <SortTh label="Status" colKey="status" sort={sort} onClick={clickHeader} />
              <th />
            </tr>
          </thead>
          <tbody>
            {sorted.map((f) => (
              <tr key={f.id}>
                <td className="name">{f.name}</td>
                <td>{f.namespace}</td>
                <td>{f.kind}</td>
                <td>{f.remotePort}</td>
                <td title={`${f.address}:${f.localPort}`}>{f.localPort}</td>
                <td>{f.protocol}</td>
                <td>
                  <span className={'pf-status pf-' + f.status.toLowerCase()}>{f.status}</span>
                </td>
                <td>
                  <button className="btn" disabled={busy === f.id} onClick={() => stop(f.id)}>
                    Stop
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function ForwardModal(props: {
  cluster: string;
  kind: string;
  namespace: string;
  name: string;
  ports: number[];
  onClose: () => void;
  onStarted: () => void;
  onAuthExpired: () => void;
}) {
  const { cluster, kind, namespace, name, ports, onClose, onStarted, onAuthExpired } = props;
  const [remotePort, setRemotePort] = useState(ports[0] ? String(ports[0]) : '');
  const [localPort, setLocalPort] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const remote = Number.parseInt(remotePort, 10);
    if (!Number.isFinite(remote) || remote <= 0) {
      setError('Enter a valid pod port.');
      return;
    }
    const local = localPort.trim() ? Number.parseInt(localPort, 10) : undefined;
    setBusy(true);
    setError(null);
    api
      .startPortForward(cluster, { kind, namespace, name, remotePort: remote, localPort: local })
      .then(() => onStarted())
      .catch((err) => {
        if (err instanceof ApiError && err.status === 401) {
          onAuthExpired();
        }
        setError(String(err));
        setBusy(false);
      });
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <form className="modal" onClick={(e) => e.stopPropagation()} onSubmit={submit}>
        <h2>Forward {kind}</h2>
        <p className="modal-note">
          {namespace}/{name} — binds a local port on the kweblens host to a port on this {kind.toLowerCase()}.
        </p>
        {error && <div className="error">{error}</div>}
        <label>
          <span>Pod port</span>
          {ports.length > 0 ? (
            <select value={remotePort} onChange={(e) => setRemotePort(e.target.value)}>
              {ports.map((p) => (
                <option key={p} value={p}>
                  {p}
                </option>
              ))}
            </select>
          ) : (
            <input
              type="number"
              min={1}
              value={remotePort}
              onChange={(e) => setRemotePort(e.target.value)}
              autoFocus
            />
          )}
        </label>
        <label>
          <span>Local port (blank = auto)</span>
          <input type="number" min={0} value={localPort} onChange={(e) => setLocalPort(e.target.value)} />
        </label>
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button type="submit" className="btn primary" disabled={busy}>
            {busy ? 'Starting…' : 'Start forward'}
          </button>
        </div>
      </form>
    </div>
  );
}

type HelmAction =
  | { mode: 'install'; repository: string; chart: string; version: string }
  | {
      mode: 'upgrade';
      namespace: string;
      name: string;
      chart: string;
      chartVersion: string;
      repository?: string;
      version?: string;
    }
  | { mode: 'rollback'; namespace: string; name: string; revision: number };

function HelmView(props: {
  cluster: string;
  authed: boolean;
  onNavigate: (kind: string, ns?: string) => void;
  openResources?: { namespace: string; name: string } | null;
  onResourcesConsumed?: () => void;
  onRequireAuth: () => void;
  onAuthExpired: () => void;
}) {
  const { cluster, authed, onNavigate, openResources, onResourcesConsumed, onRequireAuth, onAuthExpired } = props;
  const [tab, setTab] = useState<'charts' | 'releases' | 'repositories'>('releases');
  const [action, setAction] = useState<HelmAction | null>(null);
  const [resourcesFor, setResourcesFor] = useState<{ namespace: string; name: string } | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);

  const onAction = (a: HelmAction) => (authed ? setAction(a) : onRequireAuth());

  // Deep-linked from a resource's "Managed By: Helm" — open that release's resources.
  useEffect(() => {
    if (openResources) {
      setTab('releases');
      setResourcesFor(openResources);
      onResourcesConsumed?.();
    }
  }, [openResources, onResourcesConsumed]);

  return (
    <div className="overview">
      <div className="content-head">
        <h1>Helm</h1>
      </div>
      <div className="tabs">
        <button className={'tab' + (tab === 'charts' ? ' active' : '')} onClick={() => setTab('charts')}>
          Charts
        </button>
        <button className={'tab' + (tab === 'releases' ? ' active' : '')} onClick={() => setTab('releases')}>
          Releases
        </button>
        <button className={'tab' + (tab === 'repositories' ? ' active' : '')} onClick={() => setTab('repositories')}>
          Repositories
        </button>
      </div>
      {tab === 'charts' ? (
        <HelmCharts cluster={cluster} authed={authed} onAction={onAction} />
      ) : tab === 'repositories' ? (
        <HelmRepos authed={authed} onRequireAuth={onRequireAuth} onAuthExpired={onAuthExpired} />
      ) : (
        <HelmReleases
          cluster={cluster}
          authed={authed}
          onAction={onAction}
          onResources={(namespace, name) => setResourcesFor({ namespace, name })}
          refreshKey={refreshKey}
        />
      )}
      {action && (
        <HelmActionModal
          cluster={cluster}
          action={action}
          onClose={() => setAction(null)}
          onApplied={() => {
            setAction(null);
            setTab('releases');
            setRefreshKey((k) => k + 1);
          }}
          onAuthExpired={onAuthExpired}
        />
      )}
      {resourcesFor && (
        <HelmResourcesModal
          cluster={cluster}
          namespace={resourcesFor.namespace}
          name={resourcesFor.name}
          onClose={() => setResourcesFor(null)}
          onOpen={(kind, ns) => {
            setResourcesFor(null);
            onNavigate(kind, ns);
          }}
        />
      )}
    </div>
  );
}

function HelmCharts(props: { cluster: string; authed: boolean; onAction: (a: HelmAction) => void }) {
  const { cluster, authed, onAction } = props;
  const [charts, setCharts] = useState<HelmChart[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState('');

  useEffect(() => {
    let cancelled = false;
    setCharts(null);
    setError(null);
    api
      .helmCharts(cluster)
      .then((c) => !cancelled && setCharts(c))
      .catch((e) => !cancelled && setError(String(e)));
    return () => {
      cancelled = true;
    };
  }, [cluster]);

  const q = query.trim().toLowerCase();
  const filtered = (charts ?? []).filter(
    (c) => !q || c.name.toLowerCase().includes(q) || (c.description ?? '').toLowerCase().includes(q),
  );
  const { sorted, sort, clickHeader } = useTableSort(
    filtered,
    'name',
    (c, k) => (c[k as keyof HelmChart] as string) ?? '',
  );

  return (
    <>
      <div className="content-head">
        <span className="count">{charts ? `${filtered.length} charts` : ''}</span>
        <div className="spacer" />
        <input
          className="search"
          type="search"
          placeholder="Search charts…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>
      {error && <div className="error">{error}</div>}
      {charts === null ? (
        <div className="empty">Loading…</div>
      ) : filtered.length === 0 ? (
        <div className="empty">No charts. Configure repositories under kweblens.helm.repositories.</div>
      ) : (
        <table className="grid">
          <thead>
            <tr>
              <SortTh label="Name" colKey="name" sort={sort} onClick={clickHeader} />
              <SortTh label="Description" colKey="description" sort={sort} onClick={clickHeader} />
              <SortTh label="Version" colKey="version" sort={sort} onClick={clickHeader} />
              <SortTh label="App Version" colKey="appVersion" sort={sort} onClick={clickHeader} />
              <SortTh label="Repository" colKey="repository" sort={sort} onClick={clickHeader} />
              <th />
            </tr>
          </thead>
          <tbody>
            {sorted.map((c) => (
              <tr key={c.repository + '/' + c.name}>
                <td className="name">{c.name}</td>
                <td className="muted">{c.description ?? '—'}</td>
                <td>{c.version}</td>
                <td>{c.appVersion ?? '—'}</td>
                <td>{c.repository}</td>
                <td>
                  {authed && (
                    <button
                      className="btn"
                      onClick={() =>
                        onAction({ mode: 'install', repository: c.repository, chart: c.name, version: c.version })
                      }
                    >
                      Install
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}

function HelmReleases(props: {
  cluster: string;
  authed: boolean;
  onAction: (a: HelmAction) => void;
  onResources: (namespace: string, name: string) => void;
  refreshKey: number;
}) {
  const { cluster, authed, onAction, onResources, refreshKey } = props;
  const [releases, setReleases] = useState<HelmRelease[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setReleases(null);
    setError(null);
    api
      .helmReleases(cluster)
      .then((r) => !cancelled && setReleases(r))
      .catch((e) => !cancelled && setError(String(e)));
    return () => {
      cancelled = true;
    };
  }, [cluster, refreshKey]);

  const { sorted, sort, clickHeader } = useTableSort(releases ?? [], 'name', (r, k) => {
    if (k === 'revision') {
      return r.revision;
    }
    if (k === 'updated') {
      return Date.parse(r.updated ?? '') || 0;
    }
    return (r[k as keyof HelmRelease] as string) ?? '';
  });

  return (
    <>
      <div className="content-head">
        <span className="count">{releases ? `${releases.length} releases` : ''}</span>
      </div>
      {error && <div className="error">{error}</div>}
      {releases === null ? (
        <div className="empty">Loading…</div>
      ) : releases.length === 0 ? (
        <div className="empty">No releases.</div>
      ) : (
        <table className="grid">
          <thead>
            <tr>
              <SortTh label="Name" colKey="name" sort={sort} onClick={clickHeader} />
              <SortTh label="Namespace" colKey="namespace" sort={sort} onClick={clickHeader} />
              <SortTh label="Chart" colKey="chart" sort={sort} onClick={clickHeader} />
              <SortTh label="Source" colKey="managedByKweblens" sort={sort} onClick={clickHeader} />
              <SortTh label="Revision" colKey="revision" sort={sort} onClick={clickHeader} />
              <SortTh label="Version" colKey="chartVersion" sort={sort} onClick={clickHeader} />
              <SortTh label="App Version" colKey="appVersion" sort={sort} onClick={clickHeader} />
              <SortTh label="Status" colKey="status" sort={sort} onClick={clickHeader} />
              <SortTh label="Updated" colKey="updated" sort={sort} onClick={clickHeader} />
              <th />
            </tr>
          </thead>
          <tbody>
            {sorted.map((r) => (
              <tr key={r.namespace + '/' + r.name}>
                <td className="name">{r.name}</td>
                <td>{r.namespace}</td>
                <td>{r.chart}</td>
                <td>
                  {r.managedByKweblens ? (
                    <span className="chip">kweblens</span>
                  ) : (
                    <span className="chip subtle">external</span>
                  )}
                </td>
                <td>{r.revision}</td>
                <td>
                  {r.chartVersion}
                  {r.updateAvailable && (
                    <span className="chip update-chip" title={`Latest ${r.latestVersion} in ${r.latestRepository}`}>
                      ↑ {r.latestVersion}
                    </span>
                  )}
                </td>
                <td>{r.appVersion}</td>
                <td>
                  <StatusBadge text={r.status} />
                </td>
                <td>{r.updated ? age(r.updated) : '—'}</td>
                <td className="row-actions">
                  <button className="btn" onClick={() => onResources(r.namespace, r.name)}>
                    Resources
                  </button>
                  {authed && r.updateAvailable && (
                    <button
                      className="btn primary"
                      title={`Upgrade to ${r.latestVersion}`}
                      onClick={() =>
                        onAction({
                          mode: 'upgrade',
                          namespace: r.namespace,
                          name: r.name,
                          chart: r.chart,
                          chartVersion: r.chartVersion,
                          repository: r.latestRepository ?? undefined,
                          version: r.latestVersion ?? undefined,
                        })
                      }
                    >
                      Update
                    </button>
                  )}
                  {authed && (
                    <>
                      <button
                        className="btn"
                        onClick={() =>
                          onAction({
                            mode: 'upgrade',
                            namespace: r.namespace,
                            name: r.name,
                            chart: r.chart,
                            chartVersion: r.chartVersion,
                          })
                        }
                      >
                        Upgrade
                      </button>
                      <button
                        className="btn"
                        disabled={r.revision <= 1}
                        onClick={() =>
                          onAction({
                            mode: 'rollback',
                            namespace: r.namespace,
                            name: r.name,
                            revision: r.revision - 1,
                          })
                        }
                      >
                        Rollback
                      </button>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}

function HelmRepos(props: { authed: boolean; onRequireAuth: () => void; onAuthExpired: () => void }) {
  const { authed, onRequireAuth, onAuthExpired } = props;
  const [repos, setRepos] = useState<{ name: string; url: string }[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [busy, setBusy] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setRepos(null);
    setError(null);
    api
      .helmRepos()
      .then((r) => !cancelled && setRepos(r))
      .catch((e) => !cancelled && setError(String(e)));
    return () => {
      cancelled = true;
    };
  }, [refreshKey]);

  const fail = (e: unknown) => {
    if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
      onAuthExpired();
    }
    setError(String(e));
  };

  const add = () => {
    if (!authed) {
      onRequireAuth();
      return;
    }
    if (!name.trim() || !url.trim()) {
      return;
    }
    setBusy(true);
    setError(null);
    api
      .helmAddRepo(name.trim(), url.trim())
      .then(() => {
        setName('');
        setUrl('');
        setRefreshKey((k) => k + 1);
      })
      .catch(fail)
      .finally(() => setBusy(false));
  };

  const remove = (repo: string) => {
    if (!authed) {
      onRequireAuth();
      return;
    }
    if (!window.confirm(`Remove repository ${repo}?`)) {
      return;
    }
    setBusy(true);
    api
      .helmRemoveRepo(repo)
      .then(() => setRefreshKey((k) => k + 1))
      .catch(fail)
      .finally(() => setBusy(false));
  };

  return (
    <>
      <div className="content-head">
        <span className="count">{repos ? `${repos.length} repositories` : ''}</span>
      </div>
      {error && <div className="error">{error}</div>}
      {authed && (
        <div className="repo-add">
          <input placeholder="name" value={name} disabled={busy} onChange={(e) => setName(e.target.value)} />
          <input
            placeholder="https://charts.example.com"
            value={url}
            disabled={busy}
            className="repo-url"
            onChange={(e) => setUrl(e.target.value)}
          />
          <button className="btn primary" disabled={busy || !name.trim() || !url.trim()} onClick={add}>
            Add repository
          </button>
        </div>
      )}
      {repos === null ? (
        <div className="empty">Loading…</div>
      ) : repos.length === 0 ? (
        <div className="empty">No repositories. Add one above.</div>
      ) : (
        <table className="grid">
          <thead>
            <tr>
              <th>Name</th>
              <th>URL</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {repos.map((r) => (
              <tr key={r.name}>
                <td className="name">{r.name}</td>
                <td className="mono">{r.url}</td>
                <td className="row-actions">
                  {authed && (
                    <button className="btn danger" disabled={busy} onClick={() => remove(r.name)}>
                      Remove
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}

function HelmResourcesModal(props: {
  cluster: string;
  namespace: string;
  name: string;
  onClose: () => void;
  onOpen: (kind: string, namespace: string) => void;
}) {
  const { cluster, namespace, name, onClose, onOpen } = props;
  const [resources, setResources] = useState<HelmResourceRef[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .helmReleaseResources(cluster, namespace, name)
      .then((r) => !cancelled && setResources(r))
      .catch((e) => !cancelled && setError(String(e)));
    return () => {
      cancelled = true;
    };
  }, [cluster, namespace, name]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const { sorted, sort, clickHeader } = useTableSort(
    resources ?? [],
    'kind',
    (r, k) => (r[k as keyof HelmResourceRef] as string) ?? '',
  );

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal wide" onClick={(e) => e.stopPropagation()}>
        <h2>Resources</h2>
        <p className="modal-note">
          Objects managed by release <strong>{name}</strong> in <strong>{namespace}</strong> (from its manifest). Click
          a name to open it.
        </p>
        {error && <div className="error">{error}</div>}
        {resources === null ? (
          <div className="empty">Loading…</div>
        ) : resources.length === 0 ? (
          <div className="empty">No resources in this release's manifest.</div>
        ) : (
          <table className="grid">
            <thead>
              <tr>
                <SortTh label="Kind" colKey="kind" sort={sort} onClick={clickHeader} />
                <SortTh label="Namespace" colKey="namespace" sort={sort} onClick={clickHeader} />
                <SortTh label="Name" colKey="name" sort={sort} onClick={clickHeader} />
              </tr>
            </thead>
            <tbody>
              {sorted.map((r) => (
                <tr key={r.kind + '/' + r.namespace + '/' + r.name}>
                  <td>{r.kind}</td>
                  <td>{r.namespace}</td>
                  <td className="name">
                    <button className="cell-link" onClick={() => onOpen(r.kind, r.namespace)}>
                      {r.name}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}

function HelmActionModal(props: {
  cluster: string;
  action: HelmAction;
  onClose: () => void;
  onApplied: () => void;
  onAuthExpired: () => void;
}) {
  const { cluster, action, onClose, onApplied, onAuthExpired } = props;
  const [releaseName, setReleaseName] = useState(action.mode === 'install' ? action.chart : '');
  const [namespace, setNamespace] = useState('default');
  const [repository, setRepository] = useState(
    action.mode === 'install' ? action.repository : action.mode === 'upgrade' ? (action.repository ?? '') : '',
  );
  const [chart, setChart] = useState(action.mode === 'upgrade' || action.mode === 'install' ? action.chart : '');
  const [version, setVersion] = useState(
    action.mode === 'install'
      ? action.version
      : action.mode === 'upgrade'
        ? (action.version ?? action.chartVersion)
        : '',
  );
  const [valuesYaml, setValuesYaml] = useState('');
  const [revision, setRevision] = useState(action.mode === 'rollback' ? action.revision : 1);
  const [createNamespace, setCreateNamespace] = useState(false);
  const [preview, setPreview] = useState<HelmMutationResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const title = action.mode === 'install' ? 'Install chart' : action.mode === 'upgrade' ? 'Upgrade release' : 'Rollback release';

  const run = (dryRun: boolean) => {
    setBusy(true);
    setError(null);
    let p: Promise<HelmMutationResult>;
    if (action.mode === 'install') {
      p = api.helmInstall(cluster, { namespace, releaseName, repository, chart, version, valuesYaml, dryRun, createNamespace });
    } else if (action.mode === 'upgrade') {
      p = api.helmUpgrade(cluster, action.namespace, action.name, { repository, chart, version, valuesYaml, dryRun });
    } else {
      p = api.helmRollback(cluster, action.namespace, action.name, { revision, dryRun });
    }
    p.then((res) => (dryRun ? setPreview(res) : onApplied()))
      .catch((err) => {
        if (err instanceof ApiError && err.status === 401) {
          onAuthExpired();
        }
        setError(String(err));
      })
      .finally(() => setBusy(false));
  };

  const field = (label: string, node: ReactNode) => (
    <label>
      <span>{label}</span>
      {node}
    </label>
  );

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <form className="modal wide" onClick={(e) => e.stopPropagation()} onSubmit={(e) => e.preventDefault()}>
        <h2>{title}</h2>
        <p className="modal-note">Preview a dry-run first; Apply is enabled once the render succeeds.</p>
        {error && <div className="error">{error}</div>}

        {action.mode === 'install' && (
          <>
            {field('Chart', <input value={`${repository}/${chart}`} readOnly />)}
            {field('Version', <input value={version} onChange={(e) => setVersion(e.target.value)} />)}
            {field('Release name', <input value={releaseName} onChange={(e) => setReleaseName(e.target.value)} />)}
            {field('Namespace', <input value={namespace} onChange={(e) => setNamespace(e.target.value)} />)}
            <label className="check">
              <input type="checkbox" checked={createNamespace} onChange={(e) => setCreateNamespace(e.target.checked)} />
              <span>Create namespace if missing</span>
            </label>
          </>
        )}
        {action.mode === 'upgrade' && (
          <>
            {field('Release', <input value={`${action.namespace}/${action.name}`} readOnly />)}
            {field('Repository', <input value={repository} placeholder="repo name" onChange={(e) => setRepository(e.target.value)} />)}
            {field('Chart', <input value={chart} onChange={(e) => setChart(e.target.value)} />)}
            {field('Version', <input value={version} onChange={(e) => setVersion(e.target.value)} />)}
          </>
        )}
        {action.mode === 'rollback' && (
          <>
            {field('Release', <input value={`${action.namespace}/${action.name}`} readOnly />)}
            {field(
              'Roll back to revision',
              <input
                type="number"
                min={1}
                value={revision}
                onChange={(e) => setRevision(Math.max(1, Number.parseInt(e.target.value || '1', 10)))}
              />,
            )}
          </>
        )}
        {action.mode !== 'rollback' &&
          field(
            'Values (YAML, optional)',
            <textarea
              className="values"
              rows={5}
              value={valuesYaml}
              placeholder="key: value"
              onChange={(e) => setValuesYaml(e.target.value)}
            />,
          )}

        {preview && (
          <div className="helm-preview">
            <div className="preview-head">Rendered manifest (dry-run) — {preview.manifest ? '' : 'no manifest returned'}</div>
            {preview.manifest && <pre>{preview.manifest}</pre>}
          </div>
        )}

        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button type="button" className="btn" onClick={() => run(true)} disabled={busy}>
            {busy ? 'Rendering…' : 'Preview (dry-run)'}
          </button>
          <button type="button" className="btn primary" onClick={() => run(false)} disabled={busy || !preview}>
            {action.mode === 'install' ? 'Install' : action.mode === 'upgrade' ? 'Upgrade' : 'Rollback'}
          </button>
        </div>
      </form>
    </div>
  );
}

const WORKLOAD_KINDS = [
  { id: 'pods', label: 'Pods' },
  { id: 'deployments', label: 'Deployments' },
  { id: 'statefulsets', label: 'Stateful Sets' },
  { id: 'daemonsets', label: 'Daemon Sets' },
  { id: 'replicasets', label: 'Replica Sets' },
  { id: 'jobs', label: 'Jobs' },
  { id: 'cronjobs', label: 'Cron Jobs' },
];

function isHealthy(kindId: string, o: KubeObject): boolean {
  const s = (o.status as Record<string, unknown>) ?? {};
  const sp = (o.spec as Record<string, unknown>) ?? {};
  const n = (v: unknown) => (typeof v === 'number' ? v : 0);
  switch (kindId) {
    case 'pods':
      return s.phase === 'Running' || s.phase === 'Succeeded';
    case 'deployments':
    case 'statefulsets':
    case 'replicasets':
      // Scaled-to-zero counts as healthy (intentionally scaled down, not failing).
      return n(s.readyReplicas) === n(sp.replicas);
    case 'daemonsets':
      return n(s.numberReady) === n(s.desiredNumberScheduled);
    case 'jobs':
      return n(s.succeeded) > 0;
    default:
      return true;
  }
}

function WorkloadsOverview(props: { cluster: string }) {
  const { cluster } = props;
  const [counts, setCounts] = useState<Record<string, { total: number; ready: number }>>({});
  const [events, setEvents] = useState<EventSummary[] | null>(null);

  useEffect(() => {
    let cancelled = false;
    setCounts({});
    setEvents(null);
    WORKLOAD_KINDS.forEach((k) => {
      api
        .objects(cluster, k.id)
        .then((objs) => {
          if (cancelled) {
            return;
          }
          setCounts((prev) => ({
            ...prev,
            [k.id]: { total: objs.length, ready: objs.filter((o) => isHealthy(k.id, o)).length },
          }));
        })
        .catch(() => undefined);
    });
    api
      .events(cluster)
      .then((e) => !cancelled && setEvents(e))
      .catch(() => !cancelled && setEvents([]));
    return () => {
      cancelled = true;
    };
  }, [cluster]);

  return (
    <div className="overview">
      <h1 className="ov-title">Workloads</h1>
      <div className="ov-cards">
        {WORKLOAD_KINDS.map((k) => {
          const c = counts[k.id];
          const unhealthy = c ? c.total - c.ready : 0;
          return (
            <div className={'ov-card' + (unhealthy > 0 ? ' danger' : '')} key={k.id}>
              <div className="ov-num">{c ? c.total : '…'}</div>
              <div className="ov-lbl">
                {k.label}
                {c ? ` · ${c.ready} ready` : ''}
              </div>
            </div>
          );
        })}
      </div>
      <section className="ov-sec">
        <h3>Recent Events</h3>
        <EventsPane events={events ? events.slice(0, 25) : null} error={null} />
      </section>
    </div>
  );
}

function ClusterOverview(props: { cluster: string; name: string; masterUrl?: string; namespaceCount: number }) {
  const { cluster, name, masterUrl, namespaceCount } = props;
  const [nodes, setNodes] = useState<KubeObject[] | null>(null);
  const [warnings, setWarnings] = useState<EventSummary[] | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setNodes(null);
    setWarnings(null);
    setErr(null);
    api
      .objects(cluster, 'nodes')
      .then((n) => !cancelled && setNodes(n))
      .catch((e) => !cancelled && setErr(String(e)));
    api
      .events(cluster)
      .then((ev) => !cancelled && setWarnings(ev.filter((x) => x.type === 'Warning')))
      .catch(() => !cancelled && setWarnings([]));
    return () => {
      cancelled = true;
    };
  }, [cluster]);

  const nodeReady = (o: KubeObject): boolean => {
    const conds = ((o.status as Record<string, unknown>)?.conditions as Record<string, unknown>[]) ?? [];
    const r = conds.find((c) => c.type === 'Ready');
    return r ? r.status === 'True' : false;
  };
  const readyNodes = (nodes ?? []).filter(nodeReady).length;
  const warnSort = useTableSort(warnings ?? [], 'age', (w, k) =>
    k === 'age' ? ageToSeconds(w.age) : (w[k as keyof EventSummary] as string) ?? '',
  );

  return (
    <div className="overview">
      <h1 className="ov-title">{name}</h1>
      <div className="ov-cards">
        <div className="ov-card">
          <div className="ov-num">{nodes ? nodes.length : '…'}</div>
          <div className="ov-lbl">Nodes{nodes ? ` · ${readyNodes} ready` : ''}</div>
        </div>
        <div className="ov-card">
          <div className="ov-num">{namespaceCount}</div>
          <div className="ov-lbl">Namespaces</div>
        </div>
        <div className={'ov-card' + (warnings && warnings.length > 0 ? ' danger' : '')}>
          <div className="ov-num">{warnings ? warnings.length : '…'}</div>
          <div className="ov-lbl">Warnings</div>
        </div>
      </div>
      {masterUrl && (
        <div className="ov-api">
          API server: <span className="mono">{masterUrl}</span>
        </div>
      )}
      {err && <div className="error">{err}</div>}
      <div className="charts">
        <MetricChart cluster={cluster} target="cluster-cpu" label="Cluster CPU (cores)" />
        <MetricChart cluster={cluster} target="cluster-mem" label="Cluster Memory" />
      </div>
      <section className="ov-sec">
        <h3>Warnings</h3>
        {warnings === null ? (
          <div className="empty">Loading…</div>
        ) : warnings.length === 0 ? (
          <div className="empty">No warnings.</div>
        ) : (
          <table className="mini">
            <thead>
              <tr>
                <SortTh label="Reason" colKey="reason" sort={warnSort.sort} onClick={warnSort.clickHeader} />
                <SortTh label="Object" colKey="object" sort={warnSort.sort} onClick={warnSort.clickHeader} />
                <SortTh label="Message" colKey="message" sort={warnSort.sort} onClick={warnSort.clickHeader} />
                <SortTh label="Age" colKey="age" sort={warnSort.sort} onClick={warnSort.clickHeader} />
              </tr>
            </thead>
            <tbody>
              {warnSort.sorted.slice(0, 30).map((w, i) => (
                <tr key={i} className="warn">
                  <td>{w.reason}</td>
                  <td>{w.object}</td>
                  <td>{w.message}</td>
                  <td>{w.age}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}

function LoginModal(props: { onCancel: () => void; onSubmit: (user: string, pass: string) => Promise<boolean> }) {
  const { onCancel, onSubmit } = props;
  const [user, setUser] = useState('admin');
  const [pass, setPass] = useState('');
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onCancel();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onCancel]);

  return (
    <div className="modal-backdrop" onClick={onCancel}>
      <form
        className="modal"
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => {
          e.preventDefault();
          setBusy(true);
          setFailed(false);
          onSubmit(user, pass).then((ok) => {
            setBusy(false);
            if (!ok) {
              setFailed(true);
            }
          });
        }}
      >
        <h2>Sign in</h2>
        <p className="modal-note">Credentials are kept in memory for this tab only and sent over HTTP Basic.</p>
        {failed && <div className="error">Invalid credentials.</div>}
        <label>
          <span>Username</span>
          <input value={user} onChange={(e) => setUser(e.target.value)} autoFocus />
        </label>
        <label>
          <span>Password</span>
          <input type="password" value={pass} onChange={(e) => setPass(e.target.value)} />
        </label>
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onCancel} disabled={busy}>
            Cancel
          </button>
          <button type="submit" className="btn primary" disabled={busy}>
            {busy ? 'Signing in…' : 'Sign in'}
          </button>
        </div>
      </form>
    </div>
  );
}

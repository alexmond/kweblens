import type { FormEvent, ReactNode } from 'react';
import { useEffect, useMemo, useRef, useState } from 'react';

import { ApiError, api, clusterBase } from './api';
import { auth } from './auth';
import type { ColumnDef } from './columns';
import { columnsFor, printerColumnDefs } from './columns';
import {
  ageToSeconds,
  containerNames,
  gib,
  initials,
  objKey,
  objName,
  objNs,
  objSpec,
  objStatus,
  parseCpuCores,
  parseMemBytes,
  toNum,
} from './kube';
import { useEscapeKey, useTableSort } from './hooks';
import { ContainerSquares, MetricChart, SortTh, UsageBar } from './ui';
import { DialogProvider, useDialog } from './dialog';
import type { DialogApi } from './dialog';
import { ROW_ACTIONS } from './rowMenu';
import { ResourceTable } from './resourceTable';
import { HelmView } from './helm';
import { Detail, EventsPane } from './detail';
import { DockArea } from './dock';
import type { DockSession } from './dock';
import type { RowAction } from './rowMenu';
import type { DockKind } from './types';
import type {
  ClusterInfo,
  EventSummary,
  HelmRelease,
  KubeObject,
  NavCategory,
  NavItem,
  NodeDiskUsage,
  PortForward,
  UsageSummary,
} from './types';

// Ids of the synthetic (client-only) nav items — dashboards and Helm/Port-Forward views,
// not resource kinds. Centralized so the ids we build match the ids we test against.
const NAV = {
  overviewCluster: 'overview:cluster',
  overviewWorkloads: 'overview:workloads',
  portForwards: 'portforward:list',
  helmCharts: 'helm:charts',
  helmReleases: 'helm:releases',
  helmRepositories: 'helm:repositories',
} as const;

// Prefixes that mark an id as synthetic (client-only) rather than a resource kind.
const SYNTHETIC_PREFIXES = ['overview:', 'helm:', 'portforward:'] as const;

// Category labels that drive placement of the synthetic items in withSyntheticNav.
const CATEGORY = { cluster: 'Cluster', workloads: 'Workloads', network: 'Network', helm: 'Helm' } as const;

const HELM_VIEW_IDS: string[] = [NAV.helmCharts, NAV.helmReleases, NAV.helmRepositories];

// Synthetic nav items are client-only views (dashboards, Helm) rather than resource kinds.
const isSynthetic = (id: string): boolean => SYNTHETIC_PREFIXES.some((prefix) => id.startsWith(prefix));

// All nav items across categories and their nested sub-groups (Custom Resources).
function allNavItems(categories: NavCategory[]): NavItem[] {
  return categories.flatMap((c) => [...c.items, ...(c.subgroups ?? []).flatMap((g) => g.items)]);
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
    if (c.label === CATEGORY.cluster) {
      return { ...c, items: [{ id: NAV.overviewCluster, label: 'Overview', kind: '', namespaced: false }, ...c.items] };
    }
    if (c.label === CATEGORY.workloads) {
      return {
        ...c,
        items: [{ id: NAV.overviewWorkloads, label: 'Overview', kind: '', namespaced: false }, ...c.items],
      };
    }
    if (c.label === CATEGORY.network) {
      return {
        ...c,
        items: [...c.items, { id: NAV.portForwards, label: 'Port Forwards', kind: '', namespaced: false }],
      };
    }
    return c;
  });
  const helm: NavCategory = {
    label: CATEGORY.helm,
    icon: 'bi-hexagon',
    items: [
      { id: NAV.helmCharts, label: 'Charts', kind: '', namespaced: false },
      { id: NAV.helmReleases, label: 'Releases', kind: '', namespaced: false },
      { id: NAV.helmRepositories, label: 'Repositories', kind: '', namespaced: false },
    ],
  };
  // Place Helm right after the Cluster category (its three tabs are now nav sub-items).
  const result: NavCategory[] = [];
  for (const c of withOverview) {
    result.push(c);
    if (c.label === CATEGORY.cluster) {
      result.push(helm);
    }
  }
  return result;
}

export function App() {
  return (
    <DialogProvider>
      <AppInner />
    </DialogProvider>
  );
}

/** Node table: append live CPU / Memory / Disk usage-bar columns (metrics-server + node-exporter). */
function nodeUsageColumns(usage: Record<string, UsageSummary>, nodeDisk: Record<string, NodeDiskUsage>): ColumnDef[] {
  const alloc = (o: KubeObject) => ((o.status as Record<string, unknown>)?.allocatable as Record<string, string>) ?? {};
  return [
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
        return <UsageBar fraction={total ? used / total : 0} color="#c026a8" text={`${gib(used)} / ${gib(total)}`} />;
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

/** Pod table: a per-container status-square column plus raw CPU / Memory usage. */
function podUsageColumns(cols: ColumnDef[], usage: Record<string, UsageSummary>): ColumnDef[] {
  const containersCol: ColumnDef = {
    key: 'containers',
    header: 'Containers',
    sortText: (o) =>
      String(
        (((o.status as Record<string, unknown>)?.containerStatuses as { ready?: boolean }[]) ?? []).filter(
          (c) => c.ready,
        ).length,
      ),
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

/** The columns for the selected kind, with live metrics columns for Pods/Nodes. */
function buildResourceColumns(
  id: string | undefined,
  cols: ColumnDef[],
  usage: Record<string, UsageSummary>,
  nodeDisk: Record<string, NodeDiskUsage>,
): ColumnDef[] {
  if (id === 'nodes') {
    return [...cols, ...nodeUsageColumns(usage, nodeDisk)];
  }
  if (id === 'pods') {
    return podUsageColumns(cols, usage);
  }
  return cols;
}

/** Terminal/log dock sessions: open, close, and pop out into floating windows. */
function useDock() {
  const [sessions, setSessions] = useState<DockSession[]>([]);
  const [active, setActive] = useState<string | null>(null);
  const seq = useRef(0);

  const openDock = (kind: DockKind, namespace: string, pod: string, containers: string[], attach = false) => {
    seq.current += 1;
    const id = `${kind}:${namespace}/${pod}#${seq.current}`;
    setSessions((prev) => [...prev, { id, kind, namespace, pod, containers, attach }]);
    setActive(id);
  };

  const closeDock = (id: string) => {
    setSessions((prev) => {
      const next = prev.filter((s) => s.id !== id);
      setActive((cur) => (cur === id ? (next[next.length - 1]?.id ?? null) : cur));
      return next;
    });
  };

  const toggleFloat = (id: string, floating: boolean) => {
    setSessions((prev) =>
      prev.map((s, i) => {
        if (s.id !== id) {
          return s;
        }
        const rect = s.rect ?? { x: 120 + ((i * 32) % 240), y: 120 + ((i * 32) % 240), w: 640, h: 340 };
        return { ...s, floating, rect };
      }),
    );
    if (floating) {
      // Activate the last remaining docked tab when one pops out.
      setActive((cur) => {
        if (cur !== id) {
          return cur;
        }
        const docked = sessions.filter((s) => s.id !== id && !s.floating);
        return docked[docked.length - 1]?.id ?? null;
      });
    } else {
      setActive(id);
    }
  };

  return { sessions, active, setActive, openDock, closeDock, toggleFloat };
}

/** The clusters list + the currently-selected cluster (defaults to the first). */
function useClusters(setError: (e: string | null) => void) {
  const [clusters, setClusters] = useState<ClusterInfo[]>([]);
  const [cluster, setCluster] = useState<string | null>(null);
  useEffect(() => {
    api
      .clusters()
      .then((cs) => {
        setClusters(cs);
        setCluster((prev) => prev ?? cs[0]?.id ?? null);
      })
      .catch((e) => setError(String(e)));
  }, [setError]);
  return { clusters, cluster, setCluster };
}

/** Per-cluster nav tree, count badges, namespaces, Helm releases, and the active Helm scope. */
function useClusterScope(
  cluster: string | null,
  namespace: string | null,
  helmRelease: { namespace: string; name: string } | null,
  setError: (e: string | null) => void,
) {
  const [nav, setNav] = useState<NavCategory[]>([]);
  const [counts, setCounts] = useState<Record<string, number>>({});
  const [helmCounts, setHelmCounts] = useState<Record<string, number>>({});
  const [namespaces, setNamespaces] = useState<string[]>([]);
  const [helmReleaseList, setHelmReleaseList] = useState<HelmRelease[]>([]);
  const [favorites, setFavorites] = useState<string[]>([]);
  const [helmScope, setHelmScope] = useState<Set<string> | null>(null);

  useEffect(() => {
    if (!cluster) {
      return;
    }
    setNav([]);
    setCounts({});
    setFavorites(loadFavorites(cluster));
    setNamespaces([]);
    setHelmReleaseList([]);
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
  }, [cluster, setError]);

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

  useEffect(() => {
    if (!cluster) {
      setHelmCounts({});
      return;
    }
    let cancelled = false;
    Promise.allSettled([
      api.helmReleases(cluster).then((r) => r.length),
      api.helmRepos().then((r) => r.length),
      api.helmCharts(cluster).then((r) => r.length),
    ]).then(([releases, repos, charts]) => {
      if (cancelled) {
        return;
      }
      const c: Record<string, number> = {};
      if (releases.status === 'fulfilled') {
        c[NAV.helmReleases] = releases.value;
      }
      if (repos.status === 'fulfilled') {
        c[NAV.helmRepositories] = repos.value;
      }
      if (charts.status === 'fulfilled') {
        c[NAV.helmCharts] = charts.value;
      }
      setHelmCounts(c);
    });
    return () => {
      cancelled = true;
    };
  }, [cluster]);

  return { nav, counts, helmCounts, namespaces, helmReleaseList, favorites, setFavorites, helmScope };
}

/** The selected kind's objects (live-watched), its columns, and Pod/Node metrics usage. */
function useResourceData(
  cluster: string | null,
  selected: NavItem | null,
  namespace: string | null,
  setError: (e: string | null) => void,
) {
  const [objects, setObjects] = useState<KubeObject[]>([]);
  const [loading, setLoading] = useState(false);
  const [live, setLive] = useState(false);
  const [cols, setCols] = useState<ColumnDef[]>([]);
  const [usage, setUsage] = useState<Record<string, UsageSummary>>({});
  const [nodeDisk, setNodeDisk] = useState<Record<string, NodeDiskUsage>>({});

  useEffect(() => {
    if (!cluster || !selected || isSynthetic(selected.id)) {
      setObjects([]);
      return;
    }
    const ns = selected.namespaced ? (namespace ?? undefined) : undefined;
    let cancelled = false;
    setLoading(true);
    setError(null);
    api
      .objects(cluster, selected.id, ns)
      .then((r) => !cancelled && setObjects(r))
      .catch((e) => {
        if (!cancelled) {
          setError(String(e));
          setObjects([]);
        }
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [cluster, selected, namespace, setError]);

  useEffect(() => {
    if (!cluster || !selected || isSynthetic(selected.id)) {
      setCols([]);
      return;
    }
    if (selected.id.includes('.')) {
      let cancelled = false;
      api
        .printerColumns(cluster, selected.id)
        .then((pc) => !cancelled && setCols(pc.length ? printerColumnDefs(pc) : []))
        .catch(() => setCols([]));
      return () => {
        cancelled = true;
      };
    }
    setCols(columnsFor(selected.id));
    return undefined;
  }, [cluster, selected]);

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
          : api.podMetrics(cluster, selected.namespaced ? (namespace ?? undefined) : undefined);
      p.then((list) => {
        if (cancelled) {
          return;
        }
        const map: Record<string, UsageSummary> = {};
        list.forEach((u) => {
          map[(u.namespace ?? '') + '/' + u.name] = u;
        });
        setUsage(map);
      }).catch(() => !cancelled && setUsage({}));
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

  useEffect(() => {
    if (!cluster || !selected || isSynthetic(selected.id)) {
      return;
    }
    const ns = selected.namespaced ? (namespace ?? undefined) : undefined;
    const url =
      `${clusterBase(cluster)}/resources/${encodeURIComponent(selected.id)}/objects/watch` +
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

  return { objects, setObjects, loading, live, cols, usage, nodeDisk };
}

/** The top brand bar: namespace + Helm-release filters and the sign-in / sign-out box. */
function BrandBar(props: {
  cluster: string | null;
  namespace: string | null;
  setNamespace: (v: string | null) => void;
  namespaces: string[];
  helmRelease: { namespace: string; name: string } | null;
  setHelmRelease: (v: { namespace: string; name: string } | null) => void;
  helmReleaseList: HelmRelease[];
  authUser: string | null;
  onSignIn: () => void;
  onSignOut: () => void;
}) {
  const { cluster, namespace, setNamespace, namespaces, helmRelease, setHelmRelease, helmReleaseList } = props;
  const { authUser, onSignIn, onSignOut } = props;
  return (
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
            <button className="linkbtn" onClick={onSignOut}>
              Sign out
            </button>
          </span>
        ) : (
          <button className="linkbtn" onClick={onSignIn}>
            Sign in
          </button>
        )}
        <a className="switch" href="/">
          Classic UI ↗
        </a>
      </div>
    </header>
  );
}

/** The cluster rail + the per-cluster nav tree sidebar. */
function Sidebar(props: {
  clusters: ClusterInfo[];
  cluster: string | null;
  setCluster: (id: string) => void;
  activeCluster: ClusterInfo | null;
  nav: NavCategory[];
  counts: Record<string, number>;
  favorites: string[];
  selected: NavItem | null;
  setSelected: (i: NavItem) => void;
  onToggleFavorite: (id: string) => void;
}) {
  const {
    clusters,
    cluster,
    setCluster,
    activeCluster,
    nav,
    counts,
    favorites,
    selected,
    setSelected,
    onToggleFavorite,
  } = props;
  return (
    <>
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
            onToggleFavorite={onToggleFavorite}
          />
        )}
      </aside>
    </>
  );
}

/** The client-only views (cluster/workloads overviews, Helm, port-forwards) for the content pane. */
function SyntheticViews(props: {
  cluster: string;
  selected: NavItem | null;
  error: string | null;
  activeCluster: ClusterInfo | null;
  namespaces: string[];
  authUser: string | null;
  helmTarget: { namespace: string; name: string } | null;
  onHelmTargetConsumed: () => void;
  onNavigate: (kind: string, ns?: string) => void;
  onRequireAuth: () => void;
  onAuthExpired: () => void;
}) {
  const { cluster, selected, error, activeCluster, namespaces, authUser, helmTarget } = props;
  const { onHelmTargetConsumed, onNavigate, onRequireAuth, onAuthExpired } = props;
  const id = selected?.id;
  return (
    <>
      {(!selected || id === NAV.overviewCluster) && !error && (
        <ClusterOverview
          cluster={cluster}
          name={activeCluster?.name ?? cluster}
          masterUrl={activeCluster?.masterUrl}
          namespaceCount={namespaces.length}
        />
      )}
      {id === NAV.overviewWorkloads && <WorkloadsOverview cluster={cluster} />}
      {id !== undefined && HELM_VIEW_IDS.includes(id) && (
        <HelmView
          cluster={cluster}
          view={id === NAV.helmCharts ? 'charts' : id === NAV.helmRepositories ? 'repositories' : 'releases'}
          authed={!!authUser}
          onNavigate={onNavigate}
          openResources={helmTarget}
          onResourcesConsumed={onHelmTargetConsumed}
          onRequireAuth={onRequireAuth}
          onAuthExpired={onAuthExpired}
        />
      )}
      {id === NAV.portForwards && <PortForwards cluster={cluster} authed={!!authUser} onRequireAuth={onRequireAuth} />}
    </>
  );
}

/** The resource-list surface: header (search, create, columns), bulk bar, and the table. */
function ResourceListView(props: {
  selected: NavItem;
  filtered: KubeObject[];
  objects: KubeObject[];
  query: string;
  setQuery: (v: string) => void;
  live: boolean;
  tableCols: ColumnDef[];
  visibleCols: ColumnDef[];
  hiddenCols: Set<string>;
  toggleCol: (key: string) => void;
  selection: Set<string>;
  clearSelection: () => void;
  bulkDelete: () => void;
  toggleRow: (key: string) => void;
  toggleAll: (keys: string[]) => void;
  detail: { resourceId: string; obj: KubeObject; edit?: boolean } | null;
  onOpen: (obj: KubeObject) => void;
  onNamespaceClick: (ns: string) => void;
  authUser: string | null;
  onCreate: () => void;
  onRowAction: (action: RowAction, obj: KubeObject, container?: string) => void;
  fetchChildren?: (obj: KubeObject) => Promise<KubeObject[]>;
  loading: boolean;
}) {
  const { selected, filtered, objects, query, setQuery, live, tableCols, visibleCols, hiddenCols, toggleCol } = props;
  const { selection, clearSelection, bulkDelete, toggleRow, toggleAll, detail, onOpen, onNamespaceClick } = props;
  const { authUser, onCreate, onRowAction, fetchChildren, loading } = props;
  return (
    <>
      <div className="content-head">
        <h1>{selected.label}</h1>
        <span className="count">{query ? `${filtered.length} of ${objects.length}` : `${objects.length} items`}</span>
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
        <button className="btn create-btn" onClick={onCreate}>
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
                    <input type="checkbox" checked={!hiddenCols.has(c.key)} onChange={() => toggleCol(c.key)} />
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
          <button className="btn" onClick={clearSelection}>
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
        onOpen={onOpen}
        onNamespaceClick={selected.namespaced ? onNamespaceClick : undefined}
        authed={authUser !== null}
        onRowAction={onRowAction}
        fetchChildren={fetchChildren}
      />
    </>
  );
}

/** The app-level modals: sign-in, create-from-YAML, and start-port-forward. */
function AppModals(props: {
  cluster: string | null;
  showLogin: boolean;
  setShowLogin: (v: boolean) => void;
  setAuthUser: (v: string | null) => void;
  showCreate: boolean;
  setShowCreate: (v: boolean) => void;
  forward: { kind: string; namespace: string; name: string; ports: number[] } | null;
  setForward: (v: null) => void;
  onForwardStarted: () => void;
  signOut: () => void;
}) {
  const { cluster, showLogin, setShowLogin, setAuthUser, showCreate, setShowCreate } = props;
  const { forward, setForward, onForwardStarted, signOut } = props;
  return (
    <>
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
            signOut();
            setShowCreate(false);
            setShowLogin(true);
          }}
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
            onForwardStarted();
          }}
          onAuthExpired={signOut}
        />
      )}
    </>
  );
}

/** Filter the object list by the active Helm scope and the search query. */
function filterObjects(objects: KubeObject[], query: string, helmScope: Set<string> | null): KubeObject[] {
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
}

/** Cross-link navigation: jump to a kind (owner refs), Port Forwards, or a Helm release. */
function useNavigation(
  nav: NavCategory[],
  actions: {
    setSelected: (i: NavItem) => void;
    setDetail: (d: null) => void;
    setNamespace: (ns: string | null) => void;
    setHelmTarget: (t: { namespace: string; name: string }) => void;
  },
) {
  const { setSelected, setDetail, setNamespace, setHelmTarget } = actions;
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
    setSelected({ id: NAV.portForwards, label: 'Port Forwards', kind: '', namespaced: false });
  };
  const navigateToHelmRelease = (namespace: string, name: string) => {
    setDetail(null);
    setSelected({ id: NAV.helmReleases, label: 'Releases', kind: '', namespaced: false });
    setHelmTarget({ namespace, name });
  };
  return { navigateToKind, navigateToPortForwards, navigateToHelmRelease };
}

/** The shell's action handlers (row actions, selection/column toggles, favorites, bulk delete). */
function useAppActions(a: {
  cluster: string | null;
  authUser: string | null;
  selected: NavItem | null;
  selection: Set<string>;
  objects: KubeObject[];
  dialog: DialogApi;
  openDock: (kind: DockKind, ns: string, pod: string, containers: string[], attach?: boolean) => void;
  setForward: (f: { kind: string; namespace: string; name: string; ports: number[] }) => void;
  setDetail: (d: { resourceId: string; obj: KubeObject; edit?: boolean } | null) => void;
  setError: (e: string) => void;
  setObjects: (updater: (prev: KubeObject[]) => KubeObject[]) => void;
  setShowLogin: (v: boolean) => void;
  setSelection: (u: Set<string> | ((prev: Set<string>) => Set<string>)) => void;
  setHiddenCols: (u: (prev: Set<string>) => Set<string>) => void;
  setFavorites: (u: (prev: string[]) => string[]) => void;
  setAuthUser: (v: string | null) => void;
}) {
  const signOut = () => {
    auth.clear();
    a.setAuthUser(null);
  };

  const fetchPods = (obj: KubeObject): Promise<KubeObject[]> =>
    a.cluster ? fetchWorkloadPods(a.cluster, obj) : Promise.resolve([]);

  const handleRowAction = (resourceId: string, action: RowAction, obj: KubeObject, container?: string) => {
    if (!a.cluster) {
      return;
    }
    dispatchRowAction(resourceId, action, obj, container, {
      cluster: a.cluster,
      authUser: a.authUser,
      dialog: a.dialog,
      openDock: a.openDock,
      setForward: a.setForward,
      setDetail: a.setDetail,
      setError: a.setError,
      setObjects: a.setObjects,
      setShowLogin: a.setShowLogin,
    });
  };

  const toggleFavorite = (id: string) =>
    a.setFavorites((prev) => {
      const next = prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id];
      if (a.cluster) {
        saveFavorites(a.cluster, next);
      }
      return next;
    });

  const toggleCol = (key: string) => a.setHiddenCols((prev) => toggleInSet(prev, key));
  const toggleRow = (key: string) => a.setSelection((prev) => toggleInSet(prev, key));
  const toggleAll = (keys: string[]) =>
    a.setSelection((prev) => (prev.size >= keys.length && keys.length > 0 ? new Set() : new Set(keys)));

  const bulkDelete = () => {
    if (!a.cluster || !a.selected || a.selection.size === 0) {
      return;
    }
    if (!a.authUser) {
      a.setShowLogin(true);
      return;
    }
    void runBulkDelete({
      cluster: a.cluster,
      selected: a.selected,
      selection: a.selection,
      objects: a.objects,
      dialog: a.dialog,
      onAuthCleared: () => {
        signOut();
        a.setShowLogin(true);
      },
      clearSelection: () => a.setSelection(new Set()),
    });
  };

  return { signOut, fetchPods, handleRowAction, toggleFavorite, toggleCol, toggleRow, toggleAll, bulkDelete };
}

/** The pods a workload owns — matched by its spec.selector.matchLabels in its namespace. */
async function fetchWorkloadPods(cluster: string, obj: KubeObject): Promise<KubeObject[]> {
  const sel =
    ((obj.spec as Record<string, unknown>)?.selector as { matchLabels?: Record<string, string> })?.matchLabels ?? {};
  const keys = Object.keys(sel);
  if (keys.length === 0) {
    return [];
  }
  const pods = await api.objects(cluster, 'pods', objNs(obj) ?? undefined);
  return pods.filter((p) => {
    const labels = p.metadata?.labels ?? {};
    return keys.every((k) => labels[k] === sel[k]);
  });
}

/** Toggle a key's membership in a Set, returning a new Set. */
function toggleInSet<T>(set: Set<T>, key: T): Set<T> {
  const next = new Set(set);
  if (next.has(key)) {
    next.delete(key);
  } else {
    next.add(key);
  }
  return next;
}

type RowActionDeps = {
  cluster: string;
  authUser: string | null;
  dialog: DialogApi;
  openDock: (kind: DockKind, ns: string, pod: string, containers: string[], attach?: boolean) => void;
  setForward: (f: { kind: string; namespace: string; name: string; ports: number[] }) => void;
  setDetail: (d: { resourceId: string; obj: KubeObject; edit?: boolean }) => void;
  setError: (e: string) => void;
  setObjects: (updater: (prev: KubeObject[]) => KubeObject[]) => void;
  setShowLogin: (v: boolean) => void;
};

/** Dispatch a per-row action through the ROW_ACTIONS registry, gating on auth. */
function dispatchRowAction(
  resourceId: string,
  action: RowAction,
  obj: KubeObject,
  container: string | undefined,
  deps: RowActionDeps,
) {
  const def = ROW_ACTIONS.find((a) => a.id === action);
  if (!def) {
    return;
  }
  // Everything except read-only Logs requires the admin login; prompt for it.
  if (def.requiresAuth !== false && deps.authUser === null) {
    deps.setShowLogin(true);
    return;
  }
  const confirmRun = (fn: () => Promise<unknown>, confirmMsg?: string) => {
    const go = () => fn().catch((e) => deps.setError(String(e)));
    if (!confirmMsg) {
      go();
      return;
    }
    deps.dialog.confirm({ message: confirmMsg }).then((ok) => {
      if (ok) {
        go();
      }
    });
  };
  def.run({
    cluster: deps.cluster,
    resourceId,
    obj,
    ns: objNs(obj) ?? '',
    name: objName(obj),
    kind: obj.kind ?? '',
    containers: containerNames(obj),
    container,
    dialog: deps.dialog,
    openDock: deps.openDock,
    setForward: deps.setForward,
    setDetail: deps.setDetail,
    setError: deps.setError,
    removeObject: (o) => deps.setObjects((prev) => prev.filter((x) => objKey(x) !== objKey(o))),
    confirmRun,
  });
}

/** Delete every selected object, prompting once; clears auth on 401/403. */
async function runBulkDelete(deps: {
  cluster: string;
  selected: NavItem;
  selection: Set<string>;
  objects: KubeObject[];
  dialog: DialogApi;
  onAuthCleared: () => void;
  clearSelection: () => void;
}) {
  const { cluster, selected, selection, objects, dialog, onAuthCleared, clearSelection } = deps;
  const ok = await dialog.confirm({
    title: 'Delete',
    message: `Delete ${selection.size} ${selected.label}? This cannot be undone.`,
    confirmLabel: 'Delete',
    danger: true,
  });
  if (!ok) {
    return;
  }
  const targets = objects.filter((o) => selection.has(objKey(o)) && objNs(o));
  for (const o of targets) {
    try {
      await api.del(cluster, selected.id, objNs(o) as string, objName(o));
    } catch (e) {
      if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
        onAuthCleared();
        break;
      }
    }
  }
  clearSelection();
}

function AppInner() {
  const dialog = useDialog();
  // UI state — the shell owns selection/detail/query/auth/modals; data comes from hooks.
  const [error, setError] = useState<string | null>(null);
  const [namespace, setNamespace] = useState<string | null>(null);
  const [helmRelease, setHelmRelease] = useState<{ namespace: string; name: string } | null>(null);
  const [selected, setSelected] = useState<NavItem | null>(null);
  const [detail, setDetail] = useState<{ resourceId: string; obj: KubeObject; edit?: boolean } | null>(null);
  const [hiddenCols, setHiddenCols] = useState<Set<string>>(new Set());
  const [selection, setSelection] = useState<Set<string>>(new Set());
  const [query, setQuery] = useState('');
  const [authUser, setAuthUser] = useState<string | null>(null);
  const [showLogin, setShowLogin] = useState(false);
  const [showCreate, setShowCreate] = useState(false);
  const [forward, setForward] = useState<{ kind: string; namespace: string; name: string; ports: number[] } | null>(
    null,
  );
  const [helmTarget, setHelmTarget] = useState<{ namespace: string; name: string } | null>(null);

  const { clusters, cluster, setCluster } = useClusters(setError);
  const { nav, counts, helmCounts, namespaces, helmReleaseList, favorites, setFavorites, helmScope } = useClusterScope(
    cluster,
    namespace,
    helmRelease,
    setError,
  );
  const { objects, setObjects, loading, live, cols, usage, nodeDisk } = useResourceData(
    cluster,
    selected,
    namespace,
    setError,
  );
  const {
    sessions: dockSessions,
    active: activeSession,
    setActive: setActiveSession,
    openDock,
    closeDock,
    toggleFloat,
  } = useDock();

  // Reset the view when the active cluster, or the selected kind/namespace, changes.
  useEffect(() => {
    setSelected(null);
    setNamespace(null);
    setHelmRelease(null);
  }, [cluster]);
  useEffect(() => {
    setDetail(null);
    setQuery('');
    setHiddenCols(new Set());
    setSelection(new Set());
  }, [selected, namespace]);

  const activeCluster = useMemo(() => clusters.find((c) => c.id === cluster) ?? null, [clusters, cluster]);

  const filtered = useMemo(() => filterObjects(objects, query, helmScope), [objects, query, helmScope]);

  // For Pods/Nodes, append live CPU/Memory/Disk columns backed by metrics-server usage.
  const tableCols = useMemo<ColumnDef[]>(
    () => buildResourceColumns(selected?.id, cols, usage, nodeDisk),
    [cols, usage, nodeDisk, selected],
  );

  const visibleCols = useMemo(() => tableCols.filter((c) => !hiddenCols.has(c.key)), [tableCols, hiddenCols]);

  const { navigateToKind, navigateToPortForwards, navigateToHelmRelease } = useNavigation(nav, {
    setSelected,
    setDetail,
    setNamespace,
    setHelmTarget,
  });

  const { signOut, fetchPods, handleRowAction, toggleFavorite, toggleCol, toggleRow, toggleAll, bulkDelete } =
    useAppActions({
      cluster,
      authUser,
      selected,
      selection,
      objects,
      dialog,
      openDock,
      setForward,
      setDetail,
      setError,
      setObjects,
      setShowLogin,
      setSelection,
      setHiddenCols,
      setFavorites,
      setAuthUser,
    });

  return (
    <div className="app">
      <BrandBar
        cluster={cluster}
        namespace={namespace}
        setNamespace={setNamespace}
        namespaces={namespaces}
        helmRelease={helmRelease}
        setHelmRelease={setHelmRelease}
        helmReleaseList={helmReleaseList}
        authUser={authUser}
        onSignIn={() => setShowLogin(true)}
        onSignOut={signOut}
      />

      <div className="body">
        <Sidebar
          clusters={clusters}
          cluster={cluster}
          setCluster={setCluster}
          activeCluster={activeCluster}
          nav={nav}
          counts={{ ...counts, ...helmCounts }}
          favorites={favorites}
          selected={selected}
          setSelected={setSelected}
          onToggleFavorite={toggleFavorite}
        />

        <div className="content-col">
          <main className="content">
            {error && <div className="error">{error}</div>}
            {cluster && (
              <SyntheticViews
                cluster={cluster}
                selected={selected}
                error={error}
                activeCluster={activeCluster}
                namespaces={namespaces}
                authUser={authUser}
                helmTarget={helmTarget}
                onHelmTargetConsumed={() => setHelmTarget(null)}
                onNavigate={navigateToKind}
                onRequireAuth={() => setShowLogin(true)}
                onAuthExpired={signOut}
              />
            )}
            {selected && !isSynthetic(selected.id) && (
              <ResourceListView
                selected={selected}
                filtered={filtered}
                objects={objects}
                query={query}
                setQuery={setQuery}
                live={live}
                tableCols={tableCols}
                visibleCols={visibleCols}
                hiddenCols={hiddenCols}
                toggleCol={toggleCol}
                selection={selection}
                clearSelection={() => setSelection(new Set())}
                bulkDelete={bulkDelete}
                toggleRow={toggleRow}
                toggleAll={toggleAll}
                detail={detail}
                onOpen={(obj) => setDetail({ resourceId: selected.id, obj })}
                onNamespaceClick={(ns) => setNamespace(ns)}
                authUser={authUser}
                onCreate={() => (authUser ? setShowCreate(true) : setShowLogin(true))}
                onRowAction={(action, obj, container) => handleRowAction(selected.id, action, obj, container)}
                fetchChildren={selected.expandable ? fetchPods : undefined}
                loading={loading}
              />
            )}
          </main>
          {cluster && dockSessions.length > 0 && (
            <DockArea
              cluster={cluster}
              sessions={dockSessions}
              active={activeSession}
              onActivate={setActiveSession}
              onClose={closeDock}
              onToggleFloat={toggleFloat}
            />
          )}
        </div>

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
            onAuthExpired={() => {
              auth.clear();
              setAuthUser(null);
            }}
            onClose={() => setDetail(null)}
          />
        )}
      </div>

      <AppFooter />

      <AppModals
        cluster={cluster}
        showLogin={showLogin}
        setShowLogin={setShowLogin}
        setAuthUser={setAuthUser}
        showCreate={showCreate}
        setShowCreate={setShowCreate}
        forward={forward}
        setForward={setForward}
        onForwardStarted={navigateToPortForwards}
        signOut={signOut}
      />
    </div>
  );
}

/** One open dock session — an exec terminal or a log follow, shown as a tab. */
function CreateModal(props: { cluster: string; onClose: () => void; onAuthExpired: () => void }) {
  const { cluster, onClose, onAuthExpired } = props;
  const [draft, setDraft] = useState(
    'apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: example\n  namespace: default\ndata:\n  key: value\n',
  );
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [err, setErr] = useState(false);

  useEscapeKey(onClose);

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
        <textarea
          className="yaml-edit tall"
          value={draft}
          spellCheck={false}
          onChange={(e) => setDraft(e.target.value)}
        />
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

/** Version / build-time / source-repo footer, from Actuator's public /actuator/info. */
function AppFooter() {
  const [info, setInfo] = useState<{ build?: { version?: string; time?: string } } | null>(null);
  useEffect(() => {
    api
      .info()
      .then(setInfo)
      .catch(() => undefined);
  }, []);
  const version = info?.build?.version;
  const built = info?.build?.time;
  return (
    <footer className="app-footer">
      <a className="repo-link" href="https://github.com/alexmond/kweblens" target="_blank" rel="noreferrer">
        github.com/alexmond/kweblens ↗
      </a>
      <span className="ver-line" title={built ? `Built ${built}` : undefined}>
        {version ? `v${version}` : 'dev'}
        {built ? ` · built ${new Date(built).toLocaleString()}` : ''}
      </span>
    </footer>
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

  useEscapeKey(onClose);

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
            <input type="number" min={1} value={remotePort} onChange={(e) => setRemotePort(e.target.value)} autoFocus />
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

const numOf = toNum;
// Scaled-to-zero counts as healthy (intentionally scaled down, not failing).
const replicasReady = (o: KubeObject): boolean => numOf(objStatus(o).readyReplicas) === numOf(objSpec(o).replicas);

// The Workloads overview cards. Each entry carries its own health predicate, so adding a
// workload kind is one entry here (no separate switch to keep in sync).
const WORKLOAD_KINDS: { id: string; label: string; healthy: (o: KubeObject) => boolean }[] = [
  { id: 'pods', label: 'Pods', healthy: (o) => objStatus(o).phase === 'Running' || objStatus(o).phase === 'Succeeded' },
  { id: 'deployments', label: 'Deployments', healthy: replicasReady },
  { id: 'statefulsets', label: 'Stateful Sets', healthy: replicasReady },
  {
    id: 'daemonsets',
    label: 'Daemon Sets',
    healthy: (o) => numOf(objStatus(o).numberReady) === numOf(objStatus(o).desiredNumberScheduled),
  },
  { id: 'replicasets', label: 'Replica Sets', healthy: replicasReady },
  { id: 'jobs', label: 'Jobs', healthy: (o) => numOf(objStatus(o).succeeded) > 0 },
  { id: 'cronjobs', label: 'Cron Jobs', healthy: () => true },
];

// One dashboard stat card, shared by the Cluster and Workloads overviews.
function StatCard(props: { value: ReactNode; label: ReactNode; danger?: boolean }) {
  return (
    <div className={'ov-card' + (props.danger ? ' danger' : '')}>
      <div className="ov-num">{props.value}</div>
      <div className="ov-lbl">{props.label}</div>
    </div>
  );
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
            [k.id]: { total: objs.length, ready: objs.filter((o) => k.healthy(o)).length },
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
            <StatCard
              key={k.id}
              value={c ? c.total : '…'}
              label={
                <>
                  {k.label}
                  {c ? ` · ${c.ready} ready` : ''}
                </>
              }
              danger={unhealthy > 0}
            />
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
    k === 'age' ? ageToSeconds(w.age) : ((w[k as keyof EventSummary] as string) ?? ''),
  );

  return (
    <div className="overview">
      <h1 className="ov-title">{name}</h1>
      <div className="ov-cards">
        <StatCard value={nodes ? nodes.length : '…'} label={<>Nodes{nodes ? ` · ${readyNodes} ready` : ''}</>} />
        <StatCard value={namespaceCount} label="Namespaces" />
        <StatCard
          value={warnings ? warnings.length : '…'}
          label="Warnings"
          danger={!!(warnings && warnings.length > 0)}
        />
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

  useEscapeKey(onCancel);

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

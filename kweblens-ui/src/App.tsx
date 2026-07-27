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

// The top-level app shell / orchestrator: owns cluster+nav+selection state, wires every
// feature view together, and holds the data-loading effects. It is the one function
// intentionally exempt from the size/complexity gates (which are errors everywhere else);
// decomposing it further into a data hook + layout components is a tracked follow-up.
// eslint-disable-next-line max-lines-per-function, complexity
function AppInner() {
  const dialog = useDialog();
  const [clusters, setClusters] = useState<ClusterInfo[]>([]);
  const [cluster, setCluster] = useState<string | null>(null);
  const [nav, setNav] = useState<NavCategory[]>([]);
  const [counts, setCounts] = useState<Record<string, number>>({});
  const [helmCounts, setHelmCounts] = useState<Record<string, number>>({});
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
  const [dockSessions, setDockSessions] = useState<DockSession[]>([]);
  const [activeSession, setActiveSession] = useState<string | null>(null);
  const dockSeq = useRef(0);

  const openDock = (kind: DockKind, namespace: string, pod: string, containers: string[], attach = false) => {
    dockSeq.current += 1;
    const id = `${kind}:${namespace}/${pod}#${dockSeq.current}`;
    setDockSessions((prev) => [...prev, { id, kind, namespace, pod, containers, attach }]);
    setActiveSession(id);
  };

  const closeDock = (id: string) => {
    setDockSessions((prev) => {
      const next = prev.filter((s) => s.id !== id);
      setActiveSession((cur) => (cur === id ? (next[next.length - 1]?.id ?? null) : cur));
      return next;
    });
  };

  const toggleFloat = (id: string, floating: boolean) => {
    setDockSessions((prev) =>
      prev.map((s, i) => {
        if (s.id !== id) {
          return s;
        }
        const rect = s.rect ?? {
          x: 120 + ((i * 32) % 240),
          y: 120 + ((i * 32) % 240),
          w: 640,
          h: 340,
        };
        return { ...s, floating, rect };
      }),
    );
    if (floating) {
      // Activate the last remaining docked tab when one pops out.
      setActiveSession((cur) => {
        if (cur !== id) {
          return cur;
        }
        const docked = dockSessions.filter((s) => s.id !== id && !s.floating);
        return docked[docked.length - 1]?.id ?? null;
      });
    } else {
      setActiveSession(id);
    }
  };
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

  // Counters for the Helm nav sub-items (releases / repositories / charts). Charts can be
  // slow to warm; allSettled keeps the others working and the count appears once ready.
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

  // Fetch the selected kind's raw objects on kind/namespace change.
  useEffect(() => {
    if (!cluster || !selected || isSynthetic(selected.id)) {
      setObjects([]);
      return;
    }
    const ns = selected.namespaced ? (namespace ?? undefined) : undefined;
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
      const alloc = (o: KubeObject) =>
        ((o.status as Record<string, unknown>)?.allocatable as Record<string, string>) ?? {};
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
              <UsageBar fraction={total ? used / total : 0} color="#c026a8" text={`${gib(used)} / ${gib(total)}`} />
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
    setSelected({ id: NAV.portForwards, label: 'Port Forwards', kind: '', namespaced: false });
  };

  // The pods a workload owns — matched by its spec.selector.matchLabels in its namespace.
  const fetchWorkloadPods = async (obj: KubeObject): Promise<KubeObject[]> => {
    if (!cluster) {
      return [];
    }
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
  };

  // Per-row kebab actions: open logs, jump to YAML edit, or delete / force-delete.
  const handleRowAction = (resourceId: string, action: RowAction, obj: KubeObject, container?: string) => {
    if (!cluster) {
      return;
    }
    const def = ROW_ACTIONS.find((a) => a.id === action);
    if (!def) {
      return;
    }
    // Everything except read-only Logs requires the admin login; prompt for it. The menu
    // shows all actions (Freelens-style) so they're discoverable even when signed out.
    if (def.requiresAuth !== false && authUser === null) {
      setShowLogin(true);
      return;
    }
    // Optional-confirm wrapper handed to action handlers: run fn, surface any error.
    const confirmRun = (fn: () => Promise<unknown>, confirmMsg?: string) => {
      const go = () => fn().catch((e) => setError(String(e)));
      if (!confirmMsg) {
        go();
        return;
      }
      dialog.confirm({ message: confirmMsg }).then((ok) => {
        if (ok) {
          go();
        }
      });
    };
    def.run({
      cluster,
      resourceId,
      obj,
      ns: objNs(obj) ?? '',
      name: objName(obj),
      kind: obj.kind ?? '',
      containers: containerNames(obj),
      container,
      dialog,
      openDock,
      setForward,
      setDetail,
      setError,
      removeObject: (o) => setObjects((prev) => prev.filter((x) => objKey(x) !== objKey(o))),
      confirmRun,
    });
  };

  // From a resource's "Managed By: Helm" link → open the owning release's Resources view.
  const navigateToHelmRelease = (namespace: string, name: string) => {
    setDetail(null);
    setSelected({ id: NAV.helmReleases, label: 'Releases', kind: '', namespaced: false });
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
              counts={{ ...counts, ...helmCounts }}
              favorites={favorites}
              selected={selected?.id ?? null}
              onSelect={setSelected}
              onToggleFavorite={toggleFavorite}
            />
          )}
          <AppFooter />
        </aside>

        <div className="content-col">
          <main className="content">
            {error && <div className="error">{error}</div>}
            {(!selected || selected.id === NAV.overviewCluster) && !error && cluster && (
              <ClusterOverview
                cluster={cluster}
                name={activeCluster?.name ?? cluster}
                masterUrl={activeCluster?.masterUrl}
                namespaceCount={namespaces.length}
              />
            )}
            {cluster && selected?.id === NAV.overviewWorkloads && <WorkloadsOverview cluster={cluster} />}
            {cluster && selected?.id !== undefined && HELM_VIEW_IDS.includes(selected.id) && (
              <HelmView
                cluster={cluster}
                view={
                  selected.id === NAV.helmCharts
                    ? 'charts'
                    : selected.id === NAV.helmRepositories
                      ? 'repositories'
                      : 'releases'
                }
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
            {cluster && selected?.id === NAV.portForwards && (
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
                  onRowAction={(action, obj, container) => handleRowAction(selected.id, action, obj, container)}
                  fetchChildren={selected.expandable ? fetchWorkloadPods : undefined}
                />
              </>
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
    <div className="nav-footer">
      <a className="repo-link" href="https://github.com/alexmond/kweblens" target="_blank" rel="noreferrer">
        github.com/alexmond/kweblens ↗
      </a>
      <div className="ver-line" title={built ? `Built ${built}` : undefined}>
        {version ? `v${version}` : 'dev'}
        {built ? ` · built ${new Date(built).toLocaleString()}` : ''}
      </div>
    </div>
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

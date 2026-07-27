import { ApiError, api } from './api';
import type { DialogApi } from './dialog';
import { containerNames, objKey, objName, objNs } from './kube';
import type { RowAction } from './rowActions';
import { ROW_ACTIONS } from './rowActions';
import type { DockKind, KubeObject, NavCategory, NavItem } from './types';

// Framework-agnostic shell logic ported from App.tsx: the synthetic-nav registry, favorites
// persistence, object filtering, workload→pod matching, and the row-action / bulk-delete
// dispatchers. No Vue here — the composables and App.vue consume these.

// Ids of the synthetic (client-only) nav items — dashboards and Helm/Port-Forward views.
export const NAV = {
  overviewCluster: 'overview:cluster',
  overviewWorkloads: 'overview:workloads',
  portForwards: 'portforward:list',
  helmCharts: 'helm:charts',
  helmReleases: 'helm:releases',
  helmRepositories: 'helm:repositories',
} as const;

const SYNTHETIC_PREFIXES = ['overview:', 'helm:', 'portforward:'] as const;
const CATEGORY = { cluster: 'Cluster', workloads: 'Workloads', network: 'Network', helm: 'Helm' } as const;
export const HELM_VIEW_IDS: string[] = [NAV.helmCharts, NAV.helmReleases, NAV.helmRepositories];

export const isSynthetic = (id: string): boolean => SYNTHETIC_PREFIXES.some((prefix) => id.startsWith(prefix));

export function allNavItems(categories: NavCategory[]): NavItem[] {
  return categories.flatMap((c) => [...c.items, ...(c.subgroups ?? []).flatMap((g) => g.items)]);
}

export function loadFavorites(cluster: string): string[] {
  try {
    return JSON.parse(localStorage.getItem('kw-fav-' + cluster) ?? '[]') as string[];
  } catch {
    return [];
  }
}
export function saveFavorites(cluster: string, favorites: string[]): void {
  try {
    localStorage.setItem('kw-fav-' + cluster, JSON.stringify(favorites));
  } catch {
    // storage unavailable — favorites just won't persist
  }
}

/** Inject the two Overview dashboards, the Port Forwards item, and the Helm section. */
export function withSyntheticNav(cats: NavCategory[]): NavCategory[] {
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
  const result: NavCategory[] = [];
  for (const c of withOverview) {
    result.push(c);
    if (c.label === CATEGORY.cluster) {
      result.push(helm);
    }
  }
  return result;
}

/** Filter the object list by the active Helm scope and the search query. */
export function filterObjects(objects: KubeObject[], query: string, helmScope: Set<string> | null): KubeObject[] {
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

/** The pods a workload owns — matched by its spec.selector.matchLabels in its namespace. */
export async function fetchWorkloadPods(cluster: string, obj: KubeObject): Promise<KubeObject[]> {
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
export function toggleInSet<T>(set: Set<T>, key: T): Set<T> {
  const next = new Set(set);
  if (next.has(key)) {
    next.delete(key);
  } else {
    next.add(key);
  }
  return next;
}

export interface RowActionDeps {
  cluster: string;
  authUser: string | null;
  dialog: DialogApi;
  openDock: (kind: DockKind, ns: string, pod: string, containers: string[], attach?: boolean) => void;
  setForward: (f: { kind: string; namespace: string; name: string; ports: number[] }) => void;
  setDetail: (d: { resourceId: string; obj: KubeObject; edit?: boolean }) => void;
  setError: (e: string) => void;
  setObjects: (updater: (prev: KubeObject[]) => KubeObject[]) => void;
  setShowLogin: (v: boolean) => void;
}

/** Dispatch a per-row action through the ROW_ACTIONS registry, gating on auth. */
export function dispatchRowAction(
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
export async function runBulkDelete(deps: {
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

import { ApiError, api } from './api';
import type { DialogApi } from './dialog';
import type { LogScope } from './dock';
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
const CATEGORY = { cluster: 'Cluster', network: 'Network', helm: 'Helm' } as const;

/**
 * Categories that have a dashboard, mapped to the id the shell renders it under. Driven by a map
 * rather than a branch per category so adding one is a line here plus a server-side check.
 */
const OVERVIEW_CATEGORIES: Record<string, string> = {
  Workloads: NAV.overviewWorkloads,
  Config: 'overview:config',
  Network: 'overview:network',
  Storage: 'overview:storage',
};
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
    let items = c.items;
    if (c.label === CATEGORY.network) {
      items = [...items, { id: NAV.portForwards, label: 'Port Forwards', kind: '', namespaced: false }];
    }
    const overview = OVERVIEW_CATEGORIES[c.label];
    if (overview) {
      items = [{ id: overview, label: 'Overview', kind: '', namespaced: false }, ...items];
    }
    return items === c.items ? c : { ...c, items };
  });
  const helm: NavCategory = {
    label: CATEGORY.helm,
    icon: 'bi-hexagon',
    items: [
      { id: NAV.helmReleases, label: 'Releases', kind: '', namespaced: false },
      { id: NAV.helmCharts, label: 'Charts', kind: '', namespaced: false },
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
  openLogs: (
    ns: string,
    pod: string,
    containers: string[],
    scope: LogScope,
    workload?: { resourceId: string; name: string },
  ) => void;
  setForward: (f: { kind: string; namespace: string; name: string; ports: number[] }) => void;
  setDetail: (d: { resourceId: string; obj: KubeObject; edit?: boolean } | null) => void;
  /**
   * `objKey` of whatever the detail drawer is showing, or null when it is shut.
   *
   * Needed because deleting is now reachable FROM the drawer (#233), and a drawer left
   * open on an object that no longer exists is a detail view of nothing — it keeps
   * offering actions against a deleted object. Deleting a different row from the list
   * must not close it, which is why this is a key to compare rather than a flag.
   */
  detailKey?: string | null;
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
    openLogs: deps.openLogs,
    setForward: deps.setForward,
    setDetail: deps.setDetail,
    setError: deps.setError,
    removeObject: (o) => {
      deps.setObjects((prev) => prev.filter((x) => objKey(x) !== objKey(o)));
      // Close the drawer only when it is showing THIS object.
      if (deps.detailKey && deps.detailKey === objKey(o)) {
        deps.setDetail(null);
      }
    },
    confirmRun,
  });
}

/** What a bulk delete actually did — one entry per object it got an answer for. */
interface BulkDeleteOutcome {
  /** Objects the delete was attempted on. */
  attempted: number;
  /** Refs (`ns/name`, or just `name` when cluster-scoped) the server accepted. */
  deleted: string[];
  /** Refs the server refused, with the error as rendered to the operator. */
  failed: { ref: string; error: string }[];
  /** True when a 401/403 stopped the run before every target was tried. */
  authCleared: boolean;
}

/** `ns/name`, or `name` alone for a cluster-scoped object. */
const refOf = (o: KubeObject): string => (objNs(o) ? `${objNs(o)}/${objName(o)}` : objName(o));

/**
 * One line naming what happened, only ever produced when something failed.
 *
 * <p>A partial failure has to be distinguishable from success (#297): "7 of 10 blocked by an
 * admission webhook" used to look exactly like "all 10 deleted", because neither said anything
 * at all. The counts come first so the shape of the outcome survives truncation of the reasons.
 */
function bulkDeleteMessage(outcome: BulkDeleteOutcome, label: string): string {
  const { attempted, deleted, failed, authCleared } = outcome;
  const reasons = failed.slice(0, 3).map((f) => `${f.ref}: ${f.error}`);
  const rest = failed.length - reasons.length;
  const stopped = authCleared ? ' Stopped after a permission error; the rest were not tried.' : '';
  return (
    `Deleted ${deleted.length} of ${attempted} ${label}; ${failed.length} failed. ` +
    reasons.join('; ') +
    (rest > 0 ? `; and ${rest} more` : '') +
    stopped
  );
}

/**
 * Delete every selected object, prompting once; clears auth on 401/403.
 *
 * <p>Cluster-scoped objects are sent with no namespace rather than filtered out (`api.del`
 * turns that into the URL's "no namespace" segment, and the server addresses the object by
 * the kind's own scope). Skipping them, as this used to, meant Delete was offered on 13
 * built-in kinds plus every cluster-scoped CRD and then quietly did nothing (#297).
 */
export async function runBulkDelete(deps: {
  cluster: string;
  selected: NavItem;
  selection: Set<string>;
  objects: KubeObject[];
  dialog: DialogApi;
  setError: (e: string) => void;
  onAuthCleared: () => void;
  clearSelection: () => void;
}): Promise<BulkDeleteOutcome | null> {
  const { cluster, selected, selection, objects, dialog, setError, onAuthCleared, clearSelection } = deps;
  const ok = await dialog.confirm({
    title: 'Delete',
    message: `Delete ${selection.size} ${selected.label}? This cannot be undone.`,
    confirmLabel: 'Delete',
    danger: true,
  });
  if (!ok) {
    return null;
  }
  const targets = objects.filter((o) => selection.has(objKey(o)));
  const outcome: BulkDeleteOutcome = { attempted: targets.length, deleted: [], failed: [], authCleared: false };
  for (const o of targets) {
    try {
      await api.del(cluster, selected.id, objNs(o) ?? '', objName(o));
      outcome.deleted.push(refOf(o));
    } catch (e) {
      outcome.failed.push({ ref: refOf(o), error: String(e) });
      // A permission failure is about the session, not this object: trying the rest would
      // produce the same 403 N times. Clear auth and stop, as this has always done.
      if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
        outcome.authCleared = true;
        onAuthCleared();
        break;
      }
    }
  }
  clearSelection();
  if (outcome.failed.length > 0) {
    setError(bulkDeleteMessage(outcome, selected.label));
  }
  return outcome;
}

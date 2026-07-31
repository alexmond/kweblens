import type { ClusterInfo } from './types';

/**
 * Logic for the Clusters page — the surface the cluster-selection design review
 * (`docs/design/cluster-selection.md`) recommended in place of growing the rail.
 *
 * <p>The rail cannot scale for two independent reasons found in that review: `initials()`
 * is `id.slice(0, 2)` with no collision handling, so `prod-eu` and `prod-us` both read
 * `PR`; and `.rail` has no `overflow-y`, so past a viewport's worth of tiles the extras are
 * simply unreachable. A page fixes both by not being a fixed-height strip of two-letter
 * abbreviations.
 *
 * <p>Kept apart from the component so it can be tested without a DOM.
 */

/** What the page needs to render one row. */
export interface ClusterRow {
  id: string;
  name: string;
  masterUrl: string;
  /** STATIC clusters are declared in config or the ambient kubeconfig. */
  origin: 'STATIC' | 'RUNTIME';
  /** Whether this cluster can be edited or removed through the API. */
  editable: boolean;
  /** Why not, when it cannot — shown to explain a disabled control. */
  lockedReason: string | null;
  /** True for the cluster currently being viewed. */
  current: boolean;
}

/**
 * The reason a STATIC cluster's edit and remove controls are disabled.
 *
 * <p>Worth stating rather than just grey-ing the buttons: the cluster is perfectly real and
 * usable, it simply is not owned by the runtime store, so editing it here would be
 * overwritten by configuration on the next restart.
 */
export const STATIC_LOCK_REASON = 'Declared in configuration or the ambient kubeconfig — edit it there, not here.';

/**
 * Project the API's cluster list into rows.
 *
 * <p>`origin` is treated as STATIC when absent. An older server that does not report it
 * predates runtime cluster management, so nothing it returns is editable — defaulting the
 * other way would offer controls that then fail.
 */
export function toRows(clusters: ClusterInfo[], currentId: string | null): ClusterRow[] {
  return clusters.map((c) => {
    const origin = c.origin ?? 'STATIC';
    const editable = origin === 'RUNTIME';
    return {
      id: c.id,
      name: c.name,
      masterUrl: c.masterUrl,
      origin,
      editable,
      lockedReason: editable ? null : STATIC_LOCK_REASON,
      current: c.id === currentId,
    };
  });
}

/** Rows matching a filter over name, id and API server, case-insensitively. */
export function filterRows(rows: ClusterRow[], query: string): ClusterRow[] {
  const q = query.trim().toLowerCase();
  if (!q) {
    return rows;
  }
  return rows.filter((r) => [r.name, r.id, r.masterUrl].some((field) => field.toLowerCase().includes(q)));
}

/** A short, human summary of the page's contents — used as the page subtitle. */
export function summarise(rows: ClusterRow[]): string {
  if (!rows.length) {
    return 'No clusters configured.';
  }
  const runtime = rows.filter((r) => r.editable).length;
  const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`;
  if (runtime === 0) {
    return `${plural(rows.length, 'cluster')}, all declared in configuration.`;
  }
  if (runtime === rows.length) {
    return `${plural(rows.length, 'cluster')}, all added at runtime.`;
  }
  return `${plural(rows.length, 'cluster')} — ${runtime} added at runtime, ${rows.length - runtime} declared in configuration.`;
}

/**
 * Validate an id before it is sent.
 *
 * <p>The id lands in URLs, on disk, and as the key of a Kubernetes Secret, so the
 * intersection of what those allow is narrower than what a person might type. Rejecting it
 * here gives an immediate, specific message rather than a 400 from three layers down.
 */
export function idProblem(id: string, existing: string[]): string | null {
  const value = id.trim();
  if (!value) {
    return 'An id is required.';
  }
  // Secret keys and DNS-style names agree on this much: lowercase alphanumerics,
  // dashes and dots, starting and ending alphanumeric.
  if (!/^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$/.test(value)) {
    return 'Use lowercase letters, digits, dashes and dots; start and end with a letter or digit.';
  }
  if (existing.includes(value)) {
    return `A cluster with the id '${value}' already exists.`;
  }
  return null;
}

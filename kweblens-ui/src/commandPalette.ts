import type { ClusterInfo, NavCategory, NavItem } from './types';

/**
 * Type-to-filter command palette: the fast path for switching cluster and jumping to a
 * kind, and the thing that closes the command-palette gap the competitive review flagged
 * against k9s.
 *
 * <p>It is also the answer to a defect the cluster-selection design review turned up
 * (`docs/design/cluster-selection.md`): the rail labels tiles with `id.slice(0, 2)`, so
 * `prod-eu` and `prod-us` both read `PR` and can only be told apart by hovering one at a
 * time. A palette row carries the full name and id, so switching never depends on
 * resolving a two-letter collision.
 *
 * <p>Logic lives here rather than in the component so it can be tested without a DOM.
 */

/** What kind of thing a row does when you pick it. */
type CommandKind = 'cluster' | 'nav';

export interface Command {
  /** Stable key for :key and for tests. */
  key: string;
  kind: CommandKind;
  /** Primary text — the cluster name, or the kind's label. */
  label: string;
  /** Secondary text: the cluster id, or the category the kind sits under. */
  hint: string;
  /** Cluster id for 'cluster', nav item id for 'nav'. */
  target: string;
  /** Present only for 'nav' — the item to select, so the caller need not look it up. */
  item?: NavItem;
}

/**
 * Every command available right now: one per cluster, one per nav leaf.
 *
 * <p>This walks the categories itself rather than reusing `shell.allNavItems`, because a
 * row needs to name the category it came from and that helper returns bare items. The
 * thing to keep right is `subgroups`: Custom Resources nests one per CRD API group, so a
 * walk over `items` alone silently omits every CRD-backed kind — the same omission that
 * made the count badges wrong in #195. There is a test pinning a nested kind for exactly
 * that reason.
 *
 * <p>The currently-active cluster is left out: offering to switch to where you already
 * are wastes the top row, which is the one the palette is for.
 */
export function buildCommands(
  clusters: ClusterInfo[],
  categories: NavCategory[],
  activeCluster: string | null,
): Command[] {
  const clusterCommands: Command[] = clusters
    .filter((c) => c.id !== activeCluster)
    .map((c) => ({
      key: 'cluster:' + c.id,
      kind: 'cluster' as const,
      label: c.name,
      hint: c.id,
      target: c.id,
    }));

  const navCommands: Command[] = categories.flatMap((category) => {
    const own = category.items.map((item) => ({ item, hint: category.label }));
    const nested = (category.subgroups ?? []).flatMap((group) =>
      group.items.map((item) => ({ item, hint: category.label + ' › ' + group.label })),
    );
    return [...own, ...nested].map(({ item, hint }) => ({
      key: 'nav:' + item.id,
      kind: 'nav' as const,
      label: item.label,
      hint,
      target: item.id,
      item,
    }));
  });

  return [...clusterCommands, ...navCommands];
}

/**
 * How well `query` matches `text`, or -1 for no match.
 *
 * <p>Three tiers, best first: a prefix match, a match at a word boundary, then a
 * subsequence anywhere (so "rs" finds "ReplicaSets"). Within a tier a shorter target wins,
 * because when "pod" matches both "Pods" and "Pod Disruption Budgets" the exact-ish one is
 * what was meant.
 */
export function score(query: string, text: string): number {
  const q = query.trim().toLowerCase();
  const t = text.toLowerCase();
  if (!q) {
    return 0;
  }
  if (t.startsWith(q)) {
    return 1000 - t.length;
  }
  // A word boundary is a space, a hyphen or the CRD-group separator.
  if (new RegExp('(^|[\\s\\-›/])' + escapeRegExp(q)).test(t)) {
    return 500 - t.length;
  }
  return subsequence(q, t) ? 100 - t.length : -1;
}

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** Every character of `q` appearing in `t` in order, not necessarily adjacent. */
function subsequence(q: string, t: string): boolean {
  let at = 0;
  for (const ch of t) {
    if (ch === q[at]) {
      at += 1;
      if (at === q.length) {
        return true;
      }
    }
  }
  return at === q.length;
}

/**
 * The commands worth showing for `query`, best first, capped at `limit`.
 *
 * <p>A command matches on its label or its hint, so "network" finds the kinds filed under
 * the Network category and a cluster is reachable by id as well as by name. The label's
 * score wins when both match, since that is the text being read.
 *
 * <p>With an empty query the list is returned in natural order rather than sorted, so the
 * palette opens showing clusters first — the switch is the common case.
 */
export function filterCommands(commands: Command[], query: string, limit = 30): Command[] {
  if (!query.trim()) {
    return commands.slice(0, limit);
  }
  return commands
    .map((command) => ({
      command,
      rank: Math.max(score(query, command.label), score(query, command.hint) - 50),
    }))
    .filter((scored) => scored.rank >= 0)
    .sort((a, b) => b.rank - a.rank)
    .slice(0, limit)
    .map((scored) => scored.command);
}

/** Wrap an index into `[0, length)` so ↑ at the top lands on the last row. */
export function wrapIndex(index: number, length: number): number {
  if (length <= 0) {
    return 0;
  }
  return ((index % length) + length) % length;
}

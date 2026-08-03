import type { ClusterInfo, NavCategory, NavItem, SearchHit, SearchResponse } from './types';

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
type CommandKind = 'cluster' | 'nav' | 'object';

export interface Command {
  /** Stable key for :key and for tests. */
  key: string;
  kind: CommandKind;
  /** Primary text — the cluster name, the kind's label, or the object's name. */
  label: string;
  /** Secondary text: the cluster id, the category the kind sits under, or `Kind · namespace`. */
  hint: string;
  /** Cluster id for 'cluster', nav item id for 'nav', resource id for 'object'. */
  target: string;
  /** Present only for 'nav' — the item to select, so the caller need not look it up. */
  item?: NavItem;
  /** Present only for 'object' — everything needed to address and open the object. */
  hit?: SearchHit;
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
 *
 * <p>`minRank` raises the bar. Pass {@link STRONG_MATCH} to drop rows that only matched as a
 * loose subsequence — see `mergeCommands` for why that matters once objects are in the list.
 */
export function filterCommands(commands: Command[], query: string, limit = 30, minRank = 0): Command[] {
  if (!query.trim()) {
    return commands.slice(0, limit);
  }
  return commands
    .map((command) => ({
      command,
      rank: Math.max(score(query, command.label), score(query, command.hint) - 50),
    }))
    .filter((scored) => scored.rank >= minRank)
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

// ---------------------------------------------------------------------------
// Global search (GH#259): the same palette, now also finding OBJECTS.
//
// Until this, the palette searched navigation targets — it found the Pods kind and never a
// pod. The server-side engine (web/search) finds objects across a bounded set of kinds; what
// follows is only how those hits become palette rows, so it stays DOM-free and testable.
// ---------------------------------------------------------------------------

/** Below this the query is too broad to be worth 13 list calls per keystroke. */
const MIN_SEARCH_CHARS = 2;

/** How many navigation rows keep their place once object hits arrive — see `mergeCommands`. */
const NAV_ROWS_WITH_OBJECTS = 5;

/**
 * The rank floor that excludes `score()`'s weakest tier — a bare subsequence.
 *
 * Above every subsequence score (at most 100) and below every word-boundary one (500 on a
 * label, 450 via a hint, minus a name length that never approaches 250). It exists because
 * the subsequence tier is right when nav labels are ALL there is — "rs" should find
 * "ReplicaSets" — and wrong once real objects are competing for the same rows: measured
 * against the simulator, typing `sim` put "Persistent Volumes" and "Persistent Volume
 * Claims" above every actual object, on the strength of s…i…m appearing in that order.
 */
export const STRONG_MATCH = 200;

/** Whether `query` is worth sending to the search endpoint. */
export function shouldSearch(query: string): boolean {
  return query.trim().length >= MIN_SEARCH_CHARS;
}

/**
 * Search hits as palette rows.
 *
 * The hint is `Kind · namespace` because a bare name is not addressable: names collide across
 * namespaces, and the reader has to be able to tell the two `postgresql` Services apart before
 * pressing Enter. Cluster-scoped objects say so rather than showing an empty namespace, which
 * would read as "namespace unknown".
 */
export function objectCommands(hits: SearchHit[]): Command[] {
  return hits.map((hit) => ({
    key: `object:${hit.resourceId}:${hit.namespace ?? ''}:${hit.name}`,
    kind: 'object' as const,
    label: hit.name,
    hint: hit.namespace ? `${hit.kind} · ${hit.namespace}` : `${hit.kind} · cluster-scoped`,
    target: hit.resourceId,
    hit,
  }));
}

/**
 * Navigation rows first, then object rows.
 *
 * Not one merged ranking, deliberately. Navigation matches are computed locally and appear on
 * the keystroke; object hits arrive ~200ms later over the network. Interleaving the two by
 * score would re-order the list under the reader's fingers exactly when they are about to
 * press Enter — the armed row would be a different thing than the one they aimed at. Keeping
 * the groups in fixed order means arriving results only ever APPEND.
 *
 * Navigation is capped when objects are present so the two dozen kinds matching "co" cannot
 * push every object off the visible list; the cap lifts when there is nothing to make room for.
 */
export function mergeCommands(navCommands: Command[], objects: Command[], limit = 30): Command[] {
  const navCap = objects.length > 0 ? NAV_ROWS_WITH_OBJECTS : limit;
  return [...navCommands.slice(0, navCap), ...objects].slice(0, limit);
}

/** One thing the reader should know about the scope of a result set. */
export interface ScopeNote {
  text: string;
  /** Long-form detail for a `title` tooltip (the full list behind a count). */
  detail?: string;
}

/**
 * What this result set does and does not cover, in the reader's words.
 *
 * This is the load-bearing half of the feature. Search covers a bounded set of kinds, so a
 * CRD-backed object simply is not in the results; a dropdown that shows twenty rows and says
 * nothing else implies it looked everywhere. GH#157 made the overviews report truncation
 * against the real total for the same reason — a silent cap is a wrong answer, not a short one.
 */
export function scopeNotes(result: SearchResponse | null, shown: number): ScopeNote[] {
  if (!result) {
    return [];
  }
  const notes: ScopeNote[] = [];
  if (result.total > shown) {
    notes.push({ text: `showing ${shown} of ${result.total}` });
  } else {
    notes.push({ text: `${result.total} ${result.total === 1 ? 'match' : 'matches'}` });
  }
  if (result.namespace) {
    notes.push({ text: `in ${result.namespace}`, detail: 'The namespace filter is scoping these results.' });
  }
  if (result.unsearchedKinds.length > 0) {
    notes.push({
      text: `${result.unsearchedKinds.length} kinds not searched`,
      detail: `Global search covers ${result.searchedKinds.length} kinds. Not searched: ${result.unsearchedKinds.join(', ')}.`,
    });
  }
  if (result.skippedKinds.length > 0) {
    notes.push({
      text: `${result.skippedKinds.length} could not be listed`,
      detail: result.skippedKinds.map((k) => `${k.kind}: ${k.reason}`).join('\n'),
    });
  }
  return notes;
}

import { objStateLabel, objStateTone } from './kube';
import type { ParsedFilter } from './objectFilter';
import { parseFilter, statusQueryable, withStatusTerm } from './objectFilter';
import type { KubeObject, ObjectState } from './types';

/**
 * The list header's status chips — the states present in the rows, each a click away from
 * being the filter.
 *
 * <p>`status:Failed` shipped in #337 as syntax. Syntax nobody knows is a feature nobody has:
 * the operator who arrives at Pods directly still wants "show me the broken ones", and the one
 * who arrives from an overview click (#338) needs the filter that brought them there to be
 * legible and removable rather than mysterious. This is that affordance (GH#341).
 *
 * <h2>The chips are read off the rows, never off a list of kinds</h2>
 *
 * A per-kind table of "the states a Pod can be in" would be a second copy of the server's
 * vocabulary, and a copy goes stale the day a verdict gains a word — silently, because both
 * copies keep answering. The states shown are exactly the labels present on the rows in hand,
 * for the same reason `needsFullObject` reads the row rather than a kind list (#276). A kind
 * kweblens has no verdict for produces no labels, so it produces no chips and no empty rail,
 * without anything having to know which kinds those are.
 *
 * <h2>What a chip's number promises</h2>
 *
 * <b>Clicking this chip shows exactly this many rows.</b> That is the whole point of the epic
 * — a number that turns out to be a different number when you act on it is #157 and #316 again
 * — so the counting is arranged to make it true rather than to be checked afterwards:
 *
 * <ul>
 * <li>The rows counted are the ones every OTHER narrowing already selects — the namespace, the
 * Helm scope, and every filter term except the positive `status:` ones. That set is what the
 * caller passes as `rows`, and the click replaces only the status term, so the two sets differ
 * by exactly the one predicate the chip applies.
 * <li>Each chip carries the exact query its click installs, so the component has nothing to
 * compute and a test can filter with it directly and compare.
 * <li>A state whose count is zero is not offered. `0 Failed` opening an empty list is a worse
 * answer than plain text — the epic says so, and here the zero cannot even arise: a label is
 * only known because a row carries it.
 * </ul>
 *
 * <h2>A query that did not parse gets no chips</h2>
 *
 * A broken query narrows nothing, so every row is on screen (#322's rule, restated by the
 * header's own error row). Offering a chip then would be offering to add a term to a query that
 * is already refused — its number would describe rows the table is not showing, and its click
 * would install a term into a string that still would not compile. The rail goes away until the
 * query means something again.
 */
export interface StatusChip {
  /** The server's word for the state, verbatim — the same one the overview card counts. */
  label: string;
  tone: ObjectState['tone'];
  /** Rows in this state, among the rows the other narrowings already selected. */
  count: number;
  /** Whether the query currently selects this state. */
  active: boolean;
  /** The query this chip's click installs: selecting it, or clearing it when it is active. */
  query: string;
}

/** Counted state, before it knows whether the query selects it. */
interface Bucket {
  tone: ObjectState['tone'];
  count: number;
}

/** The matchers of the query's positive `status:` terms — what "this state is selected" means. */
function statusSelectors(parsed: ParsedFilter): ((v: string) => boolean)[] {
  // A NEGATED status term is not a selection of anything: `-status:Running` says which state to
  // exclude, and lighting the Running chip for it would offer to clear a filter that is not the
  // one in force. It stays in the query when a chip is clicked (see `withStatusTerm`), and it
  // has already removed those rows from the counted set, so its chip simply is not there.
  return parsed.terms.flatMap((t) => (!t.negated && t.atom.type === 'status' ? [t.atom.match] : []));
}

/**
 * The chips for these rows under this query.
 *
 * <p>`rows` must be the rows narrowed by everything EXCEPT the positive `status:` terms — see
 * the promise above. One pass over them, then work proportional to the handful of distinct
 * states, so this is cheap enough to be a computed that re-runs on every keystroke.
 */
export function statusChips(rows: KubeObject[], query: string): StatusChip[] {
  const parsed = parseFilter(query);
  if (parsed.error !== null) {
    return [];
  }
  const buckets = new Map<string, Bucket>();
  for (const o of rows) {
    const label = objStateLabel(o);
    if (label === '') {
      continue;
    }
    const found = buckets.get(label);
    if (found) {
      found.count++;
    } else {
      // First row wins the tone. The server derives label and tone from one verdict, so two
      // rows cannot honestly disagree; if a payload ever does, one chip with one colour is
      // still the right answer, and the alternative — splitting a state in two — would break
      // the equality with the card, which counts by label alone.
      buckets.set(label, { tone: objStateTone(o) ?? 'idle', count: 1 });
    }
  }
  const selectors = statusSelectors(parsed);
  return (
    [...buckets.entries()]
      // `statusQueryable`, not a rule of this file's own: a state a card renders as plain text
      // because no term can select it must not become a chip either, and the two answers have
      // to be the same answer (#338).
      .filter(([label]) => statusQueryable(label))
      .map(([label, b]) => {
        const active = selectors.some((m) => m(label));
        return {
          label,
          tone: b.tone,
          count: b.count,
          active,
          query: withStatusTerm(query, active ? null : label),
        };
      })
      // Most-populous first, like the overview card's breakdown (`Tally.toKindHealth`): the
      // shape of the kind should read before any single number. The alphabetical tie-break is
      // this side's own — the card breaks ties on insertion order, which here would be the
      // order rows happened to arrive in, and a watch event re-ordering the rail under the
      // pointer is worse than an arbitrary but stable order.
      .sort((a, b) => b.count - a.count || a.label.localeCompare(b.label))
  );
}

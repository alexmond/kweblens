import { checkedData } from '../checkState';
import type { CheckState } from '../checkState';
import type { KindHealth, StateCount } from '../types';

/**
 * The Cluster overview's stat cards, derived from one server-side check.
 *
 * <p>Logic, not rendering, so the rules below are testable without a DOM — in particular the
 * one this page has been wrong about twice: a card must not show a number the cluster never
 * gave it. #316 rendered "0 Warnings" for a check that had FAILED, and #324 put a read failure
 * and an action failure in one slot. Here a check that did not answer is `—`, and its states
 * are not offered as links, because a state that could not be counted must not become a click
 * that claims it can be.
 *
 * <p>The Nodes and Namespaces numbers come from `/overview/cluster` rather than from a list
 * the shell already holds. That is deliberate: the states under the number are counted from
 * the same pass over the same objects (`StatusVocabulary`), so "click 3 Ready and get exactly
 * those three rows" is structural. A total taken from one source and a breakdown from another
 * is precisely the discrepancy GH#336 exists to prevent.
 */
export interface ClusterCard {
  /** The kind the card opens — Node, Namespace. Cluster-scoped, both of them. */
  kind: string;
  label: string;
  /** The total, `…` while the check is in flight, `—` when it never answered. */
  value: number | string;
  states: StateCount[];
  /** Whether the states may be clicked through to a filtered list of exactly those objects. */
  selectable: boolean;
}

/**
 * The cards to show before the answer arrives.
 *
 * <p>Only a skeleton so the band keeps its shape rather than popping into existence — the real
 * set is whatever the server judged, never this list. It is not a second copy of the server's
 * vocabulary: nothing here decides what is counted or what it is called.
 */
const PLACEHOLDERS: { kind: string; label: string }[] = [
  { kind: 'Node', label: 'Nodes' },
  { kind: 'Namespace', label: 'Namespaces' },
];

export function clusterCards(state: CheckState<KindHealth[]>): ClusterCard[] {
  const health = checkedData(state);
  if (!health) {
    const value = state.status === 'checking' ? '…' : '—';
    return PLACEHOLDERS.map((p) => ({ ...p, value, states: [], selectable: false }));
  }
  return health.map((k) =>
    k.error
      ? // "Could not check" must never render as a healthy zero. The card still opens the
        // kind's list — that page is where the actual error is reported — but its (empty)
        // breakdown is not a set of links.
        { kind: k.kind, label: `${k.label} · unavailable`, value: '—', states: [], selectable: false }
      : { kind: k.kind, label: k.label, value: k.total, states: k.states ?? [], selectable: true },
  );
}

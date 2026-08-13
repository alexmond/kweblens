import { describe, expect, it } from 'vitest';

import type { CheckState } from '../checkState';
import type { KindHealth } from '../types';
import { clusterCards } from './clusterOverviewCards';

// The Cluster overview's cards. Everything here is about the difference between "we counted
// zero" and "nobody answered" — the distinction #316 lost when it rendered "0 Warnings" for a
// check that had failed, and the reason a state only becomes a link once it has been counted.

const kind = (over: Partial<KindHealth> = {}): KindHealth => ({
  id: 'nodes',
  label: 'Nodes',
  kind: 'Node',
  total: 4,
  ok: 3,
  attention: 1,
  suspended: 0,
  states: [
    { label: 'Ready', count: 2, tone: 'ok' },
    { label: 'Ready,SchedulingDisabled', count: 1, tone: 'warn' },
    { label: 'NotReady', count: 1, tone: 'err' },
  ],
  needsAttention: [],
  truncated: false,
  ...over,
});

const checked = (data: KindHealth[]): CheckState<KindHealth[]> => ({ status: 'checked', data });

describe('the cards the Cluster overview shows', () => {
  it('renders the kinds the server judged, with their states', () => {
    const cards = clusterCards(
      checked([kind(), kind({ id: 'namespaces', label: 'Namespaces', kind: 'Namespace', total: 3, states: [] })]),
    );

    expect(cards.map((c) => [c.kind, c.value, c.selectable])).toEqual([
      ['Node', 4, true],
      ['Namespace', 3, true],
    ]);
    expect(cards[0].states.map((s) => s.label)).toEqual(['Ready', 'Ready,SchedulingDisabled', 'NotReady']);
  });

  it('shows … while the check is in flight and — when it never answered', () => {
    expect(clusterCards({ status: 'checking' }).map((c) => c.value)).toEqual(['…', '…']);
    expect(clusterCards({ status: 'unchecked', message: 'boom' }).map((c) => c.value)).toEqual(['—', '—']);
  });

  it('offers no state links for a check that did not answer', () => {
    // A state that could not be counted must not become a link that claims it can be. Both
    // the failure and the in-flight case, because "any moment now" is not a count either.
    for (const state of [{ status: 'checking' } as const, { status: 'unchecked', message: 'boom' } as const]) {
      expect(clusterCards(state).every((c) => !c.selectable && c.states.length === 0)).toBe(true);
    }
  });

  it('marks a kind that could not be listed as unavailable rather than empty', () => {
    // The card must not read as a healthy zero, and its (absent) breakdown must not be
    // clickable — but the card itself still opens the list, which is where the real error is
    // reported.
    const cards = clusterCards(checked([kind({ error: 'nodes is forbidden', total: 0, states: [] })]));

    expect(cards).toHaveLength(1);
    expect(cards[0].value).toBe('—');
    expect(cards[0].label).toBe('Nodes · unavailable');
    expect(cards[0].selectable).toBe(false);
  });

  it('does not manufacture a card for a kind the server did not send', () => {
    // Events are the third kind in this category and carry no verdict, so the server does not
    // send them. A placeholder list that survived into the answer would put an inert card on
    // the page for a check nobody ran.
    expect(clusterCards(checked([kind()])).map((c) => c.kind)).toEqual(['Node']);
  });
});

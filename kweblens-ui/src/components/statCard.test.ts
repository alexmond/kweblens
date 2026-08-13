import { describe, expect, it } from 'vitest';

import { matchesFilter, parseFilter } from '../objectFilter';
import type { KubeObject, StateCount } from '../types';
import { cardStates, stateAction } from './statCard';

// Which lines of an overview card open something, and what they open (#338). All logic, so it
// is tested with no DOM: the template's only job is to render a <button> where there is a query
// and a <span> where there is not.

const state = (label: string, count: number, tone = 'ok'): StateCount => ({ label, count, tone });

const PODS: StateCount[] = [
  state('Running', 80),
  state('Completed', 6, 'idle'),
  state('Pending', 3, 'warn'),
  state('Failed', 0, 'err'),
  state('CrashLoopBackOff', 1, 'err'),
];

describe('a state line is a link only when pressing it would show something', () => {
  it('gives every populated state a query', () => {
    const linked = cardStates(PODS, true).filter((s) => s.query !== null);
    expect(linked.map((s) => s.label)).toEqual(['Running', 'Completed', 'Pending', 'CrashLoopBackOff']);
    expect(linked.map((s) => s.query)).toEqual([
      'status:Running',
      'status:Completed',
      'status:Pending',
      'status:CrashLoopBackOff',
    ]);
  });

  it('leaves a zero-count state as text', () => {
    // The ticket's own rule: `0 Failed` is worth saying, and opening an empty list to say it is
    // a worse answer than the line. Note it is still IN the list — not linking it is not the
    // same as hiding it.
    const failed = cardStates(PODS, true).find((s) => s.label === 'Failed');
    expect(failed).toMatchObject({ count: 0, query: null });
  });

  it('leaves every state as text on a card that was not made clickable', () => {
    expect(cardStates(PODS, false).every((s) => s.query === null)).toBe(true);
  });

  it('leaves a state the filter grammar cannot express as text', () => {
    // A label with a quote in it cannot be written as a term that selects exactly it, and a
    // link that selected a different set is the one thing this feature must not do.
    expect(cardStates([state('say "hi"', 4)], true)[0].query).toBeNull();
  });

  it('keeps the count and tone the card renders untouched', () => {
    expect(cardStates(PODS, true).map((s) => [s.label, s.count, s.tone])).toEqual(
      PODS.map((s) => [s.label, s.count, s.tone]),
    );
  });
});

describe('the query a line carries selects exactly the objects that line counted', () => {
  it('holds for each state of a fleet built to match the card', () => {
    // The equality the epic is about, without a server: build rows in the same states the card
    // counted, then run each line's own query over them and check the row count equals the
    // number printed on that line.
    const rows: KubeObject[] = PODS.flatMap((s) =>
      Array.from({ length: s.count }, (_, i) => ({
        kind: 'Pod',
        metadata: { name: `${s.label.toLowerCase()}-${i}` },
        kweblensState: { label: s.label, tone: 'ok' as const },
      })),
    );
    for (const line of cardStates(PODS, true)) {
      if (line.query === null) {
        continue;
      }
      const filter = parseFilter(line.query);
      expect(filter.error).toBeNull();
      expect(rows.filter((o) => matchesFilter(o, filter))).toHaveLength(line.count);
    }
  });
});

describe('what a screen reader is told the line does', () => {
  it('opens with the visible text, then says where it goes', () => {
    // WCAG 2.5.3: the accessible name has to contain the words on screen, or speech control
    // cannot reach the control by reading it.
    const line = cardStates(PODS, true)[2];
    expect(stateAction(line, 'Pods')).toBe('3 Pending — show these Pods');
    expect(stateAction(line, 'Pods').startsWith(`${line.count} ${line.label}`)).toBe(true);
  });
});

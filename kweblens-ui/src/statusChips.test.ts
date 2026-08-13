import { describe, expect, it } from 'vitest';

import { matchesFilter, parseFilter, withStatusTerm } from './objectFilter';
import { statusChips } from './statusChips';
import type { KubeObject } from './types';

// The list header's status chips. Nearly all of this is the ONE property the epic turns on —
// a chip's number is the number of rows its click produces — so most cases below act the click
// out and count, rather than asserting the chips look right.

const stated = (name: string, label: string, tone: 'ok' | 'warn' | 'err' | 'idle'): KubeObject => ({
  kind: 'Pod',
  metadata: { name, namespace: 'prod', labels: { app: name.startsWith('web') ? 'web' : 'batch' } },
  kweblensState: { label, tone },
});

/** A fleet with five states, two of them sharing a stem, and one row nobody judged. */
const PODS: KubeObject[] = [
  stated('web-1', 'Running', 'ok'),
  stated('web-2', 'Running', 'ok'),
  stated('web-3', 'Running', 'ok'),
  stated('batch-1', 'Completed', 'idle'),
  stated('batch-2', 'Complete', 'ok'),
  stated('web-4', 'Pending', 'warn'),
  stated('web-5', 'CrashLoopBackOff', 'err'),
  { kind: 'Endpoints', metadata: { name: 'unjudged', namespace: 'prod' } },
];

/** The rows a query selects out of a set — the list the table would show. */
const rowsFor = (objects: KubeObject[], query: string): string[] => {
  const filter = parseFilter(query);
  return objects.filter((o) => matchesFilter(o, filter)).map((o) => o.metadata?.name ?? '');
};

/**
 * The shell's `statusRows`: everything except the positive `status:` terms.
 *
 * Reproduced here rather than imported so the test does not depend on App.vue, but it is the
 * same one line — `filterObjects(objects, withStatusTerm(query, null), scope)` — and the cases
 * below are worthless if it drifts from it, so `the chips and the shell agree` pins it.
 */
const base = (objects: KubeObject[], query: string): KubeObject[] => {
  const filter = parseFilter(withStatusTerm(query, null));
  return objects.filter((o) => matchesFilter(o, filter));
};

const chipsFor = (objects: KubeObject[], query: string) => statusChips(base(objects, query), query);

describe('the chips are read off the rows', () => {
  it('offers one per state present, with its count', () => {
    expect(chipsFor(PODS, '').map((c) => [c.label, c.count])).toEqual([
      ['Running', 3],
      ['Complete', 1],
      ['Completed', 1],
      ['CrashLoopBackOff', 1],
      ['Pending', 1],
    ]);
  });

  it('orders them most-populous first, like the card breakdown', () => {
    const counts = chipsFor(PODS, '').map((c) => c.count);
    expect(counts).toEqual([...counts].sort((a, b) => b - a));
  });

  it('breaks ties by name rather than by which row arrived first', () => {
    // The rows' order is whatever the API and then the watch produced. A rail that re-ordered
    // itself under the pointer as an event landed would move a chip out from under a click.
    const forward = chipsFor(PODS, '').map((c) => c.label);
    const backward = chipsFor([...PODS].reverse(), '').map((c) => c.label);
    expect(backward).toEqual(forward);
  });

  it('carries the tone the SERVER gave the state, not one inferred from the word', () => {
    const byLabel = Object.fromEntries(chipsFor(PODS, '').map((c) => [c.label, c.tone]));
    // The pair that makes the point: two states sharing a stem, coloured differently by the
    // verdicts behind them. Any keyword rule over the letters would give them the same tone.
    expect(byLabel.Completed).toBe('idle');
    expect(byLabel.Complete).toBe('ok');
    expect(byLabel.CrashLoopBackOff).toBe('err');
  });

  it('gives a kind with no verdict no chips at all', () => {
    // Not an empty rail and not a cheerful "OK" — the absence of a claim. Nothing here knows
    // which kinds those are, which is the point: a per-kind list would be a second copy of
    // the server's vocabulary and would go stale silently (#276's reasoning).
    const unjudged = [
      { kind: 'ConfigMap', metadata: { name: 'a' } },
      { kind: 'ConfigMap', metadata: { name: 'b' } },
    ];
    expect(statusChips(unjudged, '')).toEqual([]);
    expect(statusChips([], '')).toEqual([]);
  });

  it('ignores a state the server sent empty', () => {
    const blank = [{ kind: 'Pod', metadata: { name: 'blank' }, kweblensState: { label: '', tone: 'ok' as const } }];
    expect(statusChips(blank, '')).toEqual([]);
  });
});

describe('a chip count equals the rows its click produces', () => {
  it.each(['', 'ns:prod', '-web-1', 'app=web', '-status:Running'])(
    'holds for every chip, alongside the query %s',
    (query) => {
      const chips = chipsFor(PODS, query);
      expect(chips.length).toBeGreaterThan(0);
      for (const chip of chips) {
        expect(rowsFor(PODS, chip.query), `${chip.label} beside ${query}`).toHaveLength(chip.count);
      }
    },
  );

  it('holds while a chip is already on — including the one that is on', () => {
    // Arriving from an overview click lands here: the query is `status:X` and the rail must
    // still describe every state, or switching means clearing by hand first.
    const chips = chipsFor(PODS, 'status:Pending');
    expect(chips.map((c) => c.label)).toContain('Running');
    for (const chip of chips) {
      const next = chip.active ? withStatusTerm('status:Pending', null) : chip.query;
      const expected = chip.active ? PODS.length : chip.count;
      expect(rowsFor(PODS, next), chip.label).toHaveLength(expected);
    }
  });

  it('never offers a state with nothing in it', () => {
    // "0 Failed opening an empty list is a worse answer than plain text" — and here the zero
    // cannot arise, because a label is only known from a row that carries it.
    for (const chip of chipsFor(PODS, '')) {
      expect(chip.count).toBeGreaterThan(0);
    }
  });
});

describe('what the click installs', () => {
  it('selects the state, keeping the rest of the query', () => {
    const chip = chipsFor(PODS, 'ns:prod -web-1').find((c) => c.label === 'Running');
    expect(chip?.query).toBe('ns:prod -web-1 status:Running');
    expect(rowsFor(PODS, chip?.query ?? '')).toEqual(['web-2', 'web-3']);
  });

  it('clears the state when it is already on, rather than adding it twice', () => {
    const chip = chipsFor(PODS, 'ns:prod status:Running').find((c) => c.label === 'Running');
    expect(chip?.active).toBe(true);
    expect(chip?.query).toBe('ns:prod');
  });

  it('switches from one state to another in one click', () => {
    const chip = chipsFor(PODS, 'status:Running').find((c) => c.label === 'Pending');
    expect(chip?.active).toBe(false);
    expect(chip?.query).toBe('status:Pending');
    expect(rowsFor(PODS, chip?.query ?? '')).toEqual(['web-4']);
  });
});

describe('which chip is lit', () => {
  it('lights the state an overview click arrived with', () => {
    const chips = chipsFor(PODS, 'status:CrashLoopBackOff');
    expect(chips.filter((c) => c.active).map((c) => c.label)).toEqual(['CrashLoopBackOff']);
  });

  it('lights it whatever case the query is written in', () => {
    expect(
      chipsFor(PODS, 'status:crashloopbackoff')
        .filter((c) => c.active)
        .map((c) => c.label),
    ).toEqual(['CrashLoopBackOff']);
  });

  it('lights only the whole label, so a state cannot light its stem-mate', () => {
    // The trap `status:` was made exact for. Lighting Completed from `status:Complete` would
    // claim a filter selects rows it does not.
    expect(
      chipsFor(PODS, 'status:Complete')
        .filter((c) => c.active)
        .map((c) => c.label),
    ).toEqual(['Complete']);
  });

  it('lights every state a regex term selects, because it selects them all', () => {
    // `status:/o/` is one term selecting three states; a rail showing one of them lit would
    // describe a filter narrower than the one running.
    const lit = statusChips(PODS, 'status:/o/')
      .filter((c) => c.active)
      .map((c) => c.label);
    expect([...lit].sort()).toEqual(['Complete', 'Completed', 'CrashLoopBackOff']);
  });

  it('lights nothing for a NEGATED status term', () => {
    // `-status:Running` is not a selection of Running — offering to "clear" it from the
    // Running chip would clear a filter that is not the one in force. The rows it excludes are
    // already gone from the counted set, so that chip is simply not there.
    const chips = chipsFor(PODS, '-status:Running');
    expect(chips.some((c) => c.active)).toBe(false);
    expect(chips.map((c) => c.label)).not.toContain('Running');
  });
});

describe('a query that did not parse gets no chips', () => {
  it.each(['/bad(/', 'status:', 'name:"half typed', 'key>1'])('%s', (query) => {
    // The filter is not in force, so every row is on screen (#322). A chip then would offer a
    // number about rows the table is not showing, and its click would install a term into a
    // string that still would not compile. The header's error row is what is said instead.
    expect(parseFilter(query).error).not.toBeNull();
    expect(statusChips(PODS, query)).toEqual([]);
  });
});

describe('adversarial rows', () => {
  it('keeps one chip when two rows disagree about a state′s tone', () => {
    // Cannot happen honestly — the server derives label and tone from one verdict — but
    // splitting the state in two would break the equality with the card, which counts by
    // label alone. One chip, first tone seen.
    const mixed = [stated('a', 'Running', 'ok'), stated('b', 'Running', 'err')];
    expect(statusChips(mixed, '')).toEqual([
      { label: 'Running', tone: 'ok', count: 2, active: false, query: 'status:Running' },
    ]);
  });

  it('offers a state whose name has a space, and its click selects exactly it', () => {
    const claims = [
      stated('pvc-1', 'Nearly full', 'warn'),
      stated('pvc-2', 'Bound', 'ok'),
      stated('pvc-3', 'Nearly full', 'warn'),
    ];
    const chip = statusChips(claims, '').find((c) => c.label === 'Nearly full');
    expect(chip?.count).toBe(2);
    expect(chip?.query).toBe('status:"Nearly full"');
    expect(rowsFor(claims, chip?.query ?? '')).toEqual(['pvc-1', 'pvc-3']);
  });

  it('drops a state that cannot be written as a term at all', () => {
    // A label carrying a quote cannot be quoted back into this grammar, so no term selects
    // it. Dropping the chip is the honest answer; showing one that finds nothing is the lie
    // this whole file is about. Nothing in the vocabulary does this — the guard is for the
    // day something does.
    const odd = [stated('a', 'broke"n', 'err'), stated('b', 'Running', 'ok')];
    expect(statusChips(odd, '').map((c) => c.label)).toEqual(['Running']);
  });

  it('stays linear over three thousand rows', () => {
    // It recomputes on every keystroke beside a filter that also runs per keystroke, and
    // `useResourceData` coalesces a burst of watch events into one update per frame — neither
    // helps if this walks the list more than once.
    const many = Array.from({ length: 3000 }, (_, i) => stated(`web-${i}`, i % 3 === 0 ? 'Running' : 'Pending', 'ok'));
    const started = performance.now();
    const chips = statusChips(many, 'status:Running');
    expect(chips.map((c) => c.count)).toEqual([2000, 1000]);
    expect(performance.now() - started).toBeLessThan(200);
  });
});

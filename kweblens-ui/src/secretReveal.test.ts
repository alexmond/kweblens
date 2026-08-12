import { describe, expect, it } from 'vitest';

import { afterRemove, afterReseed, toggleReveal } from './secretReveal';

describe('toggleReveal', () => {
  it('reveals a masked row and re-masks a revealed one', () => {
    expect([...toggleReveal(new Set(), 2)]).toEqual([2]);
    expect([...toggleReveal(new Set([2]), 2)]).toEqual([]);
  });

  it('leaves the other rows alone', () => {
    expect([...toggleReveal(new Set([0, 3]), 1)].sort()).toEqual([0, 1, 3]);
  });

  it('returns a new set, because the caller holds it in a ref', () => {
    const before = new Set([1]);
    expect(toggleReveal(before, 2)).not.toBe(before);
    expect([...before]).toEqual([1]);
  });
});

describe('afterRemove', () => {
  it('drops the removed row', () => {
    expect([...afterRemove(new Set([1]), 1)]).toEqual([]);
  });

  it('pulls later indices down so they still name their own row', () => {
    // Rows [a b c d], c revealed (index 2). Remove b (index 1) — c is now index 1.
    expect([...afterRemove(new Set([2]), 1)]).toEqual([1]);
  });

  it('leaves earlier indices where they are', () => {
    expect([...afterRemove(new Set([0]), 2)]).toEqual([0]);
  });

  it('never leaves an index pointing at a row that slid into place', () => {
    // The leak this whole module exists to prevent: rows [a b c], only c revealed. Removing b
    // must NOT leave index 2 set — there is no row 2 any more, and a naive delete(1) would.
    const after = afterRemove(new Set([2]), 1);
    expect(after.has(2)).toBe(false);
    expect([...after]).toEqual([1]);
  });

  it('handles several revealed rows at once', () => {
    expect([...afterRemove(new Set([0, 2, 4]), 2)].sort((a, b) => a - b)).toEqual([0, 3]);
  });
});

describe('afterReseed', () => {
  it('re-masks everything, because the old indices name nothing', () => {
    expect([...afterReseed()]).toEqual([]);
  });
});

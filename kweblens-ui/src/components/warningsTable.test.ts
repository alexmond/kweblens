import { describe, expect, it } from 'vitest';

import { PANE_WIDE_PX } from '../responsive';
import type { EventSummary } from '../types';
import { WARN_COLUMN_WIDTHS, WARN_TABLE_MIN_WIDTH, warnColumns, warnMessageFloor } from './warningsTable';

// These pin the SHAPE of the sizing policy, not a rendering: #257 was not a narrow-screen bug,
// it was three bounded columns with no width losing their space to the one variable column.
// Rendering is measured against the running app with `scripts/ui-measure.mjs`; what a DOM-free
// test can hold is that the policy stays a policy.

const ev = (over: Partial<EventSummary> = {}): EventSummary => ({
  type: 'Warning',
  reason: 'Failed',
  object: 'Pod/a',
  namespace: 'ns',
  message: 'Error: ImagePullBackOff',
  age: '4m',
  ...over,
});

describe('Warnings table columns', () => {
  it('gives every bounded column a width and the variable one none', () => {
    const byTitle = Object.fromEntries(warnColumns().map((c) => [c.title, c]));
    expect(byTitle.Reason.width).toBe(WARN_COLUMN_WIDTHS.reason);
    expect(byTitle.Object.width).toBe(WARN_COLUMN_WIDTHS.object);
    expect(byTitle.Age.width).toBe(WARN_COLUMN_WIDTHS.age);
    // The whole point: Message is the only column that absorbs slack, so it declares no width.
    expect(byTitle.Message.width).toBeUndefined();
  });

  it('leaves Message real room even at the narrowest layout', () => {
    // Not "some room": more than any bounded column, since it holds the only variable text.
    expect(warnMessageFloor()).toBeGreaterThan(WARN_COLUMN_WIDTHS.object);
    expect(warnMessageFloor()).toBe(330);
  });

  it('reuses the one pane breakpoint rather than inventing a second number', () => {
    expect(WARN_TABLE_MIN_WIDTH).toBe(PANE_WIDE_PX);
  });

  it('fits the content each bounded width was chosen for', () => {
    // ~7px per character of 13px UI text, plus ~24px of cell padding. A rough model on purpose:
    // it is here to fail if someone shrinks a width to the point the known content cannot fit.
    const fits = (text: string, px: number) => text.length * 7 + 24 <= px;
    expect(fits('FailedCreatePodSandBox', WARN_COLUMN_WIDTHS.reason)).toBe(true);
    expect(fits('PersistentVolumeClaim/data-web-0', WARN_COLUMN_WIDTHS.object)).toBe(true);
    expect(fits('Age', WARN_COLUMN_WIDTHS.age)).toBe(true);
  });

  it('sorts Age by duration, not by the string', () => {
    const sorter = warnColumns().find((c) => c.title === 'Age')?.sorter;
    if (typeof sorter !== 'function') {
      throw new Error('Age needs a comparator: "9m" sorts after "10h" as a string');
    }
    expect(sorter(ev({ age: '9m' }), ev({ age: '10h' }))).toBeLessThan(0);
    expect(sorter(ev({ age: '2d' }), ev({ age: '30s' }))).toBeGreaterThan(0);
  });
});

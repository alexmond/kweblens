import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

import { autoHiddenCols, chromeWidth, columnWidth, nextColumnChoice, tableWidth } from './columnFit';
import { columnsFor, defaultHiddenCols } from './columns';
import { buildResourceColumns } from './table';

// The fixtures are the app's REAL column sets, not invented ones, and the widths they add up
// to are the numbers measured off the running app in #238:
//
//   viewport   container   nodes   pods
//   1024        687px      1514    1314
//   1200        863px      1514    1314
//   1900       1563px      1514    1314
//
// Pods is 1354 now, not the 1314 measured in #238: GH#341 gave the Status column an explicit
// 150px in place of the 110px floor, because it renders the server's state and the 110px cell
// sliced 16.55px off a `CrashLoopBackOff` pill. That is exactly the movement this fixture is
// here to make visible — the ticket's number stops being true, and the test says so rather
// than the table quietly losing one more column to the width.
//
// A fit test written against made-up columns proves the arithmetic and nothing about the
// tables it is for; these pin both. If a column is added to Pods or Nodes and these totals
// move, that is the point — the numbers in the ticket stop being true and the test says so.
const visibleColumns = (id: string) => {
  const hidden = defaultHiddenCols(id);
  return buildResourceColumns(
    id,
    columnsFor(id).filter((c) => !hidden.has(c.key)),
    {},
    {},
  );
};

const NODES = visibleColumns('nodes');
const PODS = visibleColumns('pods');
const NODES_CHROME = chromeWidth({ namespace: false, age: true }); // Nodes are cluster-scoped
const PODS_CHROME = chromeWidth({ namespace: true, age: true });

const kept = (cols: { key: string }[], hidden: ReadonlySet<string>) =>
  cols.filter((c) => !hidden.has(c.key)).map((c) => c.key);

describe('what a table wants', () => {
  it('adds up to the widths measured off the running app', () => {
    expect(tableWidth(NODES, NODES_CHROME)).toBe(1514);
    expect(tableWidth(PODS, PODS_CHROME)).toBe(1354);
  });

  it('charges a column its own width, or the readable floor when it declares none', () => {
    expect(columnWidth({ key: 'taints', width: 90 })).toBe(90);
    expect(columnWidth({ key: 'roles' })).toBe(110);
  });

  it('counts the framework columns the table renders for itself', () => {
    // select 40 + name 260 + menu 44 = 344 before anything optional.
    expect(chromeWidth({})).toBe(344);
    expect(chromeWidth({ age: true })).toBe(424);
    expect(chromeWidth({ namespace: true, age: true })).toBe(574);
    expect(chromeWidth({ namespace: true, age: true, expandable: true })).toBe(608);
  });
});

describe('autoHiddenCols', () => {
  it('hides nothing when the table already fits', () => {
    expect(autoHiddenCols(NODES, { available: 1563, chrome: NODES_CHROME }).size).toBe(0);
    expect(autoHiddenCols(PODS, { available: 1563, chrome: PODS_CHROME }).size).toBe(0);
  });

  it('hides nothing before the first layout, when no width is known yet', () => {
    expect(autoHiddenCols(NODES, { available: 0, chrome: NODES_CHROME }).size).toBe(0);
    expect(autoHiddenCols(NODES, { available: Number.NaN, chrome: NODES_CHROME }).size).toBe(0);
  });

  it('drops from the right until Nodes fits the 863px of a 1200px screen', () => {
    const hidden = autoHiddenCols(NODES, { available: 863, chrome: NODES_CHROME });
    expect(kept(NODES, hidden)).toEqual(['status', 'roles', 'taints', 'version']);
    expect(
      tableWidth(
        NODES.filter((c) => !hidden.has(c.key)),
        NODES_CHROME,
      ),
    ).toBeLessThanOrEqual(863);
    // The live usage bars are declared last, so they are the first thing width takes away.
    expect(hidden.has('m-disk')).toBe(true);
    expect(hidden.has('m-cpu')).toBe(true);
  });

  it('keeps the leading columns on Pods at the 687px of a 1024px screen', () => {
    const hidden = autoHiddenCols(PODS, { available: 687, chrome: PODS_CHROME });
    expect(kept(PODS, hidden)).toEqual(['ready']);
    expect(
      tableWidth(
        PODS.filter((c) => !hidden.has(c.key)),
        PODS_CHROME,
      ),
    ).toBeLessThanOrEqual(687);
  });

  it('never takes away a column the picker asked to keep', () => {
    const hidden = autoHiddenCols(NODES, { available: 863, chrome: NODES_CHROME, keep: new Set(['m-disk']) });
    expect(hidden.has('m-disk')).toBe(false);
    expect(kept(NODES, hidden)).toContain('m-disk');
    expect(
      tableWidth(
        NODES.filter((c) => !hidden.has(c.key)),
        NODES_CHROME,
      ),
    ).toBeLessThanOrEqual(863);
  });

  it('hides nothing when hiding cannot make it fit — losing columns AND still scrolling is worse', () => {
    // Every column pinned: the only outcome available is a horizontal scroll, so take none.
    const all = new Set(NODES.map((c) => c.key));
    expect(autoHiddenCols(NODES, { available: 863, chrome: NODES_CHROME, keep: all }).size).toBe(0);
    // And the degenerate end: not even the framework columns fit.
    expect(autoHiddenCols(NODES, { available: 200, chrome: NODES_CHROME }).size).toBe(0);
  });

  it('does not disagree with the pane breakpoint, because it never uses one', () => {
    // The guard behind #238's promise not to introduce a second breakpoint: the fit is
    // arithmetic over the column set, so no width literal appears in it at all. Read from
    // the Vitest root rather than `import.meta.url`, which is not a file: URL under jsdom —
    // the same trap `responsive.test.ts` records.
    const code = readFileSync(resolve(process.cwd(), 'src/columnFit.ts'), 'utf8')
      .split('\n')
      .filter((line) => !line.trim().startsWith('//') && !line.trim().startsWith('*'))
      .join('\n');
    expect(code).not.toMatch(/\b900\b/);
  });
});

describe('nextColumnChoice', () => {
  const empty = { hidden: new Set<string>(), keep: new Set<string>(), autoHidden: new Set<string>() };

  it('hides a visible column', () => {
    const next = nextColumnChoice('node', empty);
    expect([...next.hidden]).toEqual(['node']);
    expect(next.keep.size).toBe(0);
  });

  it('shows a column the user had hidden', () => {
    const next = nextColumnChoice('node', { ...empty, hidden: new Set(['node']) });
    expect(next.hidden.size).toBe(0);
    expect([...next.keep]).toEqual(['node']);
  });

  it('PINS a column the width had taken away, so it survives the next fit', () => {
    const next = nextColumnChoice('m-cpu', { ...empty, autoHidden: new Set(['m-cpu']) });
    expect([...next.keep]).toEqual(['m-cpu']);
    expect(next.hidden.size).toBe(0);
    expect(autoHiddenCols(NODES, { available: 863, chrome: NODES_CHROME, keep: next.keep }).has('m-cpu')).toBe(false);
  });

  it('unpins on the way back, so the column returns to being width-decided', () => {
    const pinned = { ...empty, keep: new Set(['m-cpu']) };
    const next = nextColumnChoice('m-cpu', pinned);
    expect(next.keep.size).toBe(0);
    expect([...next.hidden]).toEqual(['m-cpu']);
  });

  it('leaves the sets it was given alone', () => {
    const hidden = new Set(['node']);
    const keep = new Set(['m-cpu']);
    nextColumnChoice('status', { hidden, keep, autoHidden: new Set() });
    expect([...hidden]).toEqual(['node']);
    expect([...keep]).toEqual(['m-cpu']);
  });
});

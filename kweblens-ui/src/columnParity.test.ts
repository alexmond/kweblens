import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

import { columnsFor, printerColumnDefs } from './columns';
import type { KubeObject, PrinterColumn } from './types';

/**
 * The SPA half of the column-parity gate.
 *
 * This file is the *producer* of `column-parity/expected.json` and the first of its two
 * consumers; `kweblens-core`'s `ColumnParityTest` is the other. Neither side hand-writes an
 * expectation — the golden is rendered by the code below, which is the only reason comparing it
 * with a second implementation proves anything at all. See `column-parity/README.md`.
 */

/**
 * The corpus lives at the repository root so both modules reach it by the same name. It is found
 * by walking up rather than by a fixed `../..`, because vitest's working directory is not the
 * same when it is run by npm, by maven and by an editor — and a path that resolves to nothing
 * would make this whole file silently skip.
 */
function corpusDir(): string {
  let dir = process.cwd();
  for (let up = 0; up < 6; up++) {
    if (existsSync(resolve(dir, 'column-parity/objects.json'))) {
      return resolve(dir, 'column-parity');
    }
    dir = dirname(dir);
  }
  throw new Error(`column-parity/ not found above ${process.cwd()}`);
}

const CORPUS = corpusDir();

interface Corpus {
  now: string;
  statusColumns: Record<string, string[]>;
  cases: { kind: string; name: string; object: KubeObject }[];
  printerColumns: { name: string; columns: PrinterColumn[]; object: KubeObject }[];
}

interface Golden {
  cases: { kind: string; name: string; values: Record<string, string> }[];
  printerColumns: { name: string; values: Record<string, string> }[];
}

const corpus = JSON.parse(readFileSync(resolve(CORPUS, 'objects.json'), 'utf8')) as Corpus;

/** The keys a kind's values are recorded under: every column but the server's own state. */
function keysFor(kind: string): string[] {
  const excluded = new Set(corpus.statusColumns[kind] ?? []);
  return columnsFor(kind)
    .map((c) => c.key)
    .filter((k) => !excluded.has(k));
}

function render(): Golden {
  return {
    cases: corpus.cases.map((c) => {
      const columns = columnsFor(c.kind);
      const wanted = new Set(keysFor(c.kind));
      const values: Record<string, string> = {};
      for (const column of columns) {
        if (wanted.has(column.key)) {
          values[column.key] = column.render(c.object);
        }
      }
      return { kind: c.kind, name: c.name, values };
    }),
    printerColumns: corpus.printerColumns.map((c) => {
      const values: Record<string, string> = {};
      for (const column of printerColumnDefs(c.columns)) {
        values[column.key] = column.render(c.object);
      }
      return { name: c.name, values };
    }),
  };
}

describe('column parity corpus', () => {
  // Every `date`-typed printer column measures against the wall clock, so the golden is only a
  // fact if the clock is one. `now` travels in the corpus so the Java side can be handed the
  // same instant rather than a second guess at it.
  beforeAll(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(corpus.now));
    // Regeneration happens before either assertion, so one run with UPDATE_COLUMN_PARITY set
    // leaves a golden both tests then agree with — rather than one that the coverage check,
    // which reads the file, was still comparing against in its previous state.
    if (process.env.UPDATE_COLUMN_PARITY) {
      writeFileSync(resolve(CORPUS, 'expected.json'), JSON.stringify(render(), null, 2) + '\n');
    }
  });
  afterAll(() => {
    vi.useRealTimers();
  });

  // Deliberately reads the golden FILE rather than what render() just produced: comparing
  // render() with itself would pass for any column set at all. What this asserts is that the
  // golden on disk still describes every column `columns.ts` defines — i.e. that a column added
  // to the SPA reached the corpus, and did not just quietly stop being compared with anything.
  it('the golden covers every column of every kind the corpus names', () => {
    const golden = JSON.parse(readFileSync(resolve(CORPUS, 'expected.json'), 'utf8')) as Golden;
    const kinds = new Set(corpus.cases.map((c) => c.kind));
    expect(kinds.size).toBeGreaterThan(0);
    for (const kind of kinds) {
      const keys = keysFor(kind);
      expect(keys.length, `${kind} defines no columns`).toBeGreaterThan(0);
      const covered = golden.cases.filter((c) => c.kind === kind);
      expect(covered.length, `${kind} has no case in the golden`).toBeGreaterThan(0);
      for (const one of covered) {
        expect(Object.keys(one.values).sort(), `${kind} / ${one.name}`).toEqual([...keys].sort());
      }
    }
  });

  it('renders what the golden records', () => {
    const golden = JSON.parse(readFileSync(resolve(CORPUS, 'expected.json'), 'utf8')) as Golden;
    expect(render()).toEqual(golden);
  });
});

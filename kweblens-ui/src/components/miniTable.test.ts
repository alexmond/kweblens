import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

// ---- The `.mini` table's wrap policy, pinned without a DOM (#278) ----
//
// The policy itself is CSS, so this test reads the stylesheet and the templates as TEXT, the
// same way `responsive.test.ts` does — no browser, no jsdom, and it runs in `npm run check`.
//
// What it exists to stop coming back: Naive's `.n-drawer` sets `word-break: break-word` on the
// whole drawer, which inherits into every `.mini` cell. `break-word` is the deprecated alias
// for `overflow-wrap: anywhere`, and `anywhere` counts toward MIN-CONTENT — so a column's
// minimum width becomes one glyph and the auto table layout squeezes a column below its own
// values, which is how the Mounted By table came to render `Runnin`/`g` and to break a node
// FQDN mid-token at the drawer's narrow end. The rules below are the whole fix; if one of them
// is dropped the symptom returns silently, because nothing overflows and no line is long.
const root = process.cwd();
const css = readFileSync(resolve(root, 'src/styles.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
const componentDir = resolve(root, 'src/components');

/** Every `.vue` under components/, as `[name, source]`. */
const templates = (): [string, string][] =>
  readdirSync(componentDir)
    .filter((f) => f.endsWith('.vue'))
    .map((f) => [f, readFileSync(resolve(componentDir, f), 'utf8')]);

describe('the .mini wrap policy', () => {
  it('restores a whole word as a column\u2019s minimum width', () => {
    expect(css).toMatch(/\.mini th,\s*\.mini td\s*\{[^}]*word-break:\s*normal/);
    expect(css).toMatch(/\.mini th,\s*\.mini td\s*\{[^}]*overflow-wrap:\s*break-word/);
  });

  it('never lets a .mini cell shrink to a glyph again', () => {
    // `anywhere` (and its `word-break: break-word` alias) is the one value that lowers
    // min-content, which is the whole defect. `break-all` on a mono cell is deliberate and
    // checked below, so it is exempt.
    const miniCellRules = [...css.matchAll(/\.mini[^{}]*\{[^}]*\}/g)].map((m) => m[0]);
    for (const rule of miniCellRules) {
      expect(rule).not.toMatch(/overflow-wrap:\s*anywhere/);
      expect(rule).not.toMatch(/word-break:\s*break-word/);
    }
  });

  it('keeps break-all for mono cells, at a specificity that actually wins', () => {
    // A base64 secret value is one token with no break opportunity in it: preferring whole
    // words there would make the Value column demand its full width. `.mono` alone cannot say
    // so — `.mini td` (0,1,1) outranks `.mono` (0,1,0) — so the override is restated.
    expect(css).toMatch(/\.mini td\.mono\s*\{[^}]*word-break:\s*break-all/);
  });

  it('gives the table a scroller of its own for when the minimums do not fit', () => {
    expect(css).toMatch(/\.mini-scroll\s*\{[^}]*overflow-x:\s*auto/);
  });

  it('wraps every .mini table in a scroller', () => {
    // Without this the table does not shred a word — it spills out of the pane instead, which
    // is a different bug wearing the same clothes. Checked over the templates rather than
    // trusted to review: a new `.mini` is exactly the kind of thing added without knowing any
    // of the above.
    const seen: string[] = [];
    for (const [name, src] of templates()) {
      for (const m of src.matchAll(/<table\s+class="mini[^"]*"/g)) {
        const before = src.slice(0, m.index);
        const classes = [...before.matchAll(/class="([^"]*)"/g)].at(-1)?.[1] ?? '';
        seen.push(`${name}: ${classes}`);
        // `files-list` is the pod file browser's own 320px-tall scroller, which already does
        // this job; anything else must be the shared wrapper.
        expect(/\bmini-scroll\b|\bfiles-list\b/.test(classes)).toBe(true);
      }
    }
    // A regex that matches nothing would pass every assertion above it.
    expect(seen.length).toBeGreaterThanOrEqual(5);
  });
});

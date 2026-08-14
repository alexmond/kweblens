import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

// The overview card's stacked bar, checked as TEXT — the same trick `responsive.test.ts` uses,
// and for the same reason: the rule lives in `styles.css`, Vitest does not process CSS, so the
// stylesheet is read off disk and parsed rather than rendered. Comments are stripped first; a
// rule that only exists in prose is not a rule.
//
// What this pins is #352. `.ov-seg.tone-idle` and `.ov-seg.tone-empty` were one selector list
// sharing `var(--border)` — which is also `.ov-bar`'s own track colour — so a state with real
// objects in it (109 of 164 Secrets `Cluster-managed`) painted as the gap where nothing is.
// Nothing else could catch it: the bar is `aria-hidden` decoration with no text of its own, so
// `contrast-check.mjs` has nothing to sample, and every screenshot of it looked like a bar.
//
// Two claims, because either one alone can be true while the bar is broken: the tones must be
// DIFFERENT TOKENS from each other and from the track, and the tokens they name must resolve to
// different COLOURS in each theme — `--muted` and `--border` are two names, but if a palette
// edit ever gave them the same literal in one theme the bar would collide again in that theme
// only, which is exactly the shape of defect this project keeps shipping.
const css = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

/**
 * Every rule in the sheet as `{ selectors, body }`.
 *
 * Selector LISTS are split, which is the whole point: the defect this file exists for was two
 * selectors sharing one block, and a lookup that only recognised `<selector> {` reported "no
 * rule for .ov-seg.tone-idle" against the very stylesheet that has it — a true failure with a
 * message that sends the reader to the wrong place.
 *
 * Split rather than matched with a regex: `([^{}]+)\{([^{}]*)\}` says the same thing and
 * backtracks super-linearly (eslint fails the build on it). Splitting on `}` also handles an
 * at-rule for free — the last `{` in a chunk opens the declarations, so `@container … { .a {`
 * yields `.a`, and a chunk with no `{` at all is a closing brace and is dropped.
 */
const rules = css.split('}').flatMap((chunk) => {
  const open = chunk.lastIndexOf('{');
  if (open === -1) return [];
  const prelude = chunk.slice(0, open);
  return [
    {
      selectors: prelude
        .slice(prelude.lastIndexOf('{') + 1)
        .split(',')
        .map((s) => s.trim()),
      body: chunk.slice(open + 1),
    },
  ];
});

/** The declared value of `prop` on `selector`, e.g. `background` on `.ov-seg.tone-ok`. */
function declared(selector: string, prop: string): string {
  const rule = rules.find((r) => r.selectors.includes(selector));
  expect(rule, `no rule for ${selector}`).toBeDefined();
  const decl = new RegExp(`(^|[;{\\s])${prop}:\\s*([^;]+);`).exec((rule as (typeof rules)[number]).body);
  expect(decl, `${selector} declares no ${prop}`).not.toBeNull();
  return (decl as RegExpExecArray)[2].trim();
}

const background = (selector: string) => declared(selector, 'background');

/** A custom property's value inside a block, e.g. `--muted` in `html.kw-dark`. */
const token = (block: string, name: string) => declared(block, name);

/** Every tone a bar segment can carry, with the one that means "this kind has nothing" last. */
const POPULATED = ['ok', 'warn', 'err', 'idle'];

describe('the overview bar paints every tone in its own colour', () => {
  it('gives each populated tone a fill of its own', () => {
    const fills = POPULATED.map((t) => background(`.ov-seg.tone-${t}`));
    expect(new Set(fills).size).toBe(POPULATED.length);
  });

  it('keeps a populated tone off the empty fill and off the track', () => {
    const empty = background('.ov-seg.tone-empty');
    const track = background('.ov-bar');
    for (const tone of POPULATED) {
      expect(background(`.ov-seg.tone-${tone}`), `tone-${tone} paints as the empty bar`).not.toBe(empty);
      expect(background(`.ov-seg.tone-${tone}`), `tone-${tone} paints as the bar's track`).not.toBe(track);
    }
  });

  it('resolves idle and empty to different colours in BOTH themes', () => {
    // The token NAMES are read back out of the rules above rather than written here again, so
    // this keeps checking the right two after someone changes which token a tone uses.
    const name = (selector: string) => /^var\((--[a-z-]+)\)$/.exec(background(selector))?.[1] ?? '';
    const idle = name('.ov-seg.tone-idle');
    const empty = name('.ov-seg.tone-empty');
    expect(idle).not.toBe('');
    expect(empty).not.toBe('');
    // The values as WRITTEN, not as computed — this is the text of the stylesheet. Enough,
    // because a palette that gave two names one literal is exactly what this catches.
    for (const theme of [':root', 'html.kw-dark']) {
      expect(token(theme, idle), `${theme}: ${idle} equals ${empty}`).not.toBe(token(theme, empty));
    }
  });

  it('names tokens rather than literals, so both themes are covered by construction', () => {
    for (const tone of [...POPULATED, 'empty']) {
      expect(background(`.ov-seg.tone-${tone}`)).toMatch(/^var\(--[a-z-]+\)$/);
    }
  });
});

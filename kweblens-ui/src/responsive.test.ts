import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

import { PANE_CONTAINER, PANE_WIDE_PX, paneWidth } from './responsive';

// The stylesheet as TEXT: this test reads the rules, it does not render them, so it needs no
// DOM and no browser. Read off disk rather than imported — Vitest does not process CSS, so
// even a `?raw` import of a .css file comes back empty. The path is resolved from the Vitest
// root (this package) because `import.meta.url` is not a file: URL under the jsdom runner.
// Comments stripped first — they talk ABOUT `@container` and `@media`, and a rule that only
// exists in prose is not a rule.
const css = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

// What these pin: the breakpoint stays ONE number in ONE place. The mechanism is split
// across two files by necessity — a container query's width cannot be a CSS variable, so the
// literal has to be written into `styles.css` — and the whole point of #231 is that surfaces
// agree about what "wide" means. So the stylesheet is parsed here and checked against
// `responsive.ts`, in a plain string test that needs no DOM and no browser.

/** Every `@container` prelude in the stylesheet, e.g. `kw-pane (min-width: 900px)`. */
const preludes = (): string[] => [...css.matchAll(/@container([^{]*)\{/g)].map((m) => m[1].trim());

describe('paneWidth', () => {
  it('names the two widths on either side of the boundary', () => {
    expect(paneWidth(0)).toBe('narrow');
    expect(paneWidth(520)).toBe('narrow'); // the drawer's default width
    expect(paneWidth(PANE_WIDE_PX - 1)).toBe('narrow');
    expect(paneWidth(PANE_WIDE_PX)).toBe('wide');
    expect(paneWidth(1565)).toBe('wide'); // the expanded drawer measured in #230
  });
});

describe('the stylesheet agrees with responsive.ts', () => {
  it('declares the pane container', () => {
    expect(css).toMatch(/\.kw-pane\s*\{[^}]*container-type:\s*inline-size/);
    expect(css).toMatch(new RegExp(`\\.kw-pane\\s*\\{[^}]*container-name:\\s*${PANE_CONTAINER}`));
  });

  it('has container queries, and every one of them queries the named pane', () => {
    expect(preludes().length).toBeGreaterThan(0);
    for (const prelude of preludes()) {
      expect(prelude.startsWith(`${PANE_CONTAINER} `)).toBe(true);
    }
  });

  it('uses only the one named breakpoint, so nobody can invent a second', () => {
    const widths = [...css.matchAll(/@container[^{]*\(min-width:\s*(\d+)px\)/g)].map((m) => Number(m[1]));
    expect(widths.length).toBeGreaterThan(0);
    for (const w of widths) {
      expect(w).toBe(PANE_WIDE_PX);
    }
  });

  it('expresses narrow as the negation of wide rather than a second number', () => {
    // `max-width` would mean a second literal (899.98px) drifting alongside the first, and
    // a gap or an overlap between the two names when someone rounds it.
    for (const prelude of preludes()) {
      expect(prelude).not.toContain('max-width');
    }
  });

  it('lays nothing out on the VIEWPORT width', () => {
    // #230's finding: the drawer is 520px or 1605px at the same viewport width, so a
    // viewport width query cannot see the space a pane has. This fails the build if one
    // appears, rather than leaving two mechanisms to disagree quietly. Media queries that
    // ask about something other than width (print, prefers-reduced-motion, forced-colors)
    // are a different question and stay allowed.
    const widthMedia = [...css.matchAll(/@media([^{]*)\{/g)]
      .map((m) => m[1])
      .filter((q) => /\b(min|max)-width\b|\bwidth\s*[<>]/.test(q));
    expect(widthMedia).toEqual([]);
  });
});

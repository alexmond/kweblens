import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

import { dataTableOverrides } from './tableTheme';

// ---- The two table implementations paint from ONE set of tokens (#478) ----
//
// A category overview renders a hand-written `.mini` and an `NDataTable` one above the other,
// and they did not look like the same product because one was painted by `styles.css` and the
// other by naive's theme. The fix is a shared `--table-*` token set; what has to stay true is
// that BOTH sides still read from it, which is a property neither a screenshot nor a contrast
// run can hold on to.
//
// What is NOT asserted here is any colour value, and no rule is asserted absent — #403, #447
// and #471 each declined to grow that pattern, and a stylesheet test that fails when a
// declaration is reworded is a test people delete. The assertions are: the override names only
// shared tokens, every token it names is declared and follows the theme, and the roles the two
// tables genuinely share are used by both.
const css = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

/** One block's declarations, e.g. everything `:root` sets. All matches: `:root` appears twice. */
const blockBodies = (block: string): string[] =>
  [...css.matchAll(new RegExp(`${block}\\s*\\{([^}]*)\\}`, 'g'))].map((m) => m[1]);

/** What some block declares a token as, or null if none does. */
const declaration = (block: string, token: string): string | null => {
  for (const body of blockBodies(block)) {
    const decl = new RegExp(`${token}:([^;]*);`).exec(body);
    if (decl !== null) {
      return decl[1].trim();
    }
  }
  return null;
};

/**
 * Whether a token's value follows the theme — the same rule `selectedRow.test.ts` applies, and
 * it matters more here: these tokens are handed to a component library, so a light-theme
 * literal among them would be painted on every dark table in the app at once. Two shapes pass:
 * declared in both palettes, or declared once as a construction over tokens that are.
 */
const followsTheme = (token: string, seen: Set<string> = new Set()): boolean => {
  if (seen.has(token)) {
    return false; // a cycle is not an answer; without this the test hangs rather than fails
  }
  seen.add(token);
  const light = declaration(':root', token);
  if (light === null) {
    return false;
  }
  if (declaration('html\\.kw-dark', token) !== null) {
    return true;
  }
  return [...light.matchAll(/var\((--[\w-]+)\)/g)].some((m) => followsTheme(m[1], seen));
};

/** Every `--table-*` token any `.mini` rule paints from. */
const miniTokens = (): Set<string> => {
  const used = new Set<string>();
  for (const chunk of css.split('}')) {
    const parts = chunk.split('{');
    if (parts.length < 2 || !/\.mini(?![\w-])/.test(parts.at(-2) as string)) {
      continue;
    }
    for (const t of (parts.at(-1) as string).matchAll(/var\((--table-[\w-]+)\)/g)) {
      used.add(t[1]);
    }
  }
  return used;
};

const overrideTokens = [
  ...new Set(Object.values(dataTableOverrides).flatMap((v) => [...v.matchAll(/var\((--[\w-]+)\)/g)].map((m) => m[1]))),
];

describe("naive's data table", () => {
  it('paints from shared table tokens and nothing else', () => {
    expect(overrideTokens.length).toBeGreaterThan(0);
    for (const token of overrideTokens) {
      expect(token, `${token} is not one of the shared --table-* tokens`).toMatch(/^--table-/);
    }
    // A literal beside the tokens is the same defect wearing the tokens' clothes: it would be
    // one theme's colour on both themes' tables. `thFontWeight` is the only non-`var()` value
    // and it is not a colour.
    for (const [key, value] of Object.entries(dataTableOverrides)) {
      if (value.startsWith('var(')) {
        continue;
      }
      expect(value, `${key} is a colour literal`).not.toMatch(/#[0-9a-fA-F]{3}|\brgba?\(|\bhsla?\(|color-mix/);
    }
  });

  it('names tokens that exist and follow the theme', () => {
    for (const token of overrideTokens) {
      expect(declaration(':root', token), `${token} is not declared in styles.css`).not.toBeNull();
      expect(followsTheme(token), `${token} does not follow the theme`).toBe(true);
    }
  });

  it('shares the roles the two tables both paint with `.mini`', () => {
    // The whole point of #478 in one assertion: a token used by only one of the two tables is
    // how the page ended up with two table looks. These four are the roles both shapes have —
    // header ground, header text, row ground, and the line between rows. Hover and the sorted
    // column are naive's alone and are not in this set, because `.mini` has neither.
    const shared = ['--table-head-bg', '--table-head-fg', '--table-row-bg', '--table-line'];
    const mini = miniTokens();
    for (const token of shared) {
      expect(mini.has(token), `no .mini rule paints from ${token}`).toBe(true);
      expect(overrideTokens.includes(token), `the DataTable override does not paint from ${token}`).toBe(true);
    }
  });

  it('reaches a table inside a drawer or a modal, not only one on a page', () => {
    // Naive picks `*Modal` / `*Popover` colours when the table is rendered inside one, and its
    // DRAWER counts as modal: measured before the fix, the drawer's Events tab painted
    // rgb(44, 44, 50) while the `.mini` tables on the tab beside it painted the panel tokens.
    // Overriding only the base names fixes the overview and leaves the drawer as reported.
    for (const suffix of ['', 'Modal', 'Popover']) {
      expect(dataTableOverrides[`tdColor${suffix}`], `tdColor${suffix} is unset`).toBe('var(--table-row-bg)');
      expect(dataTableOverrides[`thColor${suffix}`], `thColor${suffix} is unset`).toBe('var(--table-head-bg)');
    }
  });
});

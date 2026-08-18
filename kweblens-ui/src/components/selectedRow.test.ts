import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

// ---- The open drawer's row keeps a mark on the list (#455) ----
//
// `ResourceTable.vue` puts a class on the row through `NDataTable`'s `rowProps` when the row is
// the one the drawer is about. For a long time the only rule that named it was
// `.grid tr.row-active td` — `.grid` is the PRE-NAIVE table, so once the table became
// `NDataTable`'s the class landed on an `.n-data-table-tr` with no `.grid` anywhere above it and
// the rule stopped matching. Measured on a running instance with a row selected: `.row-active`
// is 1 element, `.grid` is 0 in the same sample. Deleting the rule therefore changed nothing on
// screen (#451) — and what that meant is that the selected row had NO highlight of its own at
// all, so with the drawer open nothing on the list said which row it was about.
//
// A "does styles.css mention row-active" assertion would have passed throughout that period,
// because the dead rule mentioned it. So what is pinned here is REACHABILITY: the class the
// component emits, and the fact that every other class in the selectors that style it is one
// naive really puts above the row. That is the assertion the historical defect fails.
const css = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
const table = readFileSync(resolve(process.cwd(), 'src/components/ResourceTable.vue'), 'utf8');

/**
 * The class name `rowProps` actually emits, read out of the component rather than written here
 * twice — a constant duplicated in a test is a constant that stops describing the component the
 * first time somebody renames it, which is a quieter version of the same defect.
 */
const rowActive = (): string => {
  const emitted = /class:\s*objKey\(row\) === props\.selectedKey \? '([\w-]+)'/.exec(table);
  expect(emitted, 'ResourceTable.vue no longer marks the selected row with a class').not.toBeNull();
  return (emitted as RegExpExecArray)[1];
};

/**
 * Every declaration block whose selector names that class, boundary-aware so a longer name
 * that merely starts with it is not counted. Split rather than matched with one regex over the
 * whole file, for the reason `kvList.test.ts` gives: `[^{}]*\{[^{}]*\}` backtracks
 * super-linearly and eslint refuses it. `at(-2)` is the selector even when the rule is nested
 * inside an `@container` prelude, whose chunk holds two `{`.
 */
const rulesNaming = (cls: string): { selector: string; body: string }[] => {
  // Anchored on the `.` that starts a class token and closed on a non-name character, so
  // neither `.row-activeness` nor `.xrow-active` counts as this class — the boundary trap that
  // made `.tall` match inside "install" in an earlier sweep.
  const named = new RegExp(`\\.${cls}(?![\\w-])`);
  return css
    .split('}')
    .map((chunk) => chunk.split('{'))
    .filter((parts) => parts.length >= 2 && named.test(parts.at(-2) as string))
    .map((parts) => ({ selector: (parts.at(-2) as string).trim(), body: parts.at(-1) as string }));
};

/** Every class token in a selector list, e.g. `.n-data-table-tr.row-active td` -> two. */
const classes = (selector: string): string[] => selector.match(/\.[A-Za-z_][\w-]*/g) ?? [];

/** One block's declarations, e.g. everything `:root` sets. First match: the palette block. */
const blockBody = (block: string): string => {
  const body = new RegExp(`${block}\\s*\\{([^}]*)\\}`).exec(css);
  expect(body, `no ${block} block in styles.css`).not.toBeNull();
  return (body as RegExpExecArray)[1];
};

/** What one block declares a token as, or null if it does not declare it. */
const declaration = (block: string, token: string): string | null => {
  const decl = new RegExp(`${token}:([^;]*);`).exec(blockBody(block));
  return decl === null ? null : decl[1].trim();
};

/**
 * Whether a token's value follows the theme. Two shapes count, and the second is the reason
 * this is a function rather than "declared in both blocks": `--ok-on-tint` is declared ONCE as
 * a construction over tokens that are themselves redefined per theme, deliberately, because a
 * dark override there would be a second copy of the construction to keep in step. What is not
 * allowed is the third shape — a literal in `:root` and nothing in `html.kw-dark`, which is a
 * light-theme colour painted on a dark table.
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

describe('the selected row', () => {
  it('is marked by the class the table emits, and something paints it', () => {
    const rules = rulesNaming(rowActive());
    expect(rules.length).toBeGreaterThan(0);
    expect(rules.some((r) => /background/.test(r.body))).toBe(true);
  });

  it("is styled through a selector that can REACH naive's row", () => {
    // The whole of #455 in one assertion. `.grid tr.row-active td` satisfies "the stylesheet
    // mentions the class" and reaches nothing, because the rendered ancestry is
    // `.n-data-table > … > .n-data-table-tr.row-active > .n-data-table-td` and carries no
    // `.grid`. So every class in these selectors is either the row's own or one naive writes.
    const cls = rowActive();
    for (const { selector } of rulesNaming(cls)) {
      for (const token of classes(selector)) {
        if (token === `.${cls}`) {
          continue;
        }
        expect(token, `${selector} qualifies the selected row with ${token}, which naive never renders`).toMatch(
          /^\.n-data-table/,
        );
      }
    }
  });

  it('takes every colour it paints from a token that follows the theme', () => {
    // The dead rule's `rgba(10, 122, 194, 0.1)` was chosen when there was one theme. A literal
    // here is a light-theme colour on a dark table, which is the omission `--subtle-bg` exists
    // to make impossible.
    const rules = rulesNaming(rowActive());
    const used = rules.flatMap((r) => [...r.body.matchAll(/var\((--[\w-]+)\)/g)].map((m) => m[1]));
    expect(used.length).toBeGreaterThan(0);
    for (const token of used) {
      expect(followsTheme(token), `${token} does not follow the theme`).toBe(true);
    }
    // And nothing paints from anything else: a colour literal beside the tokens is the same
    // defect wearing the tokens' clothes.
    for (const { selector, body } of rules) {
      expect(body, `${selector} paints a literal colour`).not.toMatch(/#[0-9a-fA-F]{3}|\brgba?\(|\bhsla?\(/);
    }
  });
});

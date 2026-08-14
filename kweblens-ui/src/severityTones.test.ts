import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

// The diagnosis badge, checked as TEXT — same trick and same reason as `barTones.test.ts` and
// `responsive.test.ts`: the rules live in `styles.css`, Vitest does not process CSS, so the
// stylesheet is read off disk and parsed rather than rendered. Comments are stripped first; a
// rule that only exists in prose is not a rule.
//
// What this pins is #381. `DiagnosisPanel` renders `dx-sev dx-sev-{severity}` and
// `diagnosis.ts` normalises every severity the server can send to one of three names — so a
// missing `.dx-sev-info` rule is not a fallback, it is a THIRD of the vocabulary rendered as
// the neutral base. It shipped that way, and the findings that carry `info` include the two
// that say the audit did not see everything ("Further container privileges not listed", "RBAC
// grants could not be read"): a truncation notice and a read failure painted like ordinary
// content, with nothing on screen to say the list is partial.
//
// Two claims, because either one alone can be true while a badge is wrong: the severities must
// name DIFFERENT TOKENS from each other and from the neutral base, and those tokens must
// resolve to different COLOURS in each theme. Two names are not two colours — a palette edit
// that gave two of them one literal would re-collide them in one theme only, which is exactly
// the shape of defect this project keeps shipping.
const css = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

/**
 * Every rule in the sheet as `{ selectors, body }`.
 *
 * Deliberately a second copy of `barTones.test.ts`'s parser rather than an import: it is four
 * lines of text handling that belong to the check, and a shared module between two test files
 * would be a dependency in the direction that makes either one harder to read. The reasoning
 * behind the shape (split selector LISTS; split on `}` rather than a backtracking regex, which
 * eslint fails the build on) is written out there.
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

/** The declared value of `prop` on `selector`, e.g. `color` on `.dx-sev-info`. */
function declared(selector: string, prop: string): string {
  const rule = rules.find((r) => r.selectors.includes(selector));
  expect(rule, `no rule for ${selector}`).toBeDefined();
  const decl = new RegExp(`(^|[;{\\s])${prop}:\\s*([^;]+);`).exec((rule as (typeof rules)[number]).body);
  expect(decl, `${selector} declares no ${prop}`).not.toBeNull();
  return (decl as RegExpExecArray)[2].trim();
}

/** A custom property's value inside a block, e.g. `--warn-fg` in `html.kw-dark`. */
const token = (block: string, name: string) => declared(block, name);

/** The token a declaration names, e.g. `--warn-fg` from `var(--warn-fg)`. */
function tokenOf(selector: string, prop: string): string {
  const value = declared(selector, prop);
  const name = /^var\((--[a-z-]+)\)$/.exec(value)?.[1] ?? '';
  expect(name, `${selector}'s ${prop} is the literal "${value}", so it covers one theme only`).not.toBe('');
  return name;
}

/** The token named inside a shorthand, e.g. `--danger-fg` from `3px solid var(--danger-fg)`. */
function tokenInside(selector: string, prop: string): string {
  const value = declared(selector, prop);
  const name = /var\((--[a-z-]+)\)/.exec(value)?.[1] ?? '';
  expect(name, `${selector}'s ${prop} is "${value}", which names no token`).not.toBe('');
  return name;
}

/** Every severity `diagnosis.ts` can produce. Unknown values normalise to `info`, not to none. */
const SEVERITIES = ['critical', 'warning', 'info'];

/** Both palettes. A colour defined in one of them only is a colour missing from the other. */
const THEMES = [':root', 'html.kw-dark'];

/** What the badge paints with, and what the neutral base uses for the same property. */
const PROPS = [
  { prop: 'color', neutral: '--text' },
  { prop: 'border-color', neutral: '--border' },
];

describe('a diagnosis badge paints its own severity', () => {
  for (const { prop, neutral } of PROPS) {
    it(`gives each severity its own ${prop} token`, () => {
      const names = SEVERITIES.map((s) => tokenOf(`.dx-sev-${s}`, prop));
      expect(new Set(names).size, `${prop} tokens: ${names.join(', ')}`).toBe(SEVERITIES.length);
    });

    it(`keeps every severity's ${prop} off the unstyled badge's ${neutral}, in BOTH themes`, () => {
      // Compared as LITERALS, not names: a severity token that happened to be defined as the
      // same colour as the neutral would render exactly like a badge with no severity at all,
      // which is the whole defect — `info` had no rule and inherited this neutral.
      for (const severity of SEVERITIES) {
        const name = tokenOf(`.dx-sev-${severity}`, prop);
        for (const theme of THEMES) {
          expect(token(theme, name), `${theme}: ${severity}'s ${name} equals the neutral ${neutral}`).not.toBe(
            token(theme, neutral),
          );
        }
      }
    });

    it(`resolves the three ${prop} tokens to three colours in EACH theme`, () => {
      // The token NAMES are read back out of the rules above rather than written here again,
      // so this keeps checking the right three after someone changes which token a severity
      // uses. The values are as WRITTEN, which is enough: what this catches is a palette that
      // gives two names one literal in one theme.
      const names = SEVERITIES.map((s) => tokenOf(`.dx-sev-${s}`, prop));
      for (const theme of THEMES) {
        const literals = names.map((n) => token(theme, n));
        expect(new Set(literals).size, `${theme}: ${names.join('/')} are ${literals.join(', ')}`).toBe(names.length);
      }
    });
  }

  it('stripes the finding card in the same severity, for every severity', () => {
    // The badge is 10px of text; the card's left edge is what makes a finding scannable in a
    // list. `info` had neither, so an incomplete-audit notice was a plain card with a plain
    // badge — indistinguishable from a finding of no particular grade.
    const stripes = SEVERITIES.map((s) => tokenInside(`.dx-item.dx-${s}`, 'border-left'));
    expect(new Set(stripes).size, `stripes: ${stripes.join(', ')}`).toBe(SEVERITIES.length);
  });
});

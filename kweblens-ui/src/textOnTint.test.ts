import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

// One rule, one surface: text that sits on a state's own TINT takes that state's `--*-on-tint`,
// never its `--*-fg` (GH#393, GH#484, GH#501).
//
// A state's `fg` is designed and measured against the PANEL. On its own tint it is a different
// measurement, and this project has now got that wrong on eleven surfaces in three rounds: the
// nav, the command palette, the drawer's Naive tag and the status pill (#393), then the
// diagnostics note, the drawer's refused relation and the pod file browser's notice and tag
// (#484), then the three danger-tone ones this list used to exempt (#501). Every one of them
// passed AA — the warn pairing measures 4.51:1 in light against a 4.5 floor — so nothing failed
// and nothing was noticed, and the same hundredth of margin then had to be defended every time
// the tint alpha or the panel behind it moved (#482 left the light tint alone for exactly that
// reason).
//
// Why a unit test rather than the browser check. `contrast-check.mjs` measures what is ON
// SCREEN, and three of #484's four surfaces could not be brought on screen at all without new
// stub verbs: an admin kubeconfig is never refused a join, and the pod file browser is off by
// default with no container to attach to on the simulator. A surface no scene can reach reports
// `not present`, which is a failed measurement that reads as a pass. This check needs no
// browser, no cluster and no scene — it reads the declaration.
//
// What it cannot see, stated so it is not mistaken for cover: a foreground and a background set
// in DIFFERENT rules (an inherited colour over a tinted ancestor) is invisible here, because the
// pairing only exists once the two rules meet on an element. That case is the browser check's,
// and it is why both exist.

// Resolved against THIS FILE, not `process.cwd()`. The two agree only while the runner is
// started from `kweblens-ui/`; a run from the repo root, or from a workspace root, reads a path
// that does not exist and the failure names a missing file rather than the sheet.
// `classesAreEmitted.test.ts` already does it this way.
const here = dirname(fileURLToPath(import.meta.url));
const css = readFileSync(resolve(here, 'styles.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

/**
 * Every rule in the sheet as `{ selectors, body }`.
 *
 * The same four lines `barTones.test.ts`, `severityTones.test.ts` and `toneTints.test.ts` each
 * carry, for the reason the first of them gives: a shared module between four test files would
 * be a dependency in the direction that makes each one harder to read.
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
        .map((s) => s.trim())
        .filter(Boolean),
      body: chunk.slice(open + 1),
    },
  ];
});

/**
 * Rules that still carry the pairing.
 *
 * EMPTY (#501). It held the three DANGER-tone surfaces — `.error`, `.warn-badge.err` and
 * `.rel-error` — on the grounds that the danger tone had the headroom warn did not: the
 * arithmetic over `--panel` gives 5.62:1 light / 5.75:1 dark, a full point clear of AA against
 * warn's 4.51. Two things came out of measuring them instead of computing them.
 *
 * The dark number was wrong. All three sit on naive's modal or drawer body, not on `--panel`,
 * and RENDERED they read 5.62 light / **5.16** dark — 0.66 over the floor, not 1.25. And the
 * move is not merely cosmetic: `--danger-on-tint` measures 8.14 / 6.02 on the same grounds, so
 * every one of the six samples gained. That is the answer to "uniform, or better" — better.
 *
 * An entry that stops matching a rule is itself a failure (below): an exemption list that has
 * quietly stopped exempting anything is a rule nobody is applying, which is how a check starts
 * covering less than it claims. The list being empty is what makes this check a rule about the
 * whole sheet rather than about most of it.
 */
/* eslint-disable sonarjs/no-empty-collection -- the list being empty is the ticket's outcome,
   not a leftover. Both assertions below must go on reading it: the first is what an entry would
   suppress, the second is what stops an entry outliving its rule, and deleting the mechanism
   because it currently exempts nothing is how the next exemption gets written with no guard on
   it. sonarjs is right that these reads cannot fail today; that is the state being defended. */
const KNOWN: string[] = [];

/** The state named by a `color:` declaration's token, e.g. `warn` from `var(--warn-fg)`. */
const foregroundState = (body: string) => /(?:^|[;{\s])color:\s*var\(--([a-z-]+)-fg\)/.exec(body)?.[1];

/** The state named by a `background` / `background-color` declaration's tint token. */
const tintState = (body: string) => /background(?:-color)?:\s*var\(--([a-z-]+)-tint\)/.exec(body)?.[1];

/** Selectors of every rule that paints a state's own `fg` on that same state's `tint`. */
const paired = rules
  .filter((r) => {
    const fg = foregroundState(r.body);
    return fg !== undefined && fg === tintState(r.body);
  })
  .map((r) => r.selectors.join(', '));

describe('text painted on a state tint takes the derived on-tint foreground', () => {
  it('has no rule pairing a state fg with its own tint, beyond the ones written down', () => {
    // Named rather than counted: a number tells the next reader that something is wrong and
    // not which rule, and this check's whole value is that the answer is one selector away.
    expect(
      paired.filter((s) => !KNOWN.includes(s)),
      'these paint --<state>-fg on --<state>-tint; use --<state>-on-tint',
    ).toEqual([]);
  });

  it('exempts only rules that are still there', () => {
    expect(
      KNOWN.filter((s) => !paired.includes(s)),
      'exempted but no longer paired — delete the entry',
    ).toEqual([]);
  });

  it('is a check with teeth — the pairing #484 removed is still recognised', () => {
    // A check written after the fix proves only that it is quiet. This rebuilds one of the four
    // rules exactly as it shipped and asserts both halves fire: the fg state, the tint state,
    // and their equality.
    const shipped =
      '\n  color: var(--warn-fg);\n  background: var(--warn-tint);\n  border: 1px solid var(--warn-line);\n';
    expect(foregroundState(shipped)).toBe('warn');
    expect(tintState(shipped)).toBe('warn');
    // And the shapes it must NOT fire on: a tint under neutral text, and a state's fg on the
    // panel — both correct, both everywhere in this sheet.
    expect(foregroundState('\n  color: var(--text);\n  background: var(--warn-tint);\n')).toBeUndefined();
    expect(tintState('\n  color: var(--warn-fg);\n  border-color: var(--warn-line);\n')).toBeUndefined();
    // `border-color` is not a background: a rule may name a state's line and its fg together.
    expect(tintState('\n  background: var(--panel);\n  border-color: var(--warn-tint);\n')).toBeUndefined();
  });
});

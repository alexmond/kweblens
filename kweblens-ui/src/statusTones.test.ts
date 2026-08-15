import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

import type { StatusTone } from './columns';
import { badgeTone } from './columns';
import { TONE_VARS } from './statusTones';

// What a status pill can paint, pinned against what a caller can ask for (GH#393).
//
// The pill's colour is delivered INLINE (`NTag`'s `:color`), so no stylesheet decides it and
// `contrast-check.mjs` cannot reach it by rule. That leaves two things a browser check cannot
// answer on its own, and both had already gone wrong:
//
//   * A tone in the map that no caller can produce is a colour nothing can render and nothing
//     can measure. `ok` was one for as long as the map existed: every call site routes through
//     `badgeTone`, which maps `ok` to `''` (#240 — a pill marks an exception), so the one tone
//     that had never been contrast-measured was the one the app cannot paint. A permanently
//     unmeasurable selector reads as a pass, which is exactly what `contrast-check`'s own
//     header calls the worst outcome available.
//   * A foreground re-picked per tone drifts from the surface it sits on. The pill carried the
//     state's `fg`, which is designed to read on the PANEL, and on its own tint the warn tone
//     measured 4.51:1 in light against a 4.5 floor.
//
// So: the entries are exactly the tones `badgeTone` hands over, and each entry's foreground is
// the `on-tint` token of the SAME state as its background — never a literal, never another
// state's token, never a second construction of the mix.
const css = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8');

/** Every tone `badgeTone` can hand to a pill. `''` is "no pill", so it is not one of them. */
const badged = new Set(
  (['ok', 'warn', 'err', ''] as StatusTone[]).map((t) => badgeTone(t)).filter((t): t is StatusTone => t !== ''),
);

describe('a status pill paints the tones a caller can actually ask for', () => {
  it('has an entry for every tone badgeTone produces, and none it does not', () => {
    expect(new Set(Object.keys(TONE_VARS)), `badgeTone can produce: ${[...badged].join(', ')}`).toEqual(badged);
  });

  it('takes both colours from tokens, so neither covers one theme only', () => {
    for (const [tone, vars] of Object.entries(TONE_VARS)) {
      expect(vars?.color, `${tone}'s background`).toMatch(/^var\(--[a-z-]+-tint\)$/);
      expect(vars?.textColor, `${tone}'s foreground`).toMatch(/^var\(--[a-z-]+-on-tint\)$/);
    }
  });

  it('pairs each foreground with the on-tint token of its OWN state', () => {
    // A pill wearing another state's foreground is readable and wrong — the failure mode a
    // ratio cannot see, because both colours are fine and only the pairing is a lie.
    for (const [tone, vars] of Object.entries(TONE_VARS)) {
      const state = /^var\(--([a-z-]+)-tint\)$/.exec(vars?.color ?? '')?.[1];
      expect(vars?.textColor, `${tone} fills with --${state}-tint`).toBe(`var(--${state}-on-tint)`);
    }
  });

  it('names on-tint tokens that styles.css defines, derived from that state and nothing else', () => {
    // `var(--warn-on-tint)` with no such token paints the INHERITED colour: a pill that looks
    // merely unstyled rather than broken, in a value delivered inline where no rule can be
    // found to explain it.
    for (const [tone, vars] of Object.entries(TONE_VARS)) {
      const name = /^var\((--[a-z-]+-on-tint)\)$/.exec(vars?.textColor ?? '')?.[1] ?? '';
      // Named before it is looked up: without this the search below runs for `--` and reports
      // "styles.css declares no" about a token nobody wrote, which reads as a missing token
      // rather than a foreground that is not one.
      expect(name, `${tone}'s foreground ${vars?.textColor} names no on-tint token`).not.toBe('');
      const decl = new RegExp(`${name}:\\s*([^;]+);`).exec(css);
      expect(decl, `${tone}: styles.css declares no ${name}`).not.toBeNull();
      const state = name.replace(/^--|-on-tint$/g, '');
      expect((decl as RegExpExecArray)[1], `${name} must derive from --${state}-fg`).toContain(`var(--${state}-fg)`);
    }
  });
});

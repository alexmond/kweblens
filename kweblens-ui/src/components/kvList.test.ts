import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

// ---- The Overview `.kv` list's wrap policy, pinned without a DOM (#326) ----
//
// The sibling of `miniTable.test.ts`, for the same reason and against the same cause: Naive's
// `.n-drawer` sets `word-break: break-word` on the whole drawer, `break-word` is the deprecated
// alias for `overflow-wrap: anywhere`, and `anywhere` counts toward MIN-CONTENT — so a `dt` or
// `dd` that inherits it has a minimum content width of one glyph and shreds its own token. The
// Kind row of a ValidatingWebhookConfiguration rendered `ValidatingWebhookConfigurati` / `on`
// at the drawer's 360px minimum: 203.09px of word in a 190px box.
//
// Nothing overflowed, no line was long and contrast was fine, so only a `ui-measure` run sees
// it — which is why the rules are pinned here. Dropping any one of them brings the symptom back
// silently.
const css = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

/**
 * Every declaration block whose selector mentions `.kv`, minus the unrelated `.kv-editor` /
 * `.kv-row` ones. Split rather than matched with one regex over the whole file: a
 * `[^{}]*\{[^{}]*\}` pattern backtracks super-linearly and eslint refuses it. `at(-2)` is what
 * carries a rule nested inside an `@container` prelude, whose chunk holds two `{`.
 */
const kvRules = (): string[] =>
  css
    .split('}')
    .map((chunk) => chunk.split('{'))
    .filter((parts) => parts.length >= 2 && /(^|[\s,>])\.kv(?![\w-])/.test(parts.at(-2) as string))
    .map((parts) => `${(parts.at(-2) as string).trim()} {${parts.at(-1) as string}}`);

describe('the .kv wrap policy', () => {
  it('restores a whole word as the minimum width of BOTH tracks', () => {
    // `dt` as well as `dd`: the label inherits the same drawer rule, and a label column
    // squeezed to a glyph shreds `Container Runtime` exactly as readily as a value.
    expect(css).toMatch(/\.kv dt,\s*\.kv dd\s*\{[^}]*word-break:\s*normal/);
    expect(css).toMatch(/\.kv dt,\s*\.kv dd\s*\{[^}]*overflow-wrap:\s*break-word/);
  });

  it('never lets a .kv cell shrink to a glyph again', () => {
    // `anywhere` and its `word-break: break-word` alias are the two values that lower
    // min-content, which is the whole defect. `break-all` on a mono value is deliberate and
    // checked below, so it is exempt.
    const rules = kvRules();
    expect(rules.length).toBeGreaterThanOrEqual(4);
    for (const rule of rules) {
      expect(rule).not.toMatch(/overflow-wrap:\s*anywhere/);
      expect(rule).not.toMatch(/word-break:\s*break-word/);
    }
  });

  it('keeps break-all for mono values, at a specificity that actually wins', () => {
    // A base64 secret or a `kubernetes.io/...` type is one token with no break opportunity in
    // it; preferring whole words there would let it bid for the width the kind needs. `.mono`
    // alone cannot say so — `.kv dd` (0,1,1) outranks `.mono` (0,1,0) — so it is restated.
    expect(css).toMatch(/\.kv dd\.mono\s*\{[^}]*word-break:\s*break-all/);
  });

  it('lets the label track yield its width to the value', () => {
    // The second half of the fix, and the half a "simplification" would undo: a hard `120px`
    // first track is what left the value 190px for a 203.09px word. `minmax(min-content, 120px)`
    // keeps 120px wherever there is room and yields down to its own longest label when the
    // value is short — the only neighbour a `dd` has to take width from (#318, in grid form).
    expect(css).toMatch(/\.kv\s*\{[^}]*grid-template-columns:\s*minmax\(min-content,\s*120px\)/);
  });

  it('keeps the value track on an AUTO minimum, which is what the word policy sizes', () => {
    // `1fr` is `minmax(auto, 1fr)`, and after the rule above that auto minimum is the longest
    // word. Written as `minmax(0, 1fr)` — which is what the wide layouts below it use, so it
    // reads like an inconsistency worth tidying — the floor becomes zero and the value is free
    // to be squeezed under its own token again.
    const base = css.match(/\.kv\s*\{[^}]*\}/)?.[0] ?? '';
    expect(base).toMatch(/grid-template-columns:[^;]*\s1fr\s*;/);
    expect(base).not.toMatch(/grid-template-columns:[^;]*minmax\(\s*0/);
  });
});

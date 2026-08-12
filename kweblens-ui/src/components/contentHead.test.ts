import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

// ---- Which child of the list header gives up width, pinned without a DOM (#331) ----
//
// The sibling of `kvList.test.ts` and `miniTable.test.ts`, same family, different container.
// `.content-head` is a plain flex row, so every child's automatic minimum size is its
// MIN-CONTENT — the longest WORD, not the whole string. At `narrow` (1024px) the row wants
// 948px in 689px, and the shrink is distributed across every `flex-shrink: 1` item including
// the two that have no second line to give: `.count` was handed 47.17px for 57.16px of
// "3 items" (42px tall) and `.ns-note` 67.75px for 102.06px of "Cluster-scoped" (44px tall).
//
// Nothing overflowed and no word was broken, so only a `ui-measure` run sees it — which is why
// the rules are pinned here rather than left to a screenshot.
const css = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

/**
 * Every declaration block whose selector mentions the given class. Split rather than matched
 * with one regex over the whole file, for the reason `kvList.test.ts` gives: a
 * `[^{}]*\{[^{}]*\}` pattern backtracks super-linearly and eslint refuses it.
 */
const rulesFor = (cls: string): string[] =>
  css
    .split('}')
    .map((chunk) => chunk.split('{'))
    .filter((parts) => parts.length >= 2 && new RegExp(`(^|[\\s,>])\\.${cls}(?![\\w-])`).test(parts.at(-2) as string))
    .map((parts) => `${(parts.at(-2) as string).trim()} {${parts.at(-1) as string}}`);

describe('the .content-head squeeze policy', () => {
  it('takes the fixed-shape chips out of the shrink entirely', () => {
    // `flex: 0 0 auto`, not a weighting: #318 measured that 0.4px of squeeze still costs two
    // glyphs, so nearly-not-shrinking is not a fix.
    expect(css).toMatch(/\.content-head \.count,\s*\.content-head \.ns-note\s*\{[^}]*flex:\s*0 0 auto/);
  });

  it('leaves the title shrinkable, because it is what absorbs the squeeze', () => {
    // Something has to give. The title yields 58.09px at the measured scene and wraps at word
    // boundaries; pinning it too would push the row straight into `.content`'s scroller.
    // The length assertion is what stops this passing vacuously if the rule is ever renamed.
    const h1Rules = rulesFor('content-head').filter((r) => /h1/.test(r));
    expect(h1Rules.length).toBeGreaterThanOrEqual(1);
    for (const rule of h1Rules) {
      expect(rule).not.toMatch(/flex-shrink:\s*0/);
      expect(rule).not.toMatch(/flex:\s*(none|0 0)/);
      // `min-width: 0` would buy width by re-enabling exactly the defect of #257/#326 one
      // element to the left: a single-token CRD kind sliced mid-word.
      expect(rule).not.toMatch(/min-width:\s*0/);
    }
  });

  it('does not hold the chips on one line with nowrap', () => {
    // `nowrap` would also work — it raises min-content to the whole string — but it switches
    // off `ui-measure`'s `words` check on the element it is applied to, which is how #318's
    // regression hid. A fix that silences the instrument watching it is the wrong fix.
    const chipRules = [...rulesFor('count'), ...rulesFor('ns-note')];
    // Two base rules at minimum, so a renamed class cannot turn this into a green no-op.
    expect(chipRules.length).toBeGreaterThanOrEqual(2);
    for (const rule of chipRules) {
      expect(rule).not.toMatch(/white-space:\s*nowrap/);
    }
  });
});

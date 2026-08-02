#!/usr/bin/env node
//
// Measure rendered geometry against a running kweblens, so layout claims are numbers.
//
// The companion to contrast-check.mjs: that one settles colour, this one settles size,
// overflow and line length. Both exist for the same reason — every layout judgement made
// by eye in this project has been wrong at least once, in both directions:
//
//   - "the drawer is missing its close button"   -> a flex child with min-width:auto had
//     grown the header 554px inside a 520px panel. Invisible; exact once measured.
//   - "this table behaves differently"           -> it did not. It was the widest table
//     and every table had the same fixed min-width. Measuring all of them showed the
//     difference was degree, not kind, which changed the fix.
//   - "the prose looks fine"                     -> 338 characters a line at 1900px.
//
// What it reports per selector:
//   box       x/y/width/height of the first match
//   overflow  width vs the nearest scrollable/clipping ancestor, and vs the viewport
//   measure   approximate characters per line, for text-bearing elements
//   count     how many matched (a selector matching 0 is reported, never silently passed)
//
// Exit code is 1 if anything overflows its container or the viewport, so it can gate a
// change the way contrast-check does.
//
// Usage (needs a running instance — scripts/dev-run.sh, never `java -jar`):
//   export NODE_PATH="${NODE_PATH:-$HOME/.local/lib/playwright/node_modules}"
//   node scripts/ui-measure.mjs '.drawer-title' '.dx-detail'
//   node scripts/ui-measure.mjs --view wide --leaf Pods '.n-data-table'
//   PREPARE='click:.n-data-table-tbody tr' node scripts/ui-measure.mjs --leaf Pods '.n-drawer'
//
// Options: --view <name> (narrow|normal|wide, default normal), --theme <name>,
//          --leaf <label>, --path <path>. Env: PORT, BASE_URL, PREPARE.
//
// A selector reported as `absent` is a FAILED measurement, not a pass — the same trap
// contrast-check's "not present" row exists for. Bring the surface on screen with
// --leaf/--path/PREPARE and measure again.

import { BASE_URL, open, openLeaf, runPrepare, setTheme } from './lib/kw-playwright.mjs';

const argv = process.argv.slice(2);
const flag = (name, fallback = null) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : fallback;
};
const selectors = argv.filter((a, i) => !a.startsWith('--') && !argv[i - 1]?.startsWith('--'));

if (!selectors.length) {
  console.error('usage: node scripts/ui-measure.mjs [--view v] [--theme t] [--leaf L] <selector>...');
  process.exit(2);
}

const view = flag('view') ?? 'normal';
const theme = flag('theme');
const leaf = flag('leaf');
const path = flag('path');

const { browser, page } = await open({ view, url: path ? new URL(path, BASE_URL).href : BASE_URL });
let failed = false;
try {
  if (theme) await setTheme(page, theme);
  if (leaf) await openLeaf(page, leaf);
  await runPrepare(page, process.env.PREPARE);
  await page.waitForTimeout(400);

  for (const sel of selectors) {
    const m = await page
      .$$eval(sel, (els) => {
        if (!els.length) return null;
        const el = els[0];
        const r = el.getBoundingClientRect();

        // The ancestor that would actually clip or scroll this element. Walking to the
        // first ancestor with a non-visible overflow is the honest comparison: comparing
        // against the immediate parent reports overflow that the layout never shows,
        // and comparing against the viewport misses a panel that clips its own content.
        let clipper = el.parentElement;
        while (clipper && clipper !== document.documentElement) {
          const cs = getComputedStyle(clipper);
          if (/auto|scroll|hidden/.test(cs.overflowX + cs.overflowY)) break;
          clipper = clipper.parentElement;
        }
        const cr = clipper ? clipper.getBoundingClientRect() : null;

        // Characters per line.
        //
        // Only DIRECT text nodes count. `textContent` includes every descendant, so a
        // layout container reports the concatenation of the whole page as if it were one
        // line: the first version of this called `.content-col` a 229-char/line DEFECT
        // when it holds no prose at all. A checker that fails things that are fine gets
        // ignored, which costs more than not having it.
        //
        // And the width comes from RENDERING the string, not from a glyph guess. The
        // first version assumed 0.5em per character; measured against a known 900px of
        // 14px monospace (8.40px per glyph) it read 129 chars where the truth was 107 —
        // 20.6% out, in the direction that invents defects. Laying the actual text out
        // once in the element's own font costs one reflow and is exact for that string.
        const cs = getComputedStyle(el);
        const ownText = [...el.childNodes]
          .filter((n) => n.nodeType === 3)
          .map((n) => n.textContent)
          .join('')
          .trim();
        const charsPerLine = () => {
          if (ownText.length <= 40 || r.width <= 0) return null;
          const probe = document.createElement('span');
          probe.style.cssText = 'position:absolute;visibility:hidden;white-space:pre;left:-9999px;top:0';
          probe.style.fontFamily = cs.fontFamily;
          probe.style.fontSize = cs.fontSize;
          probe.style.fontWeight = cs.fontWeight;
          probe.style.fontStyle = cs.fontStyle;
          probe.style.letterSpacing = cs.letterSpacing;
          probe.textContent = ownText;
          document.body.appendChild(probe);
          const full = probe.getBoundingClientRect().width;
          probe.remove();
          return full > 0 ? Math.round((ownText.length * r.width) / full) : null;
        };

        return {
          count: els.length,
          box: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
          scrollW: el.scrollWidth,
          // Reaching documentElement means nothing between here and the top clips, so
          // the only real bound is the viewport — say that rather than naming <html>.
          clipper: clipper && clipper !== document.documentElement ? clipper.className || clipper.tagName.toLowerCase() : null,
          clipW: cr ? Math.round(cr.width) : null,
          clipRight: cr ? Math.round(cr.right) : null,
          viewportW: window.innerWidth,
          measure: charsPerLine(),
          fontSize: cs.fontSize,
        };
      })
      .catch(() => null);

    if (!m) {
      console.log(`${sel}\n  absent — NOT a pass; bring it on screen and measure again`);
      failed = true;
      continue;
    }

    const overClip = m.clipRight != null && Math.round(m.box.x + m.box.w) > m.clipRight;
    const overView = m.box.x + m.box.w > m.viewportW;
    const selfScroll = m.scrollW > m.box.w + 1;

    console.log(sel);
    console.log(`  count    ${m.count}`);
    console.log(`  box      x=${m.box.x} y=${m.box.y} w=${m.box.w} h=${m.box.h}`);
    if (m.clipper != null && m.clipW != null) {
      console.log(
        `  clipper  .${m.clipper} w=${m.clipW} right=${m.clipRight}` +
          (overClip ? `  <-- OVERFLOWS by ${m.box.x + m.box.w - m.clipRight}px` : ''),
      );
    }
    if (selfScroll) console.log(`  content  scrollWidth=${m.scrollW} (${m.scrollW - m.box.w}px hidden inside it)`);
    if (overView) console.log(`  viewport w=${m.viewportW}  <-- OVERFLOWS by ${m.box.x + m.box.w - m.viewportW}px`);
    if (m.measure != null) {
      const verdict = m.measure > 200 ? ' <-- DEFECT' : m.measure > 90 ? ' <-- uncomfortable' : '';
      console.log(`  measure  ~${m.measure} chars/line at ${m.fontSize}${verdict}`);
      if (m.measure > 200) failed = true;
    }
    if (overClip || overView) failed = true;
  }
} finally {
  await browser.close();
}

console.log(`\nview=${view}${theme ? ` theme=${theme}` : ''} — ${failed ? 'PROBLEMS FOUND' : 'nothing over budget'}`);
process.exit(failed ? 1 : 0);

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
//   words     a word too wide for its own box — i.e. one the browser must break mid-word
//   row       how much of a container's width its own children actually reach
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
// Options: --view <name|px> (narrow|normal|wide, default normal; a bare number is the
//          bottom-end escape hatch — see `viewport` in lib/kw-playwright.mjs), --theme <name>,
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

if (!selectors.length && !argv.includes('--self-test')) {
  console.error('usage: node scripts/ui-measure.mjs [--view v] [--theme t] [--leaf L] <selector>... | --self-test');
  process.exit(2);
}

const view = flag('view') ?? 'normal';
const theme = flag('theme');
const leaf = flag('leaf');
const path = flag('path');

function measureOne(els) {
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

  // ---- Is any word too wide for the box it is in? (#257) ----
  //
  // The defect this catches: the overview's Warnings table rendered its `Reason` header
  // as "Reas / on" and `Age` as "Ag / e" while a sibling column sat mostly empty. It was
  // eyeballed off a screenshot, twice, before anyone measured it — and nothing here
  // would have failed, because the table did not overflow anything and the lines were
  // short. Naive puts `word-break: break-word` on table cells, which makes a column's
  // minimum content width ONE GLYPH, so a squeezed column silently shreds its own label
  // instead of refusing to shrink.
  //
  // Measured, not guessed: the longest UNBREAKABLE run is laid out in the element's own
  // font and compared with its content box. Unbreakable, not "word" — a browser may
  // break after `-` and `/`, so `Pod/kw251-bad-a` is legally three runs and only the
  // longest of them has to fit. Elements that cannot wrap at all (`white-space: nowrap`)
  // are skipped: they overflow or ellipsise instead, which the box/overflow lines above
  // already report.
  //
  // Runs over EVERY match, not just the first — one bad header in four is the case.
  const widestWord = () => {
    const probe = document.createElement('span');
    probe.style.cssText = 'position:absolute;visibility:hidden;white-space:pre;left:-9999px;top:0';
    document.body.appendChild(probe);
    let worst = null;
    for (const e of els) {
      const ecs = getComputedStyle(e);
      if (/nowrap|pre$/.test(ecs.whiteSpace)) continue;
      const text = [...e.childNodes]
        .filter((n) => n.nodeType === 3)
        .map((n) => n.textContent)
        .join(' ')
        .trim();
      if (!text) continue;
      const er = e.getBoundingClientRect();
      const inner =
        er.width - parseFloat(ecs.paddingLeft || 0) - parseFloat(ecs.paddingRight || 0) -
        parseFloat(ecs.borderLeftWidth || 0) - parseFloat(ecs.borderRightWidth || 0);
      if (inner <= 0) continue;
      probe.style.fontFamily = ecs.fontFamily;
      probe.style.fontSize = ecs.fontSize;
      probe.style.fontWeight = ecs.fontWeight;
      probe.style.fontStyle = ecs.fontStyle;
      probe.style.letterSpacing = ecs.letterSpacing;
      probe.style.textTransform = ecs.textTransform;
      for (const run of text.split(/\s+/).flatMap((w) => w.split(/(?<=[-/–—])/))) {
        if (!run) continue;
        probe.textContent = run;
        const w = probe.getBoundingClientRect().width;
        if (w > inner + 0.5 && (!worst || w - inner > worst.over)) {
          worst = { run, w: Math.round(w), inner: Math.round(inner), over: w - inner };
        }
      }
    }
    probe.remove();
    return worst && { run: worst.run, w: worst.w, inner: worst.inner };
  };

  // ---- How much of a row its children actually reach (#236) ----
  //
  // The opposite defect to overflow, and the one nothing here could see: the cluster
  // overview's three stat cards sat in a 2225px row and used 804px of it, leaving 1421px of
  // trailing emptiness. Nothing overflowed, no line was long, contrast was fine — the box
  // line reported a healthy 2225px-wide container and said nothing about the void inside
  // it. The audit for #234 found it only by measuring `.ov-cards` and `.ov-card` separately
  // and doing the arithmetic by hand.
  //
  // Measured per LINE, not over the whole box: a wrapping container's last line is short by
  // design, so the honest number is the SMALLEST trailing gap any of its lines leaves. If
  // even the fullest line stops well short of the container, the container is wider than
  // anything in it — which is the finding. Absolutely positioned children are excluded
  // (they are out of flow and reach nothing), and so are zero-sized ones.
  const rowFill = () => {
    const kids = [...el.children].filter((k) => {
      const kr = k.getBoundingClientRect();
      return kr.width > 0 && kr.height > 0 && getComputedStyle(k).position !== 'absolute';
    });
    if (kids.length < 2) return null;
    const px = (v) => parseFloat(v) || 0;
    const left = r.left + px(cs.borderLeftWidth) + px(cs.paddingLeft);
    const right = r.right - px(cs.borderRightWidth) - px(cs.paddingRight);
    const inner = right - left;
    if (inner <= 0) return null;

    // Cluster children into lines by their top edge with an 8px tolerance, so children of
    // different heights or a non-stretch align-items still count as one line.
    const lines = [];
    for (const k of [...kids].sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top)) {
      const kr = k.getBoundingClientRect();
      const line = lines.find((l) => Math.abs(l.top - kr.top) <= 8);
      if (line) line.right = Math.max(line.right, kr.right);
      else lines.push({ top: kr.top, right: kr.right });
    }
    const unused = Math.min(...lines.map((l) => right - l.right));
    return { kids: kids.length, lines: lines.length, inner: Math.round(inner), unused: Math.round(unused) };
  };

  return {
    count: els.length,
    word: widestWord(),
    row: rowFill(),
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
}

/**
 * Positive controls for the `words` and `row` checks, against a fixture whose answer is
 * arithmetic.
 *
 * Written because `words` was added off the back of #257 and there was no way to see it FIRE
 * — the app had already been fixed, so a clean run proved only that it was quiet. A metric with
 * no case whose answer is known ahead of time is the shape of every wrong conclusion in this
 * repo's history. `row` (#236) is pinned the same way, including the wrapping case, where the
 * naive "container width minus the widest line" reading invents a defect on every wrapped
 * layout. Needs no running app: `node scripts/ui-measure.mjs --self-test`.
 */
const SELF_TEST_FIXTURE = `
<style>
  body { margin: 0; font: 13px/1.4 system-ui, sans-serif; }
  div { box-sizing: border-box; padding: 0 12px; word-break: break-word; }
  #squeezed { width: 40px; }
  #roomy    { width: 400px; }
  #nowrap   { width: 40px; white-space: nowrap; }
  #hyphen   { width: 90px; }
  .rowbox { display: flex; flex-wrap: wrap; gap: 10px; padding: 0; width: 1000px; }
  .rowbox > i { height: 30px; background: #ccc; display: block; }
  #empty-row > i  { width: 100px; }
  #full-row  > i  { width: 320px; }
  #wrapped   > i  { width: 320px; }
</style>
<div id="squeezed">Reason</div>
<div id="roomy">Reason</div>
<div id="nowrap">Reason</div>
<div id="hyphen">Pod/kw251-bad-a</div>
<div id="empty-row" class="rowbox"><i></i><i></i><i></i></div>
<div id="full-row" class="rowbox"><i></i><i></i><i></i></div>
<div id="wrapped" class="rowbox"><i></i><i></i><i></i><i></i></div>`;

// [selector, metric, must-fire?, why]
const SELF_TEST_CASES = [
  ['#squeezed', 'word', true, 'a 40px box cannot hold "Reason" — the defect #257 shipped'],
  ['#roomy', 'word', false, 'the same word in a 400px box is fine'],
  ['#nowrap', 'word', false, 'white-space: nowrap cannot break, so it is not a word-break defect'],
  ['#hyphen', 'word', false, '"Pod/kw251-bad-a" breaks legally after / and -, so only "kw251-" must fit'],
  ['#empty-row', 'row', true, '3x100px + gaps = 320px of a 1000px row — the shape of #236'],
  ['#full-row', 'row', false, '3x320px + gaps = 1000px, so the row is used'],
  ['#wrapped', 'row', false, '4x320px wraps to a second line; a short LAST line is not waste'],
];

/** The reporting threshold for `row`: a third of the container AND more than a gap's worth. */
const rowIsEmpty = (row) => !!row && row.unused > Math.max(200, row.inner * 0.33);

if (argv.includes('--self-test')) {
  const { chromium } = await import(`${process.env.HOME}/.local/lib/playwright/node_modules/playwright/index.mjs`);
  const b = await chromium.launch({ headless: true });
  const p = await b.newPage({ viewport: { width: 800, height: 600 } });
  await p.setContent(SELF_TEST_FIXTURE);
  let bad = 0;
  for (const [sel, metric, wantDefect, why] of SELF_TEST_CASES) {
    const got = await p.$$eval(sel, measureOne);
    const fired = metric === 'row' ? rowIsEmpty(got?.row) : !!got?.word;
    const ok = fired === wantDefect;
    if (!ok) bad += 1;
    const detail =
      metric === 'row'
        ? got?.row
          ? `${got.row.unused}px unused of ${got.row.inner}px`
          : 'no row measured'
        : got?.word
          ? `"${got.word.run}" ${got.word.w}px in ${got.word.inner}px`
          : 'no word defect';
    console.log(`${ok ? 'ok  ' : 'FAIL'}  ${sel.padEnd(11)} ${metric.padEnd(5)} ${detail.padEnd(30)} ${why}`);
  }
  await b.close();
  console.log(bad ? `\n${bad} control(s) failed.` : '\nAll positive controls hold.');
  process.exit(bad ? 1 : 0);
}

const { browser, page } = await open({ view, url: path ? new URL(path, BASE_URL).href : BASE_URL });
let failed = false;
try {
  if (theme) await setTheme(page, theme);
  if (leaf) await openLeaf(page, leaf);
  await runPrepare(page, process.env.PREPARE);
  await page.waitForTimeout(400);

  for (const sel of selectors) {
    const m = await page.$$eval(sel, measureOne)
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
    if (m.row) {
      // Reported whenever the element has children in flow, not only when it is over the
      // threshold: "this row is full" is the answer half the responsive questions here need,
      // and a number that only appears when it is bad cannot be used as a before/after.
      console.log(
        `  row      ${m.row.kids} children on ${m.row.lines} line(s) leave ${m.row.unused}px` +
          ` of ${m.row.inner}px unused${rowIsEmpty(m.row) ? '  <-- trailing emptiness' : ''}`,
      );
    }
    if (m.word) {
      console.log(
        `  words    "${m.word.run}" needs ${m.word.w}px in a ${m.word.inner}px box` +
          `  <-- DEFECT: it must break mid-word`,
      );
      failed = true;
    }
    if (overClip || overView) failed = true;
  }
} finally {
  await browser.close();
}

console.log(`\nview=${view}${theme ? ` theme=${theme}` : ''} — ${failed ? 'PROBLEMS FOUND' : 'nothing over budget'}`);
process.exit(failed ? 1 : 0);

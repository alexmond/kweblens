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
//   clipped   text an ellipsis is cutting, measured sub-pixel: a fraction of a pixel short
//             is a defect, because the ellipsis pays for itself in whole characters
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
  // Measured, not guessed: the longest UNBREAKABLE run is laid out in the font that renders
  // it and compared with the matched element's content box. Unbreakable, not "word" — a
  // browser may break after `-` and `/`, so `Pod/kw251-bad-a` is legally three runs and only
  // the longest of them has to fit. Elements that cannot wrap at all (`white-space: nowrap`)
  // are skipped: they overflow or ellipsise instead, which the box/overflow and `clipped`
  // lines already report.
  //
  // DESCENDANT text counts, not only direct text nodes (#326). The first version read only
  // `e`'s own child text nodes — inherited from the chars-per-line check above, where the
  // restriction is load-bearing (a layout container's `textContent` is the whole page as one
  // "line"). Here it was a hole: the drawer Overview renders half of every `.kv dd` inside a
  // `<button class="cell-link">` or an `NTag`, so `Controlled By` and `Node` reported NO word
  // at all while the row beside them was measured. A clean `words` line on a selector meant
  // "nothing wrong with the text I could see", which is the shape of every false pass here.
  // Splitting into runs is what makes this safe where chars-per-line is not: concatenating
  // descendants cannot invent a longer WORD, only a longer line.
  //
  // Two guards keep it conservative. Runs are compared with `e`'s content box even when the
  // text sits in a narrower descendant — that can only UNDER-report (a run that fits a
  // narrower box fits `e` too), never invent a defect. And a text node is skipped when
  // anything between it and `e` takes it out of `e`'s wrapping regime: `white-space: nowrap`
  // (it ellipsises — `clipped`'s job) or an `overflow-x` scroller of its own such as
  // `.mini-scroll` (it scrolls rather than shreds).
  //
  // Runs over EVERY match, not just the first — one bad header in four is the case.
  const widestWord = () => {
    const probe = document.createElement('span');
    probe.style.cssText = 'position:absolute;visibility:hidden;white-space:pre;left:-9999px;top:0';
    document.body.appendChild(probe);
    const owners = (e) => {
      // [element whose font renders it, text] for every text node under `e` still bound by
      // `e`'s own wrapping.
      const out = [];
      const walk = document.createTreeWalker(e, NodeFilter.SHOW_TEXT);
      for (let n = walk.nextNode(); n; n = walk.nextNode()) {
        const text = (n.textContent || '').trim();
        if (!text) continue;
        let escaped = false;
        for (let a = n.parentElement; a && a !== e; a = a.parentElement) {
          const acs = getComputedStyle(a);
          if (/nowrap|pre$/.test(acs.whiteSpace) || /auto|scroll/.test(acs.overflowX)) {
            escaped = true;
            break;
          }
        }
        if (!escaped) out.push([n.parentElement, text]);
      }
      return out;
    };
    let worst = null;
    for (const e of els) {
      const ecs = getComputedStyle(e);
      if (/nowrap|pre$/.test(ecs.whiteSpace)) continue;
      const er = e.getBoundingClientRect();
      const inner =
        er.width - parseFloat(ecs.paddingLeft || 0) - parseFloat(ecs.paddingRight || 0) -
        parseFloat(ecs.borderLeftWidth || 0) - parseFloat(ecs.borderRightWidth || 0);
      if (inner <= 0) continue;
      for (const [owner, text] of owners(e)) {
        const ocs = getComputedStyle(owner);
        probe.style.fontFamily = ocs.fontFamily;
        probe.style.fontSize = ocs.fontSize;
        probe.style.fontWeight = ocs.fontWeight;
        probe.style.fontStyle = ocs.fontStyle;
        probe.style.letterSpacing = ocs.letterSpacing;
        probe.style.textTransform = ocs.textTransform;
        for (const run of text.split(/\s+/).flatMap((w) => w.split(/(?<=[-/–—])/))) {
          if (!run) continue;
          probe.textContent = run;
          const w = probe.getBoundingClientRect().width;
          if (w > inner + 0.5 && (!worst || w - inner > worst.over)) {
            worst = { run, w: Math.round(w), inner: Math.round(inner), over: w - inner };
          }
        }
      }
    }
    probe.remove();
    return worst && { run: worst.run, w: worst.w, inner: worst.inner };
  };

  // ---- What the ellipsis actually costs, measured sub-pixel (#318) ----
  //
  // `scrollWidth` and `clientWidth` are INTEGERS, and the difference between them is how
  // this script used to answer "is anything hidden inside this element". That rounding hid
  // the near-miss it was most needed for. Fixing #318 left the kind eyebrow 115.94px wide
  // for a 116.33px word: both properties reported 116, the line stayed silent — and the
  // header rendered `PERSISTENTVOLU…`, because `text-overflow` does not drop 0.4px of text,
  // it drops whole GLYPHS to make room for the ellipsis. A run said clean; the screenshot
  // said otherwise, and the screenshot was right.
  //
  // So the comparison is made in the browser's own sub-pixel geometry: a Range over the
  // element's contents reports the FULL laid-out advance even when the paint is clipped
  // (verified against a detached clone laid out at `width:auto` — 116.328125px both ways).
  // Only elements that cannot wrap AND actually clip are asked: anything else overflows
  // visibly instead, which the box/clipper lines above already report.
  //
  // A shortfall under 1px is failed rather than merely printed. A truncation that is part
  // of the design misses by tens of pixels — a name that will never fit its column. One
  // that misses by a fraction of a pixel is an accident of the layout every time, and costs
  // the reader two characters for nothing.
  const clippedText = () => {
    let worst = null;
    for (const e of els) {
      const ecs = getComputedStyle(e);
      if (!/nowrap|pre$/.test(ecs.whiteSpace)) continue;
      if (!/hidden|clip/.test(ecs.overflowX)) continue;
      const text = e.textContent.trim();
      if (!text) continue;
      const er = e.getBoundingClientRect();
      const inner =
        er.width - parseFloat(ecs.paddingLeft || 0) - parseFloat(ecs.paddingRight || 0) -
        parseFloat(ecs.borderLeftWidth || 0) - parseFloat(ecs.borderRightWidth || 0);
      if (inner <= 0) continue;
      const range = document.createRange();
      range.selectNodeContents(e);
      const need = range.getBoundingClientRect().width;
      const over = need - inner;
      if (over > 0.02 && (!worst || over > worst.over)) {
        worst = { text, need, inner, over };
      }
    }
    return worst;
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
    clipped: clippedText(),
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
  /* The descendant-text controls (#326) — no backticks in here either. #nested is the .kv dd
     shape: the value is in a CHILD element, so the direct-text-node reading measured nothing
     at all. #nested-ok is the same shape with a word that fits. #nested-nowrap and
     #nested-scroll are the two ways a descendant leaves the parent's wrapping regime, and
     must NOT be reported. */
  #nested, #nested-ok, #nested-nowrap, #nested-scroll { width: 40px; }
  #nested-ok { width: 400px; }
  #nested-nowrap > button { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; display: block; }
  #nested-scroll > span { display: block; overflow-x: auto; }
  /* The clipped controls (#318) — no backticks in here, this fixture is a template literal.
     The hairline case has to be a FRACTION of a pixel short whatever font the box happens
     to have, so its width is derived from the text itself: the parent shrink-wraps to the
     run's own advance (a percentage width resolves to auto for that intrinsic sizing, so
     there is no circularity) and the child asks for 0.4px less than its parent. #fits takes
     the same width with nothing subtracted. */
  .fit { display: inline-block; white-space: nowrap; padding: 0; }
  .fit > span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; padding: 0; }
  #hairline { width: calc(100% - 0.4px); }
  #fits     { width: 100%; }
  #cut      { width: 30px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
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
<div id="nested"><button>Reason</button></div>
<div id="nested-ok"><button>Reason</button></div>
<div id="nested-nowrap"><button>Reason</button></div>
<div id="nested-scroll"><span>Reason</span></div>
<div id="empty-row" class="rowbox"><i></i><i></i><i></i></div>
<div id="full-row" class="rowbox"><i></i><i></i><i></i></div>
<div id="wrapped" class="rowbox"><i></i><i></i><i></i><i></i></div>
<div id="cut">Reason</div>
<span class="fit"><span id="hairline">PersistentVolume</span></span>
<span class="fit"><span id="fits">PersistentVolume</span></span>`;

// [selector, metric, must-fire?, why]
const SELF_TEST_CASES = [
  ['#squeezed', 'word', true, 'a 40px box cannot hold "Reason" — the defect #257 shipped'],
  ['#roomy', 'word', false, 'the same word in a 400px box is fine'],
  ['#nowrap', 'word', false, 'white-space: nowrap cannot break, so it is not a word-break defect'],
  ['#hyphen', 'word', false, '"Pod/kw251-bad-a" breaks legally after / and -, so only "kw251-" must fit'],
  ['#nested', 'word', true, 'the word is in a CHILD element — the .kv dd shape that reported nothing'],
  ['#nested-ok', 'word', false, 'the same child text in a 400px box is fine'],
  ['#nested-nowrap', 'word', false, 'a nowrap child ellipsises; that is the clipped check, not this one'],
  ['#nested-scroll', 'word', false, 'a child with its own scroller scrolls rather than shreds'],
  ['#empty-row', 'row', true, '3x100px + gaps = 320px of a 1000px row — the shape of #236'],
  ['#full-row', 'row', false, '3x320px + gaps = 1000px, so the row is used'],
  ['#wrapped', 'row', false, '4x320px wraps to a second line; a short LAST line is not waste'],
  ['#cut', 'clipseen', true, 'a 30px box ellipsizes "Reason" — the cut is reported'],
  ['#cut', 'clip', false, 'missing by many px is a DESIGNED truncation, not a defect'],
  ['#hairline', 'clipseen', true, '0.4px short is still clipped, however the integers round'],
  ['#hairline', 'clip', true, '...and sub-pixel is a defect: #318 lost two glyphs to 0.4px'],
  ['#fits', 'clipseen', false, 'the same run in exactly its own advance is not clipped'],
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
    const fired =
      metric === 'row'
        ? rowIsEmpty(got?.row)
        : metric === 'clipseen'
          ? !!got?.clipped
          : metric === 'clip'
            ? !!got?.clipped && got.clipped.over < 1
            : !!got?.word;
    const ok = fired === wantDefect;
    if (!ok) bad += 1;
    const detail =
      metric === 'row'
        ? got?.row
          ? `${got.row.unused}px unused of ${got.row.inner}px`
          : 'no row measured'
        : metric.startsWith('clip')
          ? got?.clipped
            ? `${got.clipped.over.toFixed(2)}px cut of ${got.clipped.need.toFixed(2)}px`
            : 'nothing clipped'
          : got?.word
            ? `"${got.word.run}" ${got.word.w}px in ${got.word.inner}px`
            : 'no word defect';
    console.log(`${ok ? 'ok  ' : 'FAIL'}  ${sel.padEnd(11)} ${metric.padEnd(8)} ${detail.padEnd(30)} ${why}`);
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
    // The sub-pixel line supersedes the integer one where it applies: both describe hidden
    // content, and only one of them can see a 0.4px miss.
    if (m.clipped) {
      const px = (v) => v.toFixed(2).replace(/\.00$/, '');
      const hairline = m.clipped.over < 1;
      console.log(
        `  clipped  "${m.clipped.text}" needs ${px(m.clipped.need)}px in a ${px(m.clipped.inner)}px box` +
          ` (${px(m.clipped.over)}px cut)` +
          (hairline ? '  <-- DEFECT: a sub-pixel miss still costs whole characters' : ''),
      );
      if (hairline) failed = true;
    } else if (selfScroll) {
      console.log(`  content  scrollWidth=${m.scrollW} (${m.scrollW - m.box.w}px hidden inside it)`);
    }
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

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
//   words     a word too wide for its own box AND visibly the worse for it — either the
//             browser broke it mid-word, or it spilled past the element's own padding box
//   chip      a pill (own background, rounded ends, short label) squeezed below its own
//             label and wrapped, in a parent that had room for it — a legal wrap that reads
//             as a rendering fault
//   sliced    a pill whose own box is CUT by an ancestor that hides its overflow — the same
//             shape as `chip`, truncated rather than wrapped, and invisible to `clipped`
//             because the text fits the pill and it is the pill that does not fit
//   clipped   text an ellipsis is cutting, measured sub-pixel: a fraction of a pixel short
//             is a defect, because the ellipsis pays for itself in whole characters
//   twins     two matches whose DIFFERENT text truncates to the SAME visible string — the
//             row that stops naming itself, which is worse than any amount of clipping
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
  //
  // A run wider than its content box is the QUESTION, not the answer (#343). The first two
  // versions failed the run on that comparison alone, and it reported 140 non-defects on an
  // ordinary page: `.nav-badge` is `min-width: 18px; padding: 0 6px`, so a badge squeezed to
  // its floor has a **6px content box inside an 18px pill**, and two centred digits needing
  // 11.45px spill 2.7px each side into 6px of padding. Measured across all 140, the worst case
  // was 6.55px INSIDE its own border box and not one badge's text exceeded its pill. Right by
  // the check's own definition, invisible to a reader — and its message ("it must break
  // mid-word") was wrong about the consequence, because digits have no break opportunity, so
  // they overflow instead. 140 spurious failures is how a check stops being read at all, which
  // is the thing this file's own comment says costs more than not having the check.
  //
  // So the POPULATION is unchanged — a run wider than the content box is still the only thing
  // examined — and only the VERDICT moved: it now has to name observable damage, of which
  // there are exactly two kinds.
  //
  //   broke   The run is painting on more than one line, so the browser was allowed to break
  //           inside it (`overflow-wrap: anywhere`, Naive's `word-break: break-word`,
  //           `break-all`) and did. This is #257, #278, #318 and #326 verbatim — all four
  //           shredded a word — and it is measured from the run's OWN client rects rather than
  //           inferred from a box, so it is the damage itself and not a proxy for it.
  //   spilled The run's advance exceeds the element's PADDING box, i.e. it escapes the shape
  //           the element paints, where an ancestor can clip it or a neighbour can collide with
  //           it. Padding is inside that shape: text laid over an element's own padding is
  //           legible, unclipped and does not move anything.
  //
  // The padding box, not the border box: a border is a painted edge, and text crossing it has
  // left the element as the reader sees it. For the zero-padding majority (`.kv dd` has no
  // padding at all) the padding box IS the content box, so nothing about #326's numbers moves.
  //
  // Why `broke` is not optional once the box widens: with `word-break: break-all` a 6px content
  // box shreds a run that would have fitted the 18px pill perfectly well — a two-line badge the
  // padding-box comparison alone would wave through. The two conditions close each other's hole.
  //
  // The absorbed case is still PRINTED, without failing, because a check that silently drops a
  // population it used to fail is indistinguishable from one that stopped looking.
  const widestWord = () => {
    const px = (v) => parseFloat(v) || 0;
    const probe = document.createElement('span');
    probe.style.cssText = 'position:absolute;visibility:hidden;white-space:pre;left:-9999px;top:0';
    document.body.appendChild(probe);
    const range = document.createRange();
    const owners = (e) => {
      // [element whose font renders it, text node] for every text node under `e` still bound by
      // `e`'s own wrapping.
      const out = [];
      const walk = document.createTreeWalker(e, NodeFilter.SHOW_TEXT);
      for (let n = walk.nextNode(); n; n = walk.nextNode()) {
        if (!(n.textContent || '').trim()) continue;
        let escaped = false;
        for (let a = n.parentElement; a && a !== e; a = a.parentElement) {
          const acs = getComputedStyle(a);
          if (/nowrap|pre$/.test(acs.whiteSpace) || /auto|scroll/.test(acs.overflowX)) {
            escaped = true;
            break;
          }
        }
        if (!escaped) out.push([n.parentElement, n]);
      }
      return out;
    };
    // Every unbreakable run in a text node, with its offsets, so the run can be measured where
    // it actually sits rather than re-laid-out somewhere else. A browser may break after `-`
    // and `/`, so `Pod/kw251-bad-a` is three runs and only the longest has to fit.
    const runsOf = (raw) => {
      const out = [];
      for (const m of raw.matchAll(/\S+/g)) {
        let at = m.index;
        for (const part of m[0].split(/(?<=[-/–—])/)) {
          if (part) out.push([at, at + part.length, part]);
          at += part.length;
        }
      }
      return out;
    };
    // How many lines the run itself is painted on. Within one text node the font is constant,
    // so line boxes differ by the line height and 1px of tolerance is enough; a zero-height
    // rect is a collapsed boundary, not a line.
    const lineCount = (node, start, end) => {
      range.setStart(node, start);
      range.setEnd(node, end);
      const tops = [];
      for (const q of range.getClientRects()) {
        if (q.height <= 0) continue;
        if (!tops.some((t) => Math.abs(t - q.top) <= 1)) tops.push(q.top);
      }
      return tops.length;
    };
    let worst = null;
    let absorbed = null;
    for (const e of els) {
      const ecs = getComputedStyle(e);
      if (/nowrap|pre$/.test(ecs.whiteSpace)) continue;
      const er = e.getBoundingClientRect();
      const pad = er.width - px(ecs.borderLeftWidth) - px(ecs.borderRightWidth);
      const inner = pad - px(ecs.paddingLeft) - px(ecs.paddingRight);
      // `inner <= 0` is no longer a reason to skip: a pill whose padding eats its whole content
      // box is exactly the shape this check was over-reporting.
      if (pad <= 0) continue;
      for (const [owner, node] of owners(e)) {
        const ocs = getComputedStyle(owner);
        probe.style.fontFamily = ocs.fontFamily;
        probe.style.fontSize = ocs.fontSize;
        probe.style.fontWeight = ocs.fontWeight;
        probe.style.fontStyle = ocs.fontStyle;
        probe.style.letterSpacing = ocs.letterSpacing;
        probe.style.textTransform = ocs.textTransform;
        for (const [start, end, run] of runsOf(node.textContent)) {
          probe.textContent = run;
          const w = probe.getBoundingClientRect().width;
          if (w <= inner + 0.5) continue;
          const spill = w - pad;
          const broke = lineCount(node, start, end) > 1;
          const hit = { run, w, inner, pad, broke, spill };
          if (!broke && spill <= 0.5) {
            if (!absorbed || w - inner > absorbed.w - absorbed.inner) absorbed = hit;
            continue;
          }
          // A shredded run outranks a spilled one however far the spill goes: the reader has
          // lost the word, not just the margin.
          const rank = (h) => (h.broke ? 1e6 + (h.w - h.inner) : h.spill);
          if (!worst || rank(hit) > rank(worst)) worst = hit;
        }
      }
    }
    probe.remove();
    return { worst, absorbed };
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

  // ---- A fixed-shape pill that wrapped because a flex row squeezed it (#331) ----
  //
  // The fifth defect in the family behind #257, #278, #318 and #326, and the first one none
  // of the checks above could see. `.count` — the list header's items badge — rendered 47px
  // wide and 42px TALL at `narrow`: "3" over "items", a rounded pill two lines high. Every
  // line here stayed silent and each was right to: nothing overflowed (the row fits, at
  // min-content), no word was too wide for its box ("items" fits in 47px), nothing was
  // clipped (it wrapped rather than truncating), and no two labels read alike.
  //
  // What was wrong is a thing none of them ask: whether the element WANTED to be one line.
  // A flex item's automatic minimum size is its MIN-CONTENT, and min-content for a short
  // label is its longest WORD — so the flex algorithm may legally shrink a badge to the
  // width of "items" and let the space become a line break. The wrap is correct CSS and a
  // rendering fault to the reader, because a pill's shape is what says "this is one value".
  //
  // Two signals have to agree before this fires, and each rules out a different false
  // positive:
  //
  //   1. It really is painting more than one line. Counted from the line boxes of a Range
  //      over its contents, clustered by top with the same 8px tolerance `row` uses, because
  //      a vertically-centred dot (`.live`) sits at a different top on the SAME line.
  //   2. It is narrower than its own max-content, AND its max-content would have fitted in
  //      the room its parent has. Measured on a clone at `width: max-content` — absolutely
  //      positioned, so it is out of flow and cannot be a flex item of the parent it borrows
  //      the fonts and inherited rules from. The second half is the important one: if the
  //      parent genuinely cannot hold it, wrapping is the least-bad outcome and there is
  //      nothing here to fix. This check is about a squeeze that was AVOIDABLE.
  //
  // Scoped to pills — a self-painted background and rounded ends, with a short label — so it
  // stays quiet on the prose that is supposed to wrap. The list header's own `h1` wraps to
  // three lines beside the badge and is fine; a `<span>` with a 10px radius and a background
  // is not prose, it is a shape, and a shape that reflows has broken.
  const wrappedChip = () => {
    const px = (v) => parseFloat(v) || 0;
    let worst = null;
    for (const e of els) {
      const ecs = getComputedStyle(e);
      if (ecs.position === 'absolute' || ecs.position === 'fixed') continue;
      if (/nowrap|pre$/.test(ecs.whiteSpace)) continue;
      // A pill: it paints its own background and its ends are rounded.
      if (/^(transparent|rgba\(0, 0, 0, 0\))$/.test(ecs.backgroundColor)) continue;
      const radius = Math.max(px(ecs.borderTopLeftRadius), px(ecs.borderBottomLeftRadius));
      if (radius < 4) continue;
      // A label, not a paragraph. 40 characters is the same cut the chars-per-line check
      // uses for "this is not prose".
      const text = e.textContent.replace(/\s+/g, ' ').trim();
      if (!text || text.length > 40) continue;

      const er = e.getBoundingClientRect();
      if (er.width <= 0 || er.height <= 0) continue;

      const range = document.createRange();
      range.selectNodeContents(e);
      const tops = [];
      for (const q of range.getClientRects()) {
        if (q.width <= 0 || q.height <= 0) continue;
        if (!tops.some((t) => Math.abs(t - q.top) <= 8)) tops.push(q.top);
      }
      if (tops.length < 2) continue;

      const parent = e.parentElement;
      if (!parent) continue;
      const pcs = getComputedStyle(parent);
      const pr = parent.getBoundingClientRect();
      const parentInner =
        pr.width - px(pcs.paddingLeft) - px(pcs.paddingRight) - px(pcs.borderLeftWidth) - px(pcs.borderRightWidth);
      if (parentInner <= 0) continue;

      const clone = e.cloneNode(true);
      clone.style.position = 'absolute';
      clone.style.visibility = 'hidden';
      clone.style.left = '-9999px';
      clone.style.top = '0';
      clone.style.width = 'max-content';
      clone.style.maxWidth = 'none';
      parent.appendChild(clone);
      const need = clone.getBoundingClientRect().width;
      clone.remove();

      if (need <= er.width + 0.5) continue;
      if (need > parentInner + 0.5) continue;
      const over = need - er.width;
      if (!worst || over > worst.over) {
        worst = { text, lines: tops.length, w: er.width, need, room: parentInner, over };
      }
    }
    return worst;
  };

  // ---- A pill CUT OFF by a container that hides its overflow (#341) ----
  //
  // Sixth in the family, and the second one about a SHAPE rather than about text. The list's
  // Status column started rendering the server's state (`CrashLoopBackOff`, 16 characters)
  // where it had rendered `status.phase` (`Running`, 7), inside a Naive `NTag` inside a
  // fixed-width table cell. The pill's right end was sliced flat by the cell — a square-ended
  // red block reading `CrashLoopBackO` — and **every check in this file stayed silent**:
  //
  //   - `clipped` measures text against ITS OWN box, and the tag's inner span is exactly as
  //     wide as its text. The cut happens one level up, at the wrapper. It also requires a
  //     direct text node, and a wrapper whose only child is a pill has none.
  //   - `chip` (#331) asks whether a pill WRAPPED. This one did not wrap; it was truncated.
  //   - `words`, `twins`, `row`, `box` — all correct and all about something else.
  //
  // The measurement that caught it was a 300% crop of a screenshot, which is the same way
  // #327 was found and is not a method. So: the pill's own border box against the padding box
  // of the ancestor that clips it. A pill is not text — it cannot reflow, it has no ellipsis,
  // and a rounded end that has become a straight edge is the reader's only clue that anything
  // is missing at all.
  //
  // Only a clipper the reader cannot get past counts. An `overflow-x: auto` ancestor whose
  // content overflows is a SCROLLER — `.mini-scroll` is this project's sanctioned escape
  // hatch for exactly that — so the pill is reachable and nothing is lost. `hidden` and
  // `clip` are unreachable, and so is an `auto` that has nothing to scroll.
  const slicedChip = () => {
    const px = (v) => parseFloat(v) || 0;
    let worst = null;
    for (const e of els) {
      const ecs = getComputedStyle(e);
      if (ecs.position === 'fixed') continue;
      // The same "is it a pill" gate the wrap check uses: it paints its own background, its
      // ends are rounded, and it carries a label rather than a paragraph.
      if (/^(transparent|rgba\(0, 0, 0, 0\))$/.test(ecs.backgroundColor)) continue;
      if (Math.max(px(ecs.borderTopLeftRadius), px(ecs.borderBottomLeftRadius)) < 4) continue;
      const text = e.textContent.replace(/\s+/g, ' ').trim();
      if (!text || text.length > 40) continue;
      const er = e.getBoundingClientRect();
      if (er.width <= 0 || er.height <= 0) continue;

      for (let a = e.parentElement; a && a !== document.documentElement; a = a.parentElement) {
        const acs = getComputedStyle(a);
        const overflowX = acs.overflowX;
        if (!/hidden|clip|auto|scroll/.test(overflowX)) continue;
        if (/auto|scroll/.test(overflowX) && a.scrollWidth > a.clientWidth + 1) break; // reachable
        const ar = a.getBoundingClientRect();
        const left = ar.left + px(acs.borderLeftWidth) + px(acs.paddingLeft);
        const right = ar.right - px(acs.borderRightWidth) - px(acs.paddingRight);
        const cut = Math.max(0, left - er.left) + Math.max(0, er.right - right);
        if (cut > 0.5 && (!worst || cut > worst.cut)) {
          worst = { text, cut, w: er.width, room: right - left, by: a.className || a.tagName.toLowerCase() };
        }
        break;
      }
    }
    return worst;
  };

  // ---- Two rows that stopped naming themselves (#327) ----
  //
  // `clipped` says how much of ONE label is cut. It cannot say the thing that actually made
  // the nav unusable: that the cut fell in the same place on two neighbours, so the left rail
  // rendered `VerticalPodAuto…` twice, one row above the other, and `Validating Admissio…`
  // twice below that. Kubernetes kinds are built by suffixing, so a tail-ellipsis removes
  // exactly the part that distinguishes siblings — a strictly worse failure than the mid-word
  // breaks of #318/#326, where the text was ugly but the information survived. It was found by
  // reading a screenshot; nothing here would have failed, because each label on its own was
  // merely truncated, which is normal and by design.
  //
  // So: which characters actually get painted. Each character's own rect is compared with the
  // element's content box, and a dropped run is replaced by `…` — the string the reader sees.
  // The comparison is deliberately loose (equal, or one a prefix of the other) because a
  // sibling's count badge can be a digit wider and shift the fit by a character; two labels
  // that differ only in that character are not distinguishable in practice either.
  //
  // What this reports is what FITS, not what is finally painted: the browser buys room for the
  // `…` with one or two more characters. That bias is identical for both members of a pair, so
  // it cannot invent or hide a twin — but it does mean the printed string is a character or two
  // longer than the screenshot's. Only truncated elements are compared, which is what keeps
  // this quiet: the eight `Overview` leaves are identical and fit, so they are not twins.
  //
  // One more thing it has to know: a FRAGMENT is not a label. The fix for #327 renders a leaf
  // as two spans, an elidable head and a protected tail, and the heads of two siblings really
  // do truncate to the same string — that is the design, and the tail beside them is what the
  // reader tells them apart by. Asked for `.leaf-head` the first version reported those heads
  // as twins: a defect that does not exist, in the code that had just fixed the one that did.
  // So an element whose neighbour's text carries on within 3px of where its own box ends reads
  // as one continuous string with that neighbour and is skipped — measure the wrapper instead.
  // Table cells are not caught by this: their padding puts the next cell's text well clear.
  const isFragment = (e) => {
    const r = e.getBoundingClientRect();
    for (const sib of [e.previousElementSibling, e.nextElementSibling]) {
      if (!sib || !sib.textContent.trim()) continue;
      const sr = document.createRange();
      sr.selectNodeContents(sib);
      const t = sr.getBoundingClientRect();
      if (t.width === 0 && t.height === 0) continue;
      const sameLine = t.top < r.bottom - 1 && t.bottom > r.top + 1;
      if (sameLine && (Math.abs(t.left - r.right) <= 3 || Math.abs(r.left - t.right) <= 3)) return true;
    }
    return false;
  };

  const twinLabels = () => {
    const shown = [];
    for (const e of els) {
      const ecs = getComputedStyle(e);
      if (!/hidden|clip/.test(ecs.overflowX)) continue;
      if (isFragment(e)) continue;
      const er = e.getBoundingClientRect();
      const left = er.left + parseFloat(ecs.borderLeftWidth || 0) + parseFloat(ecs.paddingLeft || 0);
      const right = er.right - parseFloat(ecs.borderRightWidth || 0) - parseFloat(ecs.paddingRight || 0);
      if (right - left <= 0) continue;
      const range = document.createRange();
      range.selectNodeContents(e);
      const full = e.textContent.replace(/\s+/g, ' ').trim();
      if (!full || range.getBoundingClientRect().width <= right - left + 0.02) continue;

      let visible = '';
      let dropped = false;
      const walker = document.createTreeWalker(e, NodeFilter.SHOW_TEXT);
      for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        for (let i = 0; i < node.textContent.length; i += 1) {
          range.setStart(node, i);
          range.setEnd(node, i + 1);
          const cr = range.getBoundingClientRect();
          if (cr.width === 0 && cr.height === 0) continue;
          if (cr.right <= right + 0.02 && cr.left >= left - 0.02) {
            if (dropped) visible += '…';
            dropped = false;
            visible += node.textContent[i];
          } else {
            dropped = true;
          }
        }
      }
      if (dropped) visible += '…';
      shown.push({ full, visible: visible.replace(/\s+/g, ' ').trim() });
    }

    // Cluster by "reads the same": equal, or one the beginning of the other.
    const groups = [];
    for (const s of shown) {
      const alike = (a, b) => a.visible.startsWith(b.visible) || b.visible.startsWith(a.visible);
      const g = groups.find((grp) => grp.every((m) => alike(m, s)));
      if (g) g.push(s);
      else groups.push([s]);
    }
    return groups
      .filter((g) => g.length > 1 && new Set(g.map((m) => m.full)).size > 1)
      .map((g) => ({ visible: g.map((m) => m.visible).sort((a, b) => a.length - b.length)[0], full: g.map((m) => m.full) }));
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

  const words = widestWord();
  return {
    count: els.length,
    word: words.worst,
    wordAbsorbed: words.absorbed,
    chip: wrappedChip(),
    sliced: slicedChip(),
    clipped: clippedText(),
    twins: twinLabels(),
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
  /* The padding controls (#343) — no backticks. A flex row with no room for the pill drives it
     onto its own min-width, which is .nav-badge's exact shape: a 6px content box inside an
     18px pill. #pad-absorbed's two digits overflow that content box, have no break opportunity
     and stay well inside the pill — the 140 non-defects that made the check unreadable, and the
     control that says it still SEES them (metric wordabs) rather than having stopped looking.
     #pad-broke is the same pill with break-all, so the same overflow becomes two lines inside a
     box the padding would otherwise have absorbed: the hole a padding-box comparison alone
     would open, and it MUST fire. #pad-spill is wider than the pill itself and so escapes the
     shape it is painted in, unbroken. */
  .padrow { display: flex; width: 60px; padding: 0; }
  .padrow > .lbl { flex: 0 0 50px; padding: 0; }
  .spillrow { display: flex; width: 90px; padding: 0; }
  .pad {
    flex: 0 1 auto; box-sizing: border-box; min-width: 18px; padding: 0 6px;
    background: #e5e7eb; border-radius: 9px; font-size: 10px; text-align: center;
    word-break: normal; overflow-wrap: normal;
  }
  #pad-broke { word-break: break-all; }
  #pad-spill { flex: 0 0 24px; }
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
  /* The twins controls (#327) — still no backticks. Same box, same font, so the pair that
     shares a long prefix truncates to one string and the pair that does not stays two. The
     .same case must NOT fire: identical labels are not a defect, only identical RENDERINGS
     of two different ones are. The .protected pair is the fix's own shape — a head that
     gives way and a tail that does not — and is the control saying a fix measures as one. */
  .tw { width: 110px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .tw > span { overflow: hidden; text-overflow: ellipsis; white-space: pre; min-width: 0; }
  .tw.split { display: flex; }
  .tw.split > .h { flex: 0 1 auto; }
  .tw.split > .t { flex: 0 0 auto; max-width: 100%; }
  /* The chip controls (#331) — still no backticks. Same trick as #hairline: the width is
     derived from the TEXT, so the control is exact in whatever font the box happens to have.
     .pillbox shrink-wraps to the pill's own max-content (a percentage width resolves to auto
     for that intrinsic sizing, so there is no circularity), and a pill at 60% of it must
     break at its one space, into two words each comfortably under 60%. #pill-tight is the
     discriminator that keeps this honest: same wrapped pill, but its parent has no room for
     the label, so the wrap is the least-bad outcome and there is nothing to fix. #prose is
     the list header's h1 — text that wraps and is SUPPOSED to. */
  .pillbox { display: inline-block; padding: 0; }
  .tightbox { display: inline-block; width: 70px; padding: 0; }
  .pill { display: block; background: #e5e7eb; border-radius: 10px; padding: 2px 8px; }
  #pill-squeezed { width: 70%; }
  #pill-roomy { width: 100%; }
  #pill-tight { width: 100%; }
  #prose { display: block; width: 70%; }
  /* The sliced controls (#341) — no backticks here either. A pill inside a box that is too
     narrow for it: overflow hidden cuts it and the reader can never see the rest, overflow-x
     auto with real overflow is a SCROLLER and the pill stays reachable, and the roomy box is
     the negative. white-space nowrap on the cell is what makes the pill overflow rather than
     re-lay out, which is exactly what a Naive data-table cell does. */
  .cell { display: block; width: 90px; white-space: nowrap; padding: 0; }
  .tag { display: inline-block; background: #e5e7eb; border-radius: 10px; padding: 2px 8px; }
  .notag { display: inline-block; }
  #sliced-cell   { overflow: hidden; }
  #scroller-cell { overflow-x: auto; }
  #roomy-cell    { width: 300px; overflow: hidden; }
  #plain-cell    { overflow: hidden; }
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
<span class="padrow"><span class="lbl">Pods</span><span class="pad" id="pad-absorbed">60</span></span>
<span class="padrow"><span class="lbl">Pods</span><span class="pad" id="pad-broke">60</span></span>
<span class="spillrow"><span class="pad" id="pad-spill">Warning</span></span>
<span class="pillbox"><span class="pill" id="pill-squeezed">Cluster scoped</span></span>
<span class="pillbox"><span class="pill" id="pill-roomy">Cluster scoped</span></span>
<span class="tightbox"><span class="pill" id="pill-tight">Cluster scoped</span></span>
<span class="pillbox"><span id="prose">Cluster scoped</span></span>
<div class="cell" id="sliced-cell"><span class="tag sliced-pill">CrashLoopBackOff</span></div>
<div class="cell" id="scroller-cell"><span class="tag scrolled-pill">CrashLoopBackOff</span></div>
<div class="cell" id="roomy-cell"><span class="tag roomy-pill">CrashLoopBackOff</span></div>
<div class="cell" id="plain-cell"><span class="notag plain-text">CrashLoopBackOff</span></div>
<div id="empty-row" class="rowbox"><i></i><i></i><i></i></div>
<div id="full-row" class="rowbox"><i></i><i></i><i></i></div>
<div id="wrapped" class="rowbox"><i></i><i></i><i></i><i></i></div>
<div id="cut">Reason</div>
<span class="fit"><span id="hairline">PersistentVolume</span></span>
<span class="fit"><span id="fits">PersistentVolume</span></span>
<div class="tw twin">VerticalPodAutoscaler</div>
<div class="tw twin">VerticalPodAutoscalerCheckpoint</div>
<div class="tw apart">AlphaSomethingRatherLong</div>
<div class="tw apart">BetaSomethingRatherLong</div>
<div class="tw same">VerticalPodAutoscalerCheckpoint</div>
<div class="tw same">VerticalPodAutoscalerCheckpoint</div>
<div class="tw split protected"><span class="h">VerticalPod</span><span class="t">Autoscaler</span></div>
<div class="tw split protected"><span class="h">VerticalPodAutoscaler</span><span class="t">Checkpoint</span></div>`;

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
  ['#pad-absorbed', 'word', false, 'digits over a 6px content box but inside an 18px pill — the 140 of #343'],
  ['#pad-absorbed', 'wordabs', true, '...and the check SAW them: seen, classified, not failed'],
  ['#pad-broke', 'word', true, 'the same pill with break-all shreds a run the padding would have held'],
  ['#pad-spill', 'word', true, 'a run wider than the pill escapes the shape it is painted in'],
  ['#pill-squeezed', 'chip', true, 'a pill at 70% of its own label wraps — the shape of #331'],
  ['#pill-roomy', 'chip', false, 'the same pill at its full advance is one line'],
  ['#pill-tight', 'chip', false, 'wrapped, but its parent cannot hold the label — nothing to fix'],
  ['#prose', 'chip', false, 'wrapped text with no pill shape is text doing what text does'],
  ['.sliced-pill', 'sliced', true, 'a pill wider than a hidden-overflow cell is cut flat — the shape of #341'],
  ['.scrolled-pill', 'sliced', false, 'the same pill in a scroller is reachable, so nothing is lost'],
  ['.roomy-pill', 'sliced', false, 'the same pill in a cell that fits it is not cut'],
  ['.plain-text', 'sliced', false, 'clipped TEXT is the clipped check; this one is only about shapes'],
  ['#empty-row', 'row', true, '3x100px + gaps = 320px of a 1000px row — the shape of #236'],
  ['#full-row', 'row', false, '3x320px + gaps = 1000px, so the row is used'],
  ['#wrapped', 'row', false, '4x320px wraps to a second line; a short LAST line is not waste'],
  ['#cut', 'clipseen', true, 'a 30px box ellipsizes "Reason" — the cut is reported'],
  ['#cut', 'clip', false, 'missing by many px is a DESIGNED truncation, not a defect'],
  ['#hairline', 'clipseen', true, '0.4px short is still clipped, however the integers round'],
  ['#hairline', 'clip', true, '...and sub-pixel is a defect: #318 lost two glyphs to 0.4px'],
  ['#fits', 'clipseen', false, 'the same run in exactly its own advance is not clipped'],
  ['.twin', 'twins', true, 'two kinds sharing a prefix truncate to one string — the defect #327 shipped'],
  ['.apart', 'twins', false, 'two kinds that differ at the front stay two strings when cut'],
  ['.same', 'twins', false, 'the SAME label twice is not a defect; only one rendering of two labels is'],
  ['.protected', 'twins', false, 'a protected tail keeps them apart in the same box — the shape of the fix'],
  ['.protected > .h', 'twins', false, 'the two HEADS do read alike; a fragment of a label is not a label'],
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
        : metric === 'twins'
          ? (got?.twins ?? []).length > 0
          : metric === 'chip'
            ? !!got?.chip
            : metric === 'sliced'
            ? !!got?.sliced
            : metric === 'clipseen'
              ? !!got?.clipped
              : metric === 'clip'
                ? !!got?.clipped && got.clipped.over < 1
                : metric === 'wordabs'
                  ? !!got?.wordAbsorbed
                  : !!got?.word;
    const ok = fired === wantDefect;
    if (!ok) bad += 1;
    const detail =
      metric === 'row'
        ? got?.row
          ? `${got.row.unused}px unused of ${got.row.inner}px`
          : 'no row measured'
        : metric === 'twins'
        ? got?.twins?.length
          ? `both read "${got.twins[0].visible}"`
          : 'no two read the same'
        : metric === 'chip'
        ? got?.chip
          ? `${got.chip.lines} lines, ${got.chip.w.toFixed(1)}px for ${got.chip.need.toFixed(1)}px`
          : 'no avoidable pill wrap'
        : metric === 'sliced'
        ? got?.sliced
          ? `${got.sliced.cut.toFixed(1)}px of the pill cut off`
          : 'no pill cut off'
        : metric.startsWith('clip')
          ? got?.clipped
            ? `${got.clipped.over.toFixed(2)}px cut of ${got.clipped.need.toFixed(2)}px`
            : 'nothing clipped'
          : metric === 'wordabs'
            ? got?.wordAbsorbed
              ? `"${got.wordAbsorbed.run}" ${got.wordAbsorbed.w.toFixed(1)}px in ` +
                `${got.wordAbsorbed.inner.toFixed(1)}px, pill ${got.wordAbsorbed.pad.toFixed(1)}px`
              : 'nothing over its content box'
            : got?.word
              ? `"${got.word.run}" ${got.word.w.toFixed(1)}px in ${got.word.inner.toFixed(1)}px` +
                (got.word.broke ? ', BROKE' : `, +${got.word.spill.toFixed(1)}px past the pad box`)
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
      const px = (v) => v.toFixed(2).replace(/\.00$/, '');
      console.log(
        `  words    "${m.word.run}" needs ${px(m.word.w)}px in a ${px(m.word.inner)}px content box` +
          (m.word.broke
            ? `  <-- DEFECT: the browser broke it mid-word`
            : `, ${px(m.word.spill)}px past its own ${px(m.word.pad)}px padding box` +
              `  <-- DEFECT: it spills out of the shape it is painted in`),
      );
      failed = true;
    } else if (m.wordAbsorbed) {
      // Printed, never failed. This is the #343 population: over the content box, one line, and
      // still inside the element's own padding. Saying so is what distinguishes a check that
      // looked and found nothing from one that stopped looking.
      const px = (v) => v.toFixed(2).replace(/\.00$/, '');
      console.log(
        `  words    "${m.wordAbsorbed.run}" needs ${px(m.wordAbsorbed.w)}px in a` +
          ` ${px(m.wordAbsorbed.inner)}px content box — unbroken and inside its own` +
          ` ${px(m.wordAbsorbed.pad)}px padding box, so not a defect`,
      );
    }
    if (m.chip) {
      const px = (v) => v.toFixed(2).replace(/\.00$/, '');
      console.log(
        `  chip     "${m.chip.text}" wrapped to ${m.chip.lines} lines: given ${px(m.chip.w)}px` +
          ` for ${px(m.chip.need)}px, in a ${px(m.chip.room)}px row` +
          `  <-- DEFECT: a pill was squeezed below its own label`,
      );
      failed = true;
    }
    if (m.sliced) {
      const px = (v) => v.toFixed(2).replace(/\.00$/, '');
      console.log(
        `  sliced   "${m.sliced.text}" is ${px(m.sliced.w)}px in ${px(m.sliced.room)}px of .${m.sliced.by}:` +
          ` ${px(m.sliced.cut)}px of the pill cut off` +
          `  <-- DEFECT: a shape with a straight end says nothing is missing`,
      );
      failed = true;
    }
    for (const t of m.twins ?? []) {
      console.log(
        `  twins    ${t.full.map((f) => `"${f}"`).join(' and ')} both read "${t.visible}"` +
          `  <-- DEFECT: the cut removed what told them apart`,
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

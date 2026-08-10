#!/usr/bin/env node
//
// Measure WCAG contrast of rendered UI against a running kweblens, in BOTH themes.
//
// This exists because eyeballing colour has failed repeatedly here: the StatusBadge tag
// shipped at 1.93:1 (#169), Naive's primary collided with the semantic palette (#184), and
// the command palette's first two stylings measured 3.02:1 and 3.80:1 while looking fine
// (#200). Those were all caught only by measuring actual rendered pixels — which is what
// this does.
//
// THE BACKDROP COMES FROM THE RENDERED IMAGE, NOT FROM THE DOM.
//
// Earlier versions derived "what is behind this text" by walking `parentElement` upward and
// compositing translucent layers down to the first opaque ancestor. That is right about the
// cascade and wrong about paint order. Naive UI renders a select's white box
// (`.n-base-selection-label`) as a *sibling* of the input, with the input overlaid on top:
// the white is painted underneath but is not an ancestor, so the walk climbed straight past
// it to the dark top bar and called a real 12.16:1 a **1.21:1 FAIL** (#250). Anything
// painted by a sibling — overlays, absolutely-positioned layers, z-index stacking — was
// invisible to it. A false FAIL in a gating tool is worse than no tool, because it teaches
// people to ignore the real failures.
//
// So the backdrop is now read off the screen: hide every glyph, screenshot the viewport,
// decode the pixels under each measured text run, and take the modal colour. That is ground
// truth by construction — it cannot be wrong about what is on screen.
//
//   * Glyphs are hidden (`color: transparent`) rather than dodged. The other trap here is a
//     sample point landing ON a letter and reporting the text colour as the background; that
//     has burned this project before. With the text painted away, every pixel in the run's
//     own rect is backdrop, so there is no sample point to get wrong.
//   * The MODE over the whole text-run rect, not one pixel: a gradient, a focus ring or a
//     stray icon moves a single sample and cannot move the mode.
//   * The DOM walk is kept as a CROSS-CHECK and any disagreement is reported. That
//     disagreement is exactly what surfaced #250, and it is the cheapest available alarm on
//     this instrument being wrong again.
//
// Prove changes to any of that with `--self-test` (below), never by eyeballing the output.
//
// Usage:
//   scripts/dev-run.sh
//   node scripts/contrast-check.mjs                      # the default watchlist below
//   node scripts/contrast-check.mjs '.leaf.active' '.ov-card.danger'
//   PORT=8085 node scripts/contrast-check.mjs
//   PREPARE='press:Control+k' node scripts/contrast-check.mjs '.palette-row.active'
//   node scripts/contrast-check.mjs --self-test          # positive controls; no running app
//
// PREPARE runs simple actions before sampling, semicolon-separated:
//   press:<key>   click:<selector>   fill:<selector>=<text>   wait:<ms>
//   upload:<file-input selector>=<path on this machine>
//   scroll:<selector>            bring a below-the-fold surface into the viewport
//   hover:<selector>             park the pointer on an element and measure its :hover state
//   close                        shut an open drawer/modal if there is one (no argument)
//   leaf:<nav label>             open a nav leaf, expanding the rail and categories first
//                                `Category/Label` where the label is not unique — six
//                                categories have an `Overview`, and an ambiguous one throws
//                                rather than silently opening the first (e.g. `leaf:Network/Overview`)
//   drawer:<px>                  drag the open drawer to a width in its own 360..1400 range
//
// `scroll:` and `leaf:` exist because this tool measures a RENDERED PIXEL, so anything below
// the fold or behind the nav could not be measured at all — it reported `outside the viewport`
// or died in a 30s "element is not visible" retry. Between them the app has no un-measurable
// screen left:
//   PREPARE='leaf:Deployments;click:.n-data-table-tbody tr' node scripts/contrast-check.mjs '.mini th'
//   PREPARE='scroll:.warn-table' node scripts/contrast-check.mjs '.warn-table .n-data-table-td'
//
// Prefix a step with `?` to skip it when its selector is not on screen. PREPARE runs once
// per theme, so a step that only applies the first time — signing in — otherwise stalls the
// second pass until it times out and takes the whole run with it:
//   PREPARE='?click:.bar-btn:has-text("Sign in");?fill:.n-modal input[type=password]=admin;…'
//
// Exit code is 1 if anything falls under the AA floor, so it can gate a change.
//
// Needs the shared Playwright install (see the global setup notes), not a local one:
//   NODE_PATH=$HOME/.local/lib/playwright/node_modules node scripts/contrast-check.mjs

import { createRequire } from 'module';

import { resizeDrawer, resolveLeaf, splitFill } from './lib/kw-playwright.mjs';

const require = createRequire(process.env.HOME + '/.local/lib/playwright/node_modules/');
const { chromium } = require('playwright');

const PORT = process.env.PORT || '8080';
const URL = process.env.URL || `http://localhost:${PORT}/`;
const AA_NORMAL = 4.5;
const AA_LARGE = 3.0;
// How far the decoded pixel and the DOM walk may differ, per channel, before it is worth
// printing. 1 is normal rounding noise (the verified three-layer case computed rgb(41,63,80)
// where the browser painted rgb(40,63,79)); anything past 2 means one of them is wrong.
const AGREE_TOLERANCE = 2;

// Text-bearing things whose colours have been wrong before, or are easy to get wrong.
const DEFAULT_SELECTORS = [
  '.leaf.active',
  // The Warnings card's tinted variant. It reported `not present` for its whole existence, and
  // only half of that was the styling's fault: `StatCard` applies `danger` when the cluster has
  // Warning events, and the simulator seeded none, so a cluster-free run could not reach it. It
  // can now. (A `.ov-card.warn` entry sat here too and was deleted rather than kept: no code
  // adds that class and no rule styles it, so it was not an unmeasured check — it was a
  // selector that could never match, which is the "permanently unmeasured row" this file's own
  // summary warns about, dressed as a pass.)
  '.ov-card.danger',
  '.nav-badge',
  // `.count` and `.acc-count` are the other two pills, and both are checked — in the scenes
  // below, on the list and the drawer where they actually render. They used to sit here, where
  // they could only ever report `not present`.
  // Plain .ov-card, not just its .danger variant: the variant matched a <div> and passed
  // while the clickable <button> cards sat at 1.34:1 in dark mode. Measure the base class.
  '.ov-card',
  '.ov-kind',
  '.ov-num',
  // The footer bar stays dark in BOTH themes while its text came from the active palette, so
  // the light theme painted light-theme greys on a dark bar (2.48:1 / 3.21:1, #244). A
  // surface whose colour does not follow the theme needs watching in the theme it does not
  // follow.
  '.app-footer .ver-line',
  '.app-footer .repo-link',
  // The secondary-text family that hard-coded slate-400 instead of --muted and failed the
  // LIGHT theme at 2.37:1 while looking correct in the dark one it was picked against (#265).
  // `.ov-scope-note` is on every cluster and category overview.
  '.ov-scope-note',
];

/**
 * Screens the default run walks to before sampling, each `{ prepare, selectors }`.
 *
 * A watchlist is only a watchlist for what the run can SEE. Everything the colour bugs of
 * #260/#264/#265 lived on — the drawer's chips, the read-only YAML tab, a cluster-scoped
 * list's pill, the diagnostics modal, a button's hover pad — sits behind a click, so adding
 * those selectors to `DEFAULT_SELECTORS` would have printed a column of `not present`: the
 * "green line with half the run unmeasured" this file's own summary warns about, which is
 * indistinguishable from a pass. Each scene carries the PREPARE that brings its surface on
 * screen, so `node scripts/contrast-check.mjs` with no arguments measures all of them.
 *
 * Scenes run in order, in each theme, from whatever the previous one left behind — hence the
 * leading `close` to shut a drawer or modal. Requires the simulator's fixtures
 * (annotations, a TLS Ingress); against a cluster without them those rows say `not present`,
 * which is honest and counted.
 */
const SCENES = [
  {
    // Pods rather than ConfigMaps: the same annotation chips, plus a Containers table for
    // `.mini th`, plus a YAML document with LISTS in it — without which the next scene's
    // `.yk-dash` is `not present` and the run is quietly one selector short.
    name: 'drawer chips',
    prepare:
      'close;leaf:Pods;click:.n-data-table-tbody tr;wait:800;' +
      '?click:.n-collapse-item__header:has-text("Annotations");wait:400',
    // `.n-tag` is the drawer's `<NTag type="info">Helm</NTag>`, which measured 3.36:1 in the
    // light theme on Naive's own info palette (#269) — a component-library colour rather than
    // one of ours, fixed in App.vue's `themeOverrides`. It is watched here because nothing
    // else in the app renders a Naive tag on a bespoke surface.
    selectors: ['.chip.subtle', '.acc-count', '.mini th', '.n-tag'],
  },
  {
    name: 'read-only YAML tab',
    prepare: '?click:.n-tabs-tab:has-text("YAML");wait:1200',
    // 1.42:1 in dark before #265: a fixed GitHub-LIGHT palette on a block that has a dark
    // override, so the whole tab was unreadable in the theme it was never checked in.
    selectors: ['.yaml .yk-key', '.yaml .yk-str', '.yaml .yk-num', '.yaml .yk-dash'],
  },
  {
    name: 'TLS chips on an Ingress',
    prepare: 'close;leaf:Ingresses;click:.n-data-table-tbody tr;wait:900',
    selectors: ['.chip'],
  },
  {
    // A resource LIST. `.count`, the status pill and the ACTIVE leaf's count badge only exist
    // here — on the overview the base pass samples they are all `not present`, so leaving them
    // in `DEFAULT_SELECTORS` bought a permanently-unmeasured row rather than a check.
    //
    // `.badge` was dropped from the watchlist here rather than moved: this file carried it
    // from the era when StatusBadge had its own class, and it has rendered a Naive `NTag`
    // since — so the entry had been matching nothing for months while reporting the same
    // `not present` a healthy cluster legitimately produces. Its live form is watched as
    // `.n-tag` in the drawer scene.
    //
    // The row-level STATUS pill is watched here as `.n-data-table-td .n-tag`. It was unwatched
    // for exactly as long as the simulator was perfectly healthy: no seeded pod ever reached a
    // state `StatusBadge` tones, so there was nothing to measure and a selector would have
    // reported `not present` forever. The seeder now puts about one pod in six into
    // CrashLoopBackOff, ImagePullBackOff, Pending, OOMKilled, Evicted or Completed, so the
    // tinted pill is on screen in a cluster-free run. Note this samples the first pill with
    // text, which on a name-sorted list is whichever tone happens to come first — it checks
    // that the pill family is readable, not that every tone is.
    name: 'resource list',
    prepare: 'close;leaf:Pods;wait:800',
    selectors: ['.count', '.leaf.active .nav-badge', '.n-data-table-td .n-tag'],
  },
  {
    // `.ns-note` is the "Cluster-scoped" pill, so it needs a cluster-scoped kind.
    name: 'cluster-scoped list',
    prepare: 'close;leaf:Namespaces;wait:800',
    selectors: ['.ns-note'],
  },
  {
    name: 'diagnostics modal',
    prepare: 'close;click:.bar-btn[title^="Diagnostics"];wait:900',
    selectors: ['.diag-dim', '.diag-detail', '.diag-kv dt', '.diag-caps li.off .diag-name', '.diag-warn'],
  },
  {
    // The editor dialog's Review Changes tab — the second diff (T1), which asks the cluster
    // what it WOULD store. Behind the admin login: its tabs are `v-if="!readonly"`, so a
    // signed-out run does not merely fail to measure them, it never renders them.
    name: 'editor review (signed in)',
    // Clicks the NAME cell, not the row. `ResourceTable`'s `rowProps` ignores a click whose
    // target is inside a checkbox, button, anchor or dropdown — so a row-centre click lands
    // on the Namespace LINK and filters the list instead of opening anything, and `td` alone
    // is the checkbox column. Both leave the drawer shut, and every following `?` step then
    // skips silently: the run reports `not present` for a surface it never navigated to.
    prepare:
      'close;signin:admin;leaf:Config Maps;wait:900;' +
      'click:.n-data-table-tbody tr td:nth-child(2);wait:1200;' +
      '?click:.n-tabs-tab:has-text("YAML");wait:1200;?click:button:has-text("Edit");wait:1800;' +
      '?click:.n-tabs-tab:has-text("Review Changes");wait:2500',
    selectors: ['.review-h', '.review-cap', '.review-recheck'],
  },
  {
    // A `:hover` pad, which nothing here could reach before the `hover:` verb: `.btn:hover`
    // hard-coded a light background and put the button's own label at 1.16:1 in dark mode.
    // Needs the pod file browser (`dev-run.sh --files`); without it the `.btn` is absent and
    // this row is counted as unmeasured rather than passed.
    name: 'button hover',
    prepare:
      'close;leaf:Pods;click:.n-data-table-tbody tr;wait:800;' +
      '?click:.n-tabs-tab:has-text("Files");wait:1200;?hover:.btn',
    selectors: ['.btn'],
  },
  {
    // The metrics chart's hover tooltip. Nothing here had ever hovered a chart point, so the
    // tooltip DOM did not exist during a run and these two selectors were unmeasurable: the
    // value rendered at 1.28:1 and the timestamp at 2.51:1 on the default dark theme, worse
    // than every failure this file's header records. The box is echarts' OWN container and
    // echarts inline-styles it, so a `.chart-tip` wrapper rule (which is what used to sit in
    // styles.css) can never reach it — its colours come from the chart option.
    //
    // What this scene CANNOT see is the chart itself: the axis labels and grid lines are
    // painted into a `<canvas>`, so there is no node to sample and no amount of scene-walking
    // will produce one. That half is guarded in the gate instead, by
    // `src/metric-chart-option.test.ts`, which fails if a `var(--…)` ever reaches the option.
    //
    // Needs a Prometheus / VictoriaMetrics backend. Without one MetricChart renders its
    // "Graphs need a …" placeholder, there is no canvas to hover, and these rows are counted
    // as unmeasured rather than passed.
    name: 'metrics chart tooltip',
    prepare: 'close;leaf:Cluster/Overview;wait:2000;?hover:.metric-echart;wait:800',
    selectors: ['.chart-tip-t', '.chart-tip-v'],
  },
];

const args = process.argv.slice(2);
const SELF_TEST = args.includes('--self-test');
const explicit = args.filter((a) => !a.startsWith('--'));
const selectors = explicit.length ? explicit : DEFAULT_SELECTORS;
// Named selectors, or an explicit PREPARE, mean "measure exactly this" — the scene walk would
// then navigate away from whatever the caller set up.
const scenes = explicit.length || process.env.PREPARE ? [] : SCENES;

const channel = (v) => {
  const c = v / 255;
  return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
};
const luminance = ([r, g, b]) => 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
const ratio = (a, b) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
};
// Colour channels as 0-255, whatever notation the browser returned.
//
// `getComputedStyle` does NOT always answer in `rgb()`. Naive UI's controls resolve to
// `color(srgb 0.890196 0.909804 0.92549 / 0.75)`, whose channels are 0-1 floats. Read as
// 0-255 those are near-black, so this reported a near-white label on a dark panel as a
// 1.42:1 FAIL (#245) — a false failure in a gating tool, which is worse than no tool,
// because it teaches people to ignore the real ones.
const parse = (s) => {
  const nums = (s || '').match(/[\d.]+/g)?.map(Number) ?? null;
  if (!nums || !/^\s*color\(/.test(s)) return nums;
  if (!/^\s*color\(\s*srgb\b/.test(s)) {
    // display-p3 and the rest need a real gamut conversion, not a scale factor. Refuse
    // rather than guess: a wrong number here is indistinguishable from a right one.
    throw new Error(`unsupported colour space, cannot measure: ${s}`);
  }
  // r g b are 0-1; a trailing alpha after `/` is already 0-1 and must not be scaled.
  return nums.map((n, i) => (i < 3 ? Math.round(n * 255) : n));
};
const rgbText = (c) => `rgb(${c[0]},${c[1]},${c[2]})`;
const agree = (a, b) => a && b && a.every((v, i) => Math.abs(v - b[i]) <= AGREE_TOLERANCE);

/** Sample one selector: its first match's colours, rects and DOM-walk backdrops. */
async function sampleSelector(page, sel) {
  return page
    .$$eval(sel, (els) => {
      // Same 0-1 vs 0-255 trap as the parser outside the page (#245).
      const nums = (c) => {
        const n = (c || '').match(/[\d.]+/g)?.map(Number) ?? null;
        if (!n || !/^\s*color\(/.test(c)) return n;
        if (!/^\s*color\(\s*srgb\b/.test(c)) return null; // unknown space: skip this layer
        return n.map((v, i) => (i < 3 ? Math.round(v * 255) : v));
      };
      // The DOM's opinion of the colour painted at this element's own box: its background
      // composited over every translucent ancestor down to the first opaque one.
      //
      // Kept only as a cross-check now (see the header): it is blind to anything a sibling
      // paints, which is how #250 happened. When it disagrees with the decoded pixel the
      // pixel wins, and the disagreement is printed.
      const painted = (el) => {
        const layers = [];
        let node = el;
        let base = [255, 255, 255];
        while (node) {
          const p = nums(getComputedStyle(node).backgroundColor);
          if (p && p.length >= 3) {
            const a = (p.length > 3) ? p[3] : 1;
            if (a >= 0.999) {
              base = p.slice(0, 3);
              break;
            }
            if (a > 0) layers.push([p.slice(0, 3), a]);
          }
          node = node.parentElement;
        }
        // Nearest ancestor is painted last, so apply the collected layers in reverse.
        let out = base;
        for (let i = layers.length - 1; i >= 0; i -= 1) {
          const [c, a] = layers[i];
          out = out.map((v, j) => Math.round(c[j] * a + v * (1 - a)));
        }
        return out;
      };
      // Where this element's own glyphs actually land, so the pixel sample is taken over the
      // run itself rather than over padding that a neighbouring layer happens to cover.
      // Falls back to the box inside the border edge when the element has no direct text of
      // its own — an <input>, whose value lives in a shadow tree, takes that path.
      const textRect = (el) => {
        const r = document.createRange();
        let box = null;
        for (const n of el.childNodes) {
          if (n.nodeType !== 3 || !n.textContent.trim()) continue;
          r.selectNodeContents(n);
          for (const cr of r.getClientRects()) {
            if (cr.width < 1 || cr.height < 1) continue;
            box = box
              ? {
                  x: Math.min(box.x, cr.x),
                  y: Math.min(box.y, cr.y),
                  r: Math.max(box.r, cr.right),
                  b: Math.max(box.b, cr.bottom),
                }
              : { x: cr.x, y: cr.y, r: cr.right, b: cr.bottom };
          }
        }
        if (box) return { x: box.x, y: box.y, w: box.r - box.x, h: box.b - box.y };
        const cs = getComputedStyle(el);
        const bb = el.getBoundingClientRect();
        const w = (v) => parseFloat(v) || 0;
        return {
          x: bb.x + w(cs.borderLeftWidth) + 1,
          y: bb.y + w(cs.borderTopWidth) + 1,
          w: bb.width - w(cs.borderLeftWidth) - w(cs.borderRightWidth) - 2,
          h: bb.height - w(cs.borderTopWidth) - w(cs.borderBottomWidth) - 2,
        };
      };
      // Only elements that paint glyphs THEMSELVES are measurable.
      //
      // `textContent` is recursive, so every wrapper on the way down used to be measured as
      // if it held the text its descendants hold. That is harmless while the wrapper and its
      // child share a backdrop, and actively wrong once they do not: with the backdrop read
      // off the screen, `.bar-filter`'s three wrappers sampled the white select box that is
      // painted over most of their box while carrying the top bar's inherited light colour,
      // and reported 1.17:1 for text that does not exist. (Same shape as `ui-measure`
      // calling a layout container a 229-chars-per-line defect.) A leaf with real text is
      // still always measured, so nothing is lost: colour reaches the glyphs through it.
      const hasOwnText = (el) =>
        Array.prototype.some.call(el.childNodes, (n) => n.nodeType === 3 && n.textContent.trim());
      // Reading the backdrop off the screen buys ground truth and costs a precondition the
      // DOM walk never had: the text has to BE on the screen. Both ways of failing that
      // turned up the first time this ran against an open drawer —
      //
      //   * off-viewport: a row's `⋮` buttons sat at x=1594 in a 1400px window, so the
      //     screenshot holds no pixels for them at all;
      //   * covered: the `+ Create` button was underneath the drawer panel, so the decoded
      //     pixel was the drawer's white and the button read 1.00:1.
      //
      // Neither is a contrast defect — nobody can see either element — and reporting them as
      // FAIL is the same false-alarm failure mode as #250. So an unmeasurable sample says so,
      // and is NOT quietly back-filled from the DOM walk, which would reintroduce exactly the
      // blindness this change removes.
      const unmeasurable = (el, r) => {
        if (!r || r.w < 1 || r.h < 1) return 'zero-sized text run';
        if (r.x < 0 || r.y < 0 || r.x + r.w > window.innerWidth || r.y + r.h > window.innerHeight) {
          return 'outside the viewport — scroll or widen it';
        }
        // Hit-test several points, not one: a single probe can land in the gap between two
        // glyphs of an inline child. "Related" means the element itself, an ancestor (what a
        // `pointer-events: none` badge resolves to) or a descendant.
        //
        // `pointer-events: none` first, though: such an element is PAINTED but can never be
        // returned by elementFromPoint, so the hit test answers a question it was not asked
        // and every one of them reads as "covered by another layer". That is how the metrics
        // chart's hover tooltip — echarts sets `pointer-events: none` on its container —
        // reported unmeasurable while sitting in plain sight at 1.28:1. Force the property
        // back on for the duration of the probe, so the answer is about occlusion again.
        const forced = [];
        for (let n = el; n && n !== document.documentElement; n = n.parentElement) {
          if (getComputedStyle(n).pointerEvents === 'none') {
            forced.push([n, n.style.pointerEvents]);
            n.style.pointerEvents = 'auto';
          }
        }
        const pts = [0.5, 0.15, 0.85].map((f) => [r.x + r.w * f, r.y + r.h / 2]);
        const seen = pts.some(([x, y]) => {
          const hit = document.elementFromPoint(x, y);
          return hit && (hit === el || el.contains(hit) || hit.contains(el));
        });
        forced.forEach(([n, prev]) => (n.style.pointerEvents = prev));
        return seen ? null : 'covered by another layer';
      };
      const of = (el, what) => {
        const rect = textRect(el);
        const skip = unmeasurable(el, rect);
        return { what, color: getComputedStyle(el).color, domBg: painted(el), rect: skip ? null : rect, skip };
      };
      const sample = (el) => {
        const cs = getComputedStyle(el);
        // The element's own text, plus any text-bearing descendant that sets its own
        // colour — a row can pass on its label and fail on its hint.
        const parts = hasOwnText(el) ? [of(el, '(self)')] : [];
        for (const kid of el.querySelectorAll('*')) {
          if (hasOwnText(kid)) parts.push(of(kid, kid.className || kid.tagName.toLowerCase()));
        }
        return { parts, font: cs.fontSize, weight: cs.fontWeight };
      };
      // First match that actually carries text, not simply the first match. `.n-data-table-th`
      // matches the selection-checkbox column first, which has no text at all, so a strict
      // "first match" reports the whole selector as unmeasurable while every visible header
      // beside it goes unchecked.
      const all = els.map(sample);
      return [all.find((s) => s.parts.length) ?? all[0]].filter(Boolean);
    })
    .catch(() => []);
}

/**
 * Ground truth: the modal colour actually painted under each rect, glyphs removed.
 *
 * One screenshot serves every rect in a pass, so this costs one capture per theme rather
 * than one per selector. Decoding happens in the page (canvas `getImageData`) so the script
 * needs no PNG library — and a data: URL does not taint the canvas.
 */
async function pixelBackdrops(page, rects) {
  const style = await page.addStyleTag({
    content: `*, *::before, *::after { color: transparent !important;
        -webkit-text-fill-color: transparent !important; text-shadow: none !important;
        caret-color: transparent !important; }
      input::placeholder, textarea::placeholder { color: transparent !important;
        -webkit-text-fill-color: transparent !important; }`,
  });
  let shot;
  try {
    shot = await page.screenshot();
  } finally {
    await style.evaluate((n) => n.remove()).catch(() => {});
  }
  const view = page.viewportSize();
  return page.evaluate(
    async ({ dataUrl, rects: rs, width }) => {
      const img = new Image();
      img.src = dataUrl;
      await img.decode();
      const cv = document.createElement('canvas');
      cv.width = img.width;
      cv.height = img.height;
      const ctx = cv.getContext('2d', { willReadFrequently: true });
      ctx.drawImage(img, 0, 0);
      const scale = img.width / width;
      return rs.map((r) => {
        if (!r) return null;
        const x0 = Math.max(0, Math.round(r.x * scale));
        const y0 = Math.max(0, Math.round(r.y * scale));
        const x1 = Math.min(img.width, Math.round((r.x + r.w) * scale));
        const y1 = Math.min(img.height, Math.round((r.y + r.h) * scale));
        if (x1 - x0 < 1 || y1 - y0 < 1) return null;
        const d = ctx.getImageData(x0, y0, x1 - x0, y1 - y0).data;
        // Mode, not mean and not one sample: a mean of a two-tone region is a colour that is
        // not on screen anywhere, and one sample is what a focus ring or an icon derails.
        const counts = new Map();
        let best = 0;
        let key = 0;
        for (let i = 0; i < d.length; i += 4) {
          const k = (d[i] << 16) | (d[i + 1] << 8) | d[i + 2];
          const n = (counts.get(k) || 0) + 1;
          counts.set(k, n);
          if (n > best) {
            best = n;
            key = k;
          }
        }
        const total = d.length / 4;
        return { rgb: [(key >> 16) & 255, (key >> 8) & 255, key & 255], share: best / total };
      });
    },
    { dataUrl: `data:image/png;base64,${shot.toString('base64')}`, rects, width: view.width },
  );
}

/** What a step waits for, so an optional one can be tested before it is run. */
const selectorOf = (verb, arg) => ((verb === 'fill' || verb === 'upload') ? splitFill(arg).selector : arg);

/**
 * Make the nav clickable before PREPARE walks it.
 *
 * The rail collapses (#237) and each category is a `<details>` whose open state is remembered
 * in prefs, so a `click:.leaf-label…` step can resolve its element and then spend the whole
 * timeout on "element is not visible". That reads as a renamed leaf and sends you to the nav
 * registry; the only problem is a shut parent. `openLeaf` in lib/kw-playwright.mjs carries the
 * same fix — this file keeps its own copy on purpose (it is the instrument that has caught the
 * real colour defects, and sharing it with a helper that changes is how it stops being one).
 */
async function openNav(page) {
  const tile = await page.$('.tile-nav'); // rendered only while the nav is collapsed
  if (tile) {
    await tile.click().catch(() => {});
    await page.waitForTimeout(300);
  }
  await page.evaluate(() => {
    for (const d of document.querySelectorAll('details.group')) {
      d.open = true;
    }
  });
}

/**
 * Open a nav leaf by label, expanding the rail and every category first.
 *
 * Until this existed, NOTHING behind the nav could have its colour measured here: the app has
 * no deep links (every route is in-page state), `ui-shot`/`ui-measure` reach a list with
 * `--leaf`, and this tool had only PREPARE — whose `click:` cannot see a leaf inside a
 * collapsed category (#237) and dies after a 30s "element is not visible" retry loop. So every
 * list, drawer and detail surface in the app was un-measurable for contrast, which is exactly
 * the set of screens colour bugs have shipped in.
 */
async function openLeafHere(page, label) {
  const rail = page.locator('.tile-nav');
  if (await rail.isVisible().catch(() => false)) {
    await rail.click().catch(() => {});
    await page.waitForTimeout(300);
  }
  // Only the CLOSED categories: clicking every summary TOGGLES, so on the normal case (all
  // open) it shuts them and the leaf stops being clickable.
  const closed = page.locator('.group:not([open]) > summary');
  for (let i = 0, n = await closed.count(); i < n; i += 1) {
    await closed
      .nth(i)
      .click()
      .catch(() => {});
    await page.waitForTimeout(60);
  }
  // Refuse to guess between same-named leaves. SIX categories have an `Overview`, and this
  // took `.first()`: `leaf:Overview`, meant to reach the Network overview, opened the CLUSTER
  // one instead. Both render `.ov-scope-note` and `.ov-truncated`, so the run returned real
  // numbers for the wrong page — the failure mode that looks exactly like a clean pass. The
  // resolver lives in lib/kw-playwright.mjs so the two walkers cannot disagree about it.
  const leaf = await resolveLeaf(page, label);
  await leaf.click({ timeout: 5000 });
  await page.waitForSelector('.n-data-table-tbody tr, .empty, .ov-scope-note', { timeout: 10000 }).catch(() => {});
  await page.waitForTimeout(500);
}

async function runPrepare(page, spec) {
  for (const raw of (spec || '').split(';').map((s) => s.trim()).filter(Boolean)) {
    const optional = raw.startsWith('?');
    const step = optional ? raw.slice(1) : raw;
    const [verb, ...rest] = step.split(':');
    const arg = rest.join(':');
    if (optional && verb === 'press') throw new Error('? needs a selector, so it cannot mark a press step');
    // `?step` is skipped when its target is not on screen. PREPARE runs once per theme, and
    // a step like signing in only applies the first time — without this the second pass sits
    // waiting for a modal that is already dealt with, and the run dies on a timeout.
    if (optional && !(await page.$(selectorOf(verb, arg)))) continue;
    if (verb === 'press') await page.keyboard.press(arg);
    else if (verb === 'click') await page.click(arg);
    else if (verb === 'wait') await page.waitForTimeout(Number(arg));
    // Below the fold is not the same as absent. This tool refuses to sample an element that
    // is off screen — rightly, since the backdrop comes from a rendered pixel — so a surface
    // like the overview's Warnings table (y≈1220 in a 900px viewport) reported `outside the
    // viewport` for every selector, which is a failed run wearing a caveat (#257).
    else if (verb === 'scroll') await page.locator(arg).first().scrollIntoViewIfNeeded();
    // A `:hover` rule was unreachable here, and hover backgrounds are exactly where a
    // one-theme literal survives: `.btn:hover` hard-coded `#f0f4f7` with no dark override, so
    // in dark mode a button's `var(--text)` label sat on a near-white pad at 1.13:1 — on every
    // button in the app. The pointer stays parked, so the state holds through the backdrop
    // screenshot as well as the computed-style read.
    else if (verb === 'hover') await page.locator(arg).first().hover();
    // Signing in, as ONE verb rather than a four-step optional incantation.
    //
    // Until this existed, no surface behind the admin login could have its colour measured
    // here at all — most visibly the YAML editor's own dialog, whose Form / Warnings /
    // Review tabs are `v-if="!readonly"` and therefore simply absent to a signed-out run.
    // Open-mode makes that easy to miss: the page renders, the drawer opens, and only the
    // editing surfaces are quietly gone, so a run looks complete while never having seen
    // them. Idempotent by construction — if the Sign in link is not there we are already in.
    else if (verb === 'signin') {
      const link = page.locator('.bar-btn:has-text("Sign in")').first();
      if (await link.isVisible().catch(() => false)) {
        await link.click();
        await page.waitForTimeout(400);
        const inputs = page.locator('.n-modal input');
        await inputs.nth(0).fill(arg || 'admin');
        await inputs.nth(1).fill(arg || 'admin');
        await page.getByRole('button', { name: /^sign/i }).last().click();
        await page.waitForTimeout(1800);
      }
    }
    // `close` (no argument) shuts an open drawer or modal if there is one. `?press:Escape`
    // cannot express this — `?` needs a selector to test — and an unconditional Escape is not
    // safe either, since the app also uses it to leave surfaces the caller may have opened on
    // purpose. Scenes need it because each one starts from wherever the previous one stopped,
    // and a Naive modal mask silently intercepts the next click.
    else if (verb === 'close') {
      const open = await page
        .locator('.n-modal-mask, .n-drawer')
        .first()
        .isVisible()
        .catch(() => false);
      if (open) {
        await page.keyboard.press('Escape');
        await page.waitForTimeout(300);
      }
    } else if (verb === 'leaf') await openLeafHere(page, arg);
    // `drawer:<px>` drags the open drawer to a width inside its own 360..1400 resize range.
    // Shared with the other runner rather than copied — the rule this file's history keeps
    // teaching is that two copies of a walker drift and then disagree silently (#278).
    else if (verb === 'drawer') await resizeDrawer(page, Number(arg));
    else if (verb === 'fill') {
      // splitFill, not lastIndexOf('='): the list header's filter box takes `app=web`, and
      // the old split moved half the VALUE into the selector (see lib/kw-playwright.mjs).
      const { selector, value } = splitFill(arg);
      await page.fill(selector, value);
    } else if (verb === 'upload') {
      // A file input, for UI that only appears once something has been picked — the pod
      // file browser's upload confirmation, for one, which is otherwise unreachable here.
      const { selector, value } = splitFill(arg);
      await page.setInputFiles(selector, value);
    } else throw new Error(`unknown PREPARE verb: ${verb}`);
    await page.waitForTimeout(250);
  }
}

/**
 * Measure every selector once, on whatever the page currently shows.
 *
 * Returns `{ rows, disagreements }`. A row's `bg` is the decoded pixel wherever one could be
 * taken; `domBg` is the walk's answer, kept so the two can be compared.
 */
async function measure(page, theme, sels) {
  const rows = [];
  const disagreements = [];
  const found = [];
  for (const sel of sels) {
    const samples = await sampleSelector(page, sel);
    if (!samples.length) {
      rows.push({ theme, sel, what: '—', r: null, note: 'not present' });
      continue;
    }
    // Present but painting no glyphs is still nothing measured, and must say so rather than
    // pass silently — the same rule as `not present`.
    if (!samples[0].parts.length) {
      rows.push({ theme, sel, what: '—', r: null, note: 'present, but no text of its own' });
      continue;
    }
    found.push({ sel, s: samples[0] });
  }
  const flat = found.flatMap((f) => f.s.parts);
  const pixels = flat.length ? await pixelBackdrops(page, flat.map((p) => p.rect)) : [];

  let i = 0;
  for (const { sel, s } of found) {
    // WCAG's large-text allowance: >=18.66px bold, or >=24px.
    const px = parseFloat(s.font);
    const bold = Number(s.weight) >= 700;
    const floor = px >= 24 || (bold && px >= 18.66) ? AA_LARGE : AA_NORMAL;
    for (const p of s.parts) {
      const pixel = pixels[i];
      i += 1;
      const fg = parse(p.color);
      if (!fg) continue;
      // No pixel means nothing was measured. Say so; never fall back to the DOM walk.
      if (!pixel) {
        rows.push({ theme, sel, what: p.what, r: null, note: p.skip || 'no pixel could be sampled' });
        continue;
      }
      const bg = pixel.rgb;
      const row = {
        theme,
        sel,
        what: p.what,
        r: ratio(fg, bg),
        floor,
        bg: rgbText(bg),
        bgRgb: bg,
        src: 'pixel',
      };
      rows.push(row);
      if (!agree(pixel.rgb, p.domBg)) {
        disagreements.push({
          theme,
          sel,
          what: p.what,
          pixel: rgbText(pixel.rgb),
          dom: rgbText(p.domBg),
          domRatio: ratio(fg, p.domBg),
          r: row.r,
          share: pixel.share,
        });
      }
    }
  }
  return { rows, disagreements };
}

/**
 * Positive controls — cases whose answers are known before the tool is run.
 *
 * "Beware the oracle": every wrong conclusion in this project came from a broken instrument,
 * not broken maths, and this instrument has now been wrong three times (#245, #250, and the
 * 8.12:1-reported-as-1.78:1 stack before them). So the fix ships with a fixture whose right
 * answers are arithmetic, including the exact sibling-paint shape that #250 was about and
 * the glyph trap that a naive point sample falls into.
 */
const FIXTURE = `
  <style>
    body { margin: 0; font: 14px/1.4 sans-serif; }
    .bar { background: rgb(27,42,51); padding: 12px; }
    #opaque { background: rgb(27,42,51); color: rgb(232,238,242); padding: 8px; }
    .panel { background: rgb(255,255,255); padding: 8px; }
    #tint { background: rgba(10,122,194,0.14); color: rgb(51,54,57); padding: 8px; }
    #l1 { background: rgba(10,122,194,0.14); padding: 8px; }
    #l2 { background: rgba(10,122,194,0.14); padding: 8px; }
    #stack { color: rgb(232,238,242); }
    /* The #250 shape: the white box is a SIBLING of the text, painted underneath it. */
    .sel { position: relative; width: 220px; height: 30px; }
    .sel .label { position: absolute; inset: 0; background: rgb(255,255,255); }
    .sel .input { position: absolute; inset: 0; color: rgb(51,54,57); padding: 6px; }
    /* The glyph trap: dense black text on white, where a centre-point sample lands on ink. */
    #glyphs { background: rgb(255,255,255); color: rgb(0,0,0); font: 900 22px/1 sans-serif;
              width: 200px; }
    /* Nothing to measure: covered by an opaque layer, and parked off the viewport. */
    .cover { position: relative; width: 200px; height: 24px; }
    .cover .under { position: absolute; inset: 0; background: rgb(255,255,255); color: rgb(0,0,0); }
    .cover .over { position: absolute; inset: 0; background: rgb(27,42,51); }
    #offscreen { position: absolute; left: 3000px; top: 0; color: rgb(0,0,0); }
    /* Painted, in plain sight, but invisible to elementFromPoint — echarts' tooltip. */
    .ghost { position: relative; width: 200px; height: 24px; background: rgb(27,42,51); }
    .ghost .layer { position: absolute; inset: 0; background: rgb(255,255,255); color: rgb(0,0,0);
                    pointer-events: none; }
  </style>
  <div class="bar">
    <div id="opaque">Opaque</div>
    <div class="panel"><div id="tint">Tint over panel</div></div>
    <div id="l1"><div id="l2"><div id="stack">Three layers</div></div></div>
    <div class="sel"><div class="label"></div><div class="input">sibling-painted</div></div>
    <div id="glyphs">MMMMMMMM</div>
    <div class="cover"><div class="under">hidden under</div><div class="over"></div></div>
    <div id="offscreen">parked off-screen</div>
    <div class="ghost"><div class="layer">pointer-events none</div></div>
  </div>
`;

// Expected backdrops, by arithmetic. `over` composites src at alpha a onto dst.
const over = (src, a, dst) => src.map((c, i) => Math.round(c * a + dst[i] * (1 - a)));
const TINT = [10, 122, 194];
const WHITE = [255, 255, 255];
const BAR = [27, 42, 51];
const CONTROLS = [
  { sel: '#opaque', want: BAR, why: 'plain opaque element' },
  { sel: '#tint', want: over(TINT, 0.14, WHITE), why: 'translucent tint over an opaque panel' },
  { sel: '#stack', want: over(TINT, 0.14, over(TINT, 0.14, BAR)), why: 'three-layer stack' },
  { sel: '.sel .input', want: WHITE, why: 'sibling-painted backdrop (#250)', domMustDiffer: true },
  { sel: '#glyphs', want: WHITE, why: 'dense glyphs — sample must not read the ink' },
  // The two ways the pixel path can have nothing to read. Both must decline to answer rather
  // than report the colour they happen to find there.
  { sel: '.cover .under', wantNote: 'covered by another layer', why: 'covered — must not measure the layer on top' },
  { sel: '#offscreen', wantNote: 'outside the viewport', why: 'off-viewport — must not measure' },
  // ...and the control that must NOT fire: an element that is painted on top but has
  // `pointer-events: none`, so elementFromPoint can never return it. Every metrics-chart
  // tooltip sample reported "covered by another layer" until the hit test forced the property
  // back on — a whole surface reading as unmeasurable while visibly failing at 1.28:1.
  { sel: '.ghost .layer', want: WHITE, why: 'pointer-events:none but painted — must be measured' },
];

async function selfTest(page) {
  await page.setContent(FIXTURE);
  await page.waitForTimeout(200);
  const { rows, disagreements } = await measure(page, 'control', CONTROLS.map((c) => c.sel));
  let bad = 0;
  // Expected values are computed by compositing each layer and rounding, which is not
  // bit-identical to what the compositor does — the verified three-layer case in this
  // project's history computed rgb(41,63,80) where the browser painted rgb(40,63,79). So the
  // controls assert agreement to within AGREE_TOLERANCE, and print the delta. A tolerance
  // wide enough to hide a real defect would be several channels, not two.
  console.log('positive controls (expected values are arithmetic, not observations)\n');
  for (const c of CONTROLS) {
    const row = rows.find((r) => r.sel === c.sel && r.what === '(self)');
    const dis = disagreements.find((d) => d.sel === c.sel && d.what === '(self)');
    if (c.wantNote) {
      const ok = row?.r === null && String(row?.note || '').startsWith(c.wantNote);
      if (!ok) bad += 1;
      console.log(
        `${ok ? 'ok  ' : 'FAIL'}  ${c.sel.padEnd(14)} want note "${c.wantNote}"` +
          ` got ${row ? (row.r === null ? `"${row.note}"` : `${row.r.toFixed(2)}:1`) : 'nothing'}  ${c.why}`,
      );
      continue;
    }
    const got = row?.bgRgb;
    const ok = agree(got, c.want) && row?.src === 'pixel' && (!c.domMustDiffer || Boolean(dis));
    if (!ok) bad += 1;
    const delta = got ? Math.max(...got.map((v, i) => Math.abs(v - c.want[i]))) : '—';
    console.log(
      `${ok ? 'ok  ' : 'FAIL'}  ${c.sel.padEnd(14)} want ${rgbText(c.want).padEnd(18)}` +
        ` got ${String(got ? rgbText(got) : 'none').padEnd(18)} d=${delta}  ${c.why}` +
        (dis ? `   [dom walk said ${dis.dom}]` : ''),
    );
  }
  // The three-layer stack is the case the previous fix was verified against: the DOM walk
  // and the browser agreed to within a rounding step. If that stops holding, the walk broke.
  const stack = disagreements.find((d) => d.sel === '#stack');
  if (stack) {
    bad += 1;
    console.log(`FAIL  #stack: dom walk ${stack.dom} vs pixel ${stack.pixel} — the ancestor walk regressed`);
  } else {
    console.log(`ok    #stack   dom walk and decoded pixel agree within ${AGREE_TOLERANCE} (the verified case)`);
  }
  console.log(bad ? `\n${bad} control(s) failed.` : '\nAll positive controls hold.');
  return bad;
}

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });

if (SELF_TEST) {
  const bad = await selfTest(page);
  await browser.close();
  process.exit(bad ? 1 : 0);
}

await page.goto(URL, { waitUntil: 'networkidle' });
await page.waitForTimeout(1200);

const rows = [];
const disagreements = [];
let worst = { r: Infinity };

for (let pass = 0; pass < 2; pass++) {
  if (pass === 1) {
    // Dismiss whatever pass 0's PREPARE opened before reaching for the toggle. PREPARE runs
    // INSIDE this loop, so a spec like `press:Control+k` leaves a modal mask over the whole
    // shell — and Naive's mask intercepts the click on `.theme-toggle`. An earlier version
    // did not do this, and checking the command palette's own colours (the surface that has
    // been got wrong twice, #200) died on the dark pass with a 30-second timeout whose
    // message named the toggle rather than the modal actually in the way. Escape is what the
    // app binds to close the palette and the drawer, so this is the reader's own exit.
    if (
      await page
        .locator('.n-modal-mask, .n-drawer-mask')
        .first()
        .isVisible()
        .catch(() => false)
    ) {
      await page.keyboard.press('Escape');
      await page.waitForTimeout(300);
    }
    await page.click('.theme-toggle');
    await page.waitForTimeout(700);
  }
  // Read the theme from the DOM rather than assuming the toggle order — the app
  // remembers the last theme, so pass 0 is not reliably "light".
  const theme = (await page.evaluate(() => document.documentElement.classList.contains('kw-dark')))
    ? 'dark'
    : 'light';

  await openNav(page);
  await runPrepare(page, process.env.PREPARE);

  // The shell watchlist is measured on the cluster overview. Pass 1 inherits whatever pass 0's
  // last scene left on screen, so without this the dark pass would sample the base selectors
  // on a resource list and report `not present` for half of them — the two themes measuring
  // different pages, which is worse than measuring neither.
  if (scenes.length) await runPrepare(page, 'close;leaf:Cluster/Overview;wait:800');

  const out = await measure(page, theme, selectors);
  rows.push(...out.rows);
  disagreements.push(...out.disagreements);
  for (const r of out.rows) if (r.r !== null && r.r < worst.r) worst = r;

  // Then walk the scenes, in this same theme. A scene that cannot be reached (a leaf this
  // cluster does not have, a modal that failed to open) must not take the run down with it —
  // its selectors then report `not present` and are counted as unmeasured, which is the
  // truthful outcome and is visible in the summary.
  for (const scene of scenes) {
    try {
      await runPrepare(page, scene.prepare);
    } catch (e) {
      console.error(`scene "${scene.name}" could not be reached: ${e.message.split('\n')[0]}`);
    }
    const s = await measure(page, theme, scene.selectors);
    rows.push(...s.rows);
    disagreements.push(...s.disagreements);
    for (const r of s.rows) if (r.r !== null && r.r < worst.r) worst = r;
  }
}

const pad = (s, n) => String(s).padEnd(n);
console.log(pad('theme', 6) + pad('selector', 26) + pad('part', 18) + pad('ratio', 9) + 'verdict');
let failures = 0;
let unmeasured = 0;
for (const r of rows) {
  if (r.r === null) {
    unmeasured += 1;
    console.log(pad(r.theme, 6) + pad(r.sel, 26) + pad(r.what, 18) + pad('—', 9) + r.note);
    continue;
  }
  const ok = r.r >= r.floor;
  if (!ok) failures += 1;
  console.log(
    pad(r.theme, 6) +
      pad(r.sel, 26) +
      pad(String(r.what).slice(0, 17), 18) +
      pad(r.r.toFixed(2) + ':1', 9) +
      (ok ? `pass (>=${r.floor})` : `FAIL (<${r.floor})  on ${r.bg}`),
  );
}

// Not a failure, but always worth printing: where the two methods disagree is where this
// instrument has historically been wrong, and it is how #250 was found in the first place.
if (disagreements.length) {
  console.log(`\n${disagreements.length} backdrop disagreement(s) — decoded pixel wins, DOM walk shown for comparison:`);
  for (const d of disagreements) {
    console.log(
      `  ${pad(d.theme, 6)}${pad(d.sel, 26)}${pad(String(d.what).slice(0, 17), 18)}` +
        `pixel ${pad(d.pixel, 17)}${d.r.toFixed(2)}:1   dom ${pad(d.dom, 17)}${d.domRatio.toFixed(2)}:1` +
        (d.share < 0.5 ? `   (mixed backdrop, mode covers ${(d.share * 100).toFixed(0)}%)` : ''),
    );
  }
}

// "All measured text" is doing a lot of work in that sentence, so say how much was NOT
// measured. Rows that report a note carry no ratio and therefore cannot fail, which means a
// run that silently stopped measuring things exits 0 and looks identical to a clean one —
// exactly the trap the `not present` rule warns about, and now countable rather than
// something you have to notice by scrolling. Deliberately not an automatic exit 1: a
// healthy cluster genuinely never renders `.ov-card.danger`, and a checker that cries wolf
// on that gets switched off.
console.log(
  failures
    ? `\n${failures} below the floor. Worst: ${worst.sel} ${worst.what} at ${worst.r.toFixed(2)}:1 (${worst.theme}).`
    : `\nAll measured text clears its floor in both themes (${rows.length - unmeasured} of ${rows.length} samples measured).`,
);
if (unmeasured) {
  console.log(
    `${unmeasured} sample(s) produced no ratio — NOT passes. If that is most of the run, ` +
      'the run failed: bring the surfaces on screen with PREPARE and measure again.',
  );
}

await browser.close();
process.exit(failures ? 1 : 0);

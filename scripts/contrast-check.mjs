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
//
// Prefix a step with `?` to skip it when its selector is not on screen. PREPARE runs once
// per theme, so a step that only applies the first time — signing in — otherwise stalls the
// second pass until it times out and takes the whole run with it:
//   PREPARE='?click:.linkbtn:has-text("Sign in");?fill:.n-modal input[type=password]=admin;…'
//
// Exit code is 1 if anything falls under the AA floor, so it can gate a change.
//
// Needs the shared Playwright install (see the global setup notes), not a local one:
//   NODE_PATH=$HOME/.local/lib/playwright/node_modules node scripts/contrast-check.mjs

import { createRequire } from 'module';

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
  '.leaf.active .nav-badge',
  '.ov-card.danger',
  '.ov-card.warn',
  '.badge',
  '.nav-badge',
  // The count pills all hardcode a light background and need a dark override each. `.count`
  // was missing one and rendered at 2.13:1 in dark mode; the other two are here so the next
  // omission is caught by the tool rather than by a person noticing.
  '.count',
  '.acc-count',
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
];

const args = process.argv.slice(2);
const SELF_TEST = args.includes('--self-test');
const selectors = args.filter((a) => !a.startsWith('--')).length
  ? args.filter((a) => !a.startsWith('--'))
  : DEFAULT_SELECTORS;

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
        const pts = [0.5, 0.15, 0.85].map((f) => [r.x + r.w * f, r.y + r.h / 2]);
        const seen = pts.some(([x, y]) => {
          const hit = document.elementFromPoint(x, y);
          return hit && (hit === el || el.contains(hit) || hit.contains(el));
        });
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
const selectorOf = (verb, arg) =>
  (verb === 'fill' || verb === 'upload') ? arg.slice(0, arg.lastIndexOf('=')) : arg;

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
    else if (verb === 'fill') {
      const at = arg.lastIndexOf('=');
      await page.fill(arg.slice(0, at), arg.slice(at + 1));
    } else if (verb === 'upload') {
      // A file input, for UI that only appears once something has been picked — the pod
      // file browser's upload confirmation, for one, which is otherwise unreachable here.
      const at = arg.lastIndexOf('=');
      await page.setInputFiles(arg.slice(0, at), arg.slice(at + 1));
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
  </style>
  <div class="bar">
    <div id="opaque">Opaque</div>
    <div class="panel"><div id="tint">Tint over panel</div></div>
    <div id="l1"><div id="l2"><div id="stack">Three layers</div></div></div>
    <div class="sel"><div class="label"></div><div class="input">sibling-painted</div></div>
    <div id="glyphs">MMMMMMMM</div>
    <div class="cover"><div class="under">hidden under</div><div class="over"></div></div>
    <div id="offscreen">parked off-screen</div>
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

  const out = await measure(page, theme, selectors);
  rows.push(...out.rows);
  disagreements.push(...out.disagreements);
  for (const r of out.rows) if (r.r !== null && r.r < worst.r) worst = r;
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

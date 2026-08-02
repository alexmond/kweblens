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
// It composites translucent backgrounds over what is underneath them, which is the step
// that hand-calculation usually gets wrong: a `rgba(10,122,194,0.14)` row over a white
// panel is not the same colour as that rgba value.
//
// Usage:
//   scripts/dev-run.sh
//   node scripts/contrast-check.mjs                      # the default watchlist below
//   node scripts/contrast-check.mjs '.leaf.active' '.ov-card.danger'
//   PORT=8085 node scripts/contrast-check.mjs
//   PREPARE='press:Control+k' node scripts/contrast-check.mjs '.palette-row.active'
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
];

const selectors = process.argv.slice(2).length ? process.argv.slice(2) : DEFAULT_SELECTORS;

const channel = (v) => {
  const c = v / 255;
  return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
};
const luminance = ([r, g, b]) => 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
const ratio = (a, b) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
};
const parse = (s) => (s || '').match(/[\d.]+/g)?.map(Number) ?? null;
const alphaOf = (s) => {
  const p = parse(s);
  return p && p.length > 3 ? p[3] : 1;
};
const composite = (fg, a, bg) => fg.slice(0, 3).map((c, i) => Math.round(c * a + bg[i] * (1 - a)));

/** What a step waits for, so an optional one can be tested before it is run. */
const selectorOf = (verb, arg) =>
  (verb === 'fill' || verb === 'upload') ? arg.slice(0, arg.lastIndexOf('=')) : arg;

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

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
await page.goto(URL, { waitUntil: 'networkidle' });
await page.waitForTimeout(1200);

const rows = [];
let worst = { r: Infinity };

for (let pass = 0; pass < 2; pass++) {
  if (pass === 1) {
    await page.click('.theme-toggle');
    await page.waitForTimeout(700);
  }
  // Read the theme from the DOM rather than assuming the toggle order — the app
  // remembers the last theme, so pass 0 is not reliably "light".
  const theme = (await page.evaluate(() => document.documentElement.classList.contains('kw-dark')))
    ? 'dark'
    : 'light';

  await runPrepare(page, process.env.PREPARE);

  for (const sel of selectors) {
    const samples = await page
      .$$eval(
        sel,
        (els) => {
          // What is actually painted behind the element, as an OPAQUE colour.
          //
          // This used to return the first non-transparent ancestor and stop. When that
          // ancestor was itself translucent — a tinted panel inside a tinted panel — the
          // caller composited against a colour that was never on screen, and the verdict
          // could be far off: a real 8.12:1 was reported as 1.78:1. A checker that fails
          // things that are fine is worse than none, because people learn to ignore it.
          //
          // So: collect every translucent layer up to the first opaque one, then composite
          // them bottom-up, which is what the browser does.
          const behind = (el) => {
            const nums = (c) => (c || '').match(/[\d.]+/g)?.map(Number) ?? null;
            const layers = [];
            let node = el.parentElement;
            let base = [255, 255, 255];
            while (node) {
              const p = nums(getComputedStyle(node).backgroundColor);
              if (p && p.length >= 3) {
                const a = (p.length > 3) ? p[3] : 1;
                if (a >= 0.999) {
                  base = p.slice(0, 3);
                  break;
                }
                if (a > 0) {
                  layers.push([p.slice(0, 3), a]);
                }
              }
              node = node.parentElement;
            }
            // Nearest ancestor is painted last, so apply the collected layers in reverse.
            let out = base;
            for (let i = layers.length - 1; i >= 0; i -= 1) {
              const [c, a] = layers[i];
              out = out.map((v, j) => Math.round(c[j] * a + v * (1 - a)));
            }
            return `rgb(${out[0]}, ${out[1]}, ${out[2]})`;
          };
          return els.slice(0, 1).map((el) => {
            const cs = getComputedStyle(el);
            // Sample the element's own text, plus any text-bearing descendants that set
            // their own colour — a row can pass on its label and fail on its hint.
            const parts = [{ what: '(self)', color: cs.color }];
            for (const kid of el.querySelectorAll('*')) {
              if (kid.textContent.trim()) {
                parts.push({ what: kid.className || kid.tagName.toLowerCase(), color: getComputedStyle(kid).color });
              }
            }
            return { bg: cs.backgroundColor, behind: behind(el), parts, font: cs.fontSize, weight: cs.fontWeight };
          });
        })
      .catch(() => []);

    if (!samples.length) {
      rows.push({ theme, sel, what: '—', r: null, note: 'not present' });
      continue;
    }
    const s = samples[0];
    const a = alphaOf(s.bg);
    const bg = a < 1 ? composite(parse(s.bg), a, parse(s.behind)) : parse(s.bg);
    // WCAG's large-text allowance: >=18.66px bold, or >=24px.
    const px = parseFloat(s.font);
    const bold = Number(s.weight) >= 700;
    const floor = px >= 24 || (bold && px >= 18.66) ? AA_LARGE : AA_NORMAL;

    for (const p of s.parts) {
      const fg = parse(p.color);
      if (!fg) continue;
      const r = ratio(fg, bg);
      const row = { theme, sel, what: p.what, r, floor, bg: `rgb(${bg})` };
      rows.push(row);
      if (r < worst.r) worst = row;
    }
  }
}

const pad = (s, n) => String(s).padEnd(n);
console.log(pad('theme', 6) + pad('selector', 26) + pad('part', 18) + pad('ratio', 9) + 'verdict');
let failures = 0;
for (const r of rows) {
  if (r.r === null) {
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

console.log(
  failures
    ? `\n${failures} below the floor. Worst: ${worst.sel} ${worst.what} at ${worst.r.toFixed(2)}:1 (${worst.theme}).`
    : '\nAll measured text clears its floor in both themes.',
);

await browser.close();
process.exit(failures ? 1 : 0);

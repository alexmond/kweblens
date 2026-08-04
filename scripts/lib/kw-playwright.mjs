/**
 * Shared Playwright helpers for driving a running kweblens.
 *
 * Everything in here was duplicated across `contrast-check.mjs` and `perf-sweep.mjs`
 * first, and diverged: the sign-in flow existed twice with different waits, and the two
 * scripts disagreed about the SPA's base path. This is the one copy. Those two scripts
 * are deliberately NOT retrofitted onto it — they work, they are the tools that caught
 * real defects, and rewriting a working measuring instrument to tidy it is how you get a
 * checker you can no longer trust. New scripts start here.
 *
 * The account-wide Playwright install is resolved through NODE_PATH, which only CJS
 * `require` honours — hence `createRequire` rather than a bare import. Never `npm i
 * playwright` into this repo; the browser build has to match the shared install.
 *
 *   export NODE_PATH="${NODE_PATH:-$HOME/.local/lib/playwright/node_modules}"
 */
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

export const PORT = process.env.PORT ?? '8080';
// `/` is the SPA. `/ui/` also answers 200 because the SPA controller forwards unknown
// paths, so a wrong base path does NOT fail loudly — it silently serves the shell and
// every later selector reports "not present". Default to the canonical one.
export const BASE_URL = process.env.BASE_URL ?? `http://localhost:${PORT}/`;
export const USER = process.env.KWEBLENS_USER ?? 'admin';
export const PASS = process.env.KWEBLENS_PASS ?? 'admin';

/**
 * Named viewports. Named, not numeric, so a finding says "at `narrow`" and the next
 * person reproduces it exactly.
 *
 * `wide` is 1900 because every screenshot in this project was taken at ~1400 for weeks
 * and a 338-character prose line survived all of them (#235). If you only ever look at
 * one width you will only ever find that width's bugs.
 */
export const VIEWPORTS = {
  narrow: { width: 1024, height: 768 },
  normal: { width: 1400, height: 900 },
  wide: { width: 1900, height: 1000 },
};

export function viewport(name) {
  // A bare number is an ESCAPE HATCH for one question the three names cannot answer: "at what
  // width does this stop working?" (#234 asks for that number and it is not 1024, 1400 or
  // 1900). Findings at a named width stay the norm — a number in a report is reproducible
  // only if it is written down, which is what the names are for — so sweep with a number,
  // then re-state the finding at the nearest name.
  if (/^\d+$/.test(String(name))) return { width: Number(name), height: 900 };
  const v = VIEWPORTS[name];
  if (!v) throw new Error(`unknown viewport '${name}' — one of: ${Object.keys(VIEWPORTS).join(', ')}, or a width in px`);
  return v;
}

/**
 * Launch and land on a signed-in page at the requested viewport.
 *
 * Returns `{ browser, page }`; the caller closes the browser.
 */
export async function open({ view = 'normal', url = BASE_URL, signedIn = true } = {}) {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: viewport(view) });
  await page.goto(url, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);
  if (signedIn) await signIn(page);
  return { browser, page };
}

/**
 * Sign in if the app is asking. A no-op when already authenticated, so it is safe to
 * call on every pass.
 *
 * Reads are open by default (`kweblens.security.open-mode`), so an unauthenticated run
 * renders most of the UI and only fails on the write surfaces — which is exactly the
 * shape of bug that gets missed. Sign in unless you are deliberately testing the
 * logged-out view.
 */
export async function signIn(page) {
  if (!USER) return false;
  const btn = page.getByRole('button', { name: /sign in/i }).first();
  if (!(await btn.isVisible().catch(() => false))) return false;
  await btn.click();
  await page.waitForTimeout(400);
  const inputs = page.locator('.n-modal input');
  await inputs.nth(0).fill(USER);
  await inputs.nth(1).fill(PASS);
  await page.getByRole('button', { name: /^sign/i }).last().click();
  await page.waitForTimeout(1800);
  return true;
}

/** Which theme is actually on, read from the DOM. */
export const currentTheme = (page) =>
  page.evaluate(() => (document.documentElement.classList.contains('kw-dark') ? 'dark' : 'light'));

/**
 * Switch to a named theme.
 *
 * Toggles and re-reads rather than counting clicks: the app remembers the last theme in
 * prefs, so "click once for dark" is wrong roughly half the time, and a run that thinks
 * it measured dark mode while looking at light is worse than no run at all.
 *
 * It dismisses any open overlay first. Callers loop themes with PREPARE INSIDE the loop
 * (ui-shot, contrast-check), so a PREPARE that opens the command palette or a drawer
 * leaves a modal mask over the whole shell — and the second theme's click on
 * `.theme-toggle` is then intercepted by that mask. An earlier version did not do this and
 * `PREPARE='press:Control+k;…'` failed on the dark pass with a 30-second timeout whose
 * message named the toggle, not the modal actually in the way. Escape is what the app
 * itself binds to close both, so this is the same exit the reader would take.
 */
export async function setTheme(page, want) {
  if ((await currentTheme(page)) === want) return;
  if (await page.locator('.n-modal-mask, .n-drawer-mask').first().isVisible().catch(() => false)) {
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);
  }
  await page.click('.theme-toggle');
  await page.waitForTimeout(700);
  const got = await currentTheme(page);
  if (got !== want) throw new Error(`theme toggle did not reach '${want}' (still '${got}')`);
}

/** What a PREPARE step waits for, so an optional one can be tested before it runs. */
const selectorOf = (verb, arg) =>
  verb === 'fill' || verb === 'upload' ? arg.slice(0, arg.lastIndexOf('=')) : arg;

/**
 * Run a PREPARE spec — semicolon-separated steps that bring a surface on screen before
 * it is measured:
 *
 *   press:<key>   click:<selector>   fill:<selector>=<text>   wait:<ms>
 *   goto:<path>   upload:<file input selector>=<path on this machine>
 *   scroll:<selector>   hover:<selector>   leaf:<nav label>  (or leaf:<Category>/<label>)
 *   drawer:<px>   (drag the open detail drawer to a width in its own 360..1400 range)
 *
 * `hover:` exists because a `:hover` rule is a whole surface no tool here could reach, and
 * hover backgrounds are where one-theme colour literals hide: `.btn:hover` hard-coded a light
 * `#f0f4f7` with no dark override, so in the dark theme a button's own `var(--text)` label sat
 * on a near-white pad at 1.16:1 — invisible, on every button in the app, and unmeasurable until
 * this verb existed (#265). The pointer stays parked after the step, so the state survives into
 * both the computed-style read and the backdrop screenshot.
 *
 * `scroll:` exists because contrast-check refuses to sample an element that is off screen —
 * correctly, since a pixel needs the element rendered — and the cluster overview's Warnings
 * table sits at y≈1220 in a 900px viewport. Without it, every selector below the fold reads
 * `outside the viewport`, which is a FAILED measurement dressed as a caveat (#257).
 *
 * Prefix a step with `?` to skip it when its selector is absent. That matters whenever
 * PREPARE runs more than once (per theme, per viewport): a step like signing in applies
 * only the first time, and without `?` the second pass waits for a modal that is already
 * dealt with until it times out and takes the whole run with it.
 */
export async function runPrepare(page, spec) {
  for (const raw of (spec || '')
    .split(';')
    .map((s) => s.trim())
    .filter(Boolean)) {
    const optional = raw.startsWith('?');
    const step = optional ? raw.slice(1) : raw;
    const [verb, ...rest] = step.split(':');
    const arg = rest.join(':');
    if (optional && (verb === 'press' || verb === 'wait' || verb === 'goto')) {
      throw new Error(`? needs a selector, so it cannot mark a ${verb} step`);
    }
    if (optional && !(await page.$(selectorOf(verb, arg)))) continue;
    if (verb === 'press') await page.keyboard.press(arg);
    else if (verb === 'click') await page.click(arg);
    else if (verb === 'wait') await page.waitForTimeout(Number(arg));
    else if (verb === 'scroll') await page.locator(arg).first().scrollIntoViewIfNeeded();
    else if (verb === 'hover') await page.locator(arg).first().hover();
    // `leaf:` rather than `click:.leaf-label…`, for the reason recorded on openLeaf: a
    // collapsed category (#237) keeps its leaves in the DOM, so the click resolves and then
    // burns the whole timeout on "element is not visible" without ever naming the shut
    // `<details>`. contrast-check grew this verb when it hit that; the SHARED runner did not,
    // so ui-measure and ui-shot kept walking into it — and `--leaf` cannot help when a step
    // has to open a category-qualified leaf (`leaf:Workloads/Overview`) or navigate twice.
    else if (verb === 'leaf') await openLeaf(page, arg);
    // `drawer:<px>` drags the open drawer to a width. Its 360..1400 resize range was
    // unreachable from any script, so the narrow end — where a squeezed table column breaks
    // its own values mid-word (#278) — could not be measured at all.
    else if (verb === 'drawer') await resizeDrawer(page, Number(arg));
    else if (verb === 'goto') {
      await page.goto(new URL(arg, BASE_URL).href, { waitUntil: 'networkidle' });
      await page.waitForTimeout(600);
    } else if (verb === 'fill') {
      const at = arg.lastIndexOf('=');
      await page.fill(arg.slice(0, at), arg.slice(at + 1));
    } else if (verb === 'upload') {
      const at = arg.lastIndexOf('=');
      await page.setInputFiles(arg.slice(0, at), arg.slice(at + 1));
    } else throw new Error(`unknown PREPARE verb: ${verb}`);
    await page.waitForTimeout(250);
  }
}

/**
 * Expand every nav category and return the leaf labels.
 *
 * The nav can now be collapsed entirely (#237) and the state is remembered in prefs, so
 * a run can arrive with no tree at all. Re-expand it first, or every leaf lookup fails
 * with a misleading "not found".
 */
export async function expandNav(page) {
  const rail = page.locator('.tile-nav'); // rendered only while the whole nav is collapsed
  if (await rail.isVisible().catch(() => false)) {
    await rail.click().catch(() => {});
    await page.waitForTimeout(300);
  }
  // Set `.open` rather than clicking each summary. Two reasons, both learned by breaking it:
  // a click TOGGLES, so an earlier version that clicked every `.group > summary` shut every
  // category on the normal case (all open) and the leaf it was about to click stopped being
  // clickable; and a click starts the disclosure ANIMATION, which is what Playwright's
  // "element is not stable" means when you then click through it. Setting the property is
  // instant and idempotent.
  await page.evaluate(() => {
    for (const d of document.querySelectorAll('details.group')) {
      d.open = true;
    }
  });
}

/**
 * Resolve one nav leaf, by `Label` or by `Category/Label`, refusing to guess.
 *
 * Exported because `contrast-check.mjs` keeps its own copy of the click-through but must not
 * keep its own copy of the disambiguation — that is the rule this whole family of bugs keeps
 * teaching. The nav must already be expanded.
 */
export async function resolveLeaf(page, spec) {
  const cut = spec.lastIndexOf('/');
  const cat = cut > 0 ? spec.slice(0, cut).trim() : null;
  const label = (cut > 0 ? spec.slice(cut + 1) : spec).trim();
  // Anchored on `.cat-label` / `.leaf-label`, never on the row: a category `<summary>` also
  // holds the chevron and the count badge, so its innerText is `▸Network200` and an exact
  // match against `Network` finds nothing at all — which reads as "no such category" when
  // the category is right there.
  const rx = (s) => new RegExp(`^${s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`);
  const scope = cat
    ? page.locator('details.group', { has: page.locator('.cat-label').filter({ hasText: rx(cat) }) })
    : page;
  const leaves = scope.locator('.leaf-label').filter({ hasText: rx(label) });
  const n = await leaves.count();
  if (n === 0) throw new Error(`nav leaf not found: ${spec}`);
  if (n > 1) {
    const cats = await page
      .locator('details.group', { has: page.locator('.leaf-label').filter({ hasText: rx(label) }) })
      .locator('.cat-label')
      .allInnerTexts();
    throw new Error(
      `nav leaf "${label}" is ambiguous (${n} matches). Qualify it as Category/Label, e.g. ` +
        cats.map((c) => `"${c.trim()}/${label}"`).join(', '),
    );
  }
  return leaves.first();
}

export async function discoverLeaves(page, only = []) {
  await expandNav(page);
  const labels = await page
    .locator('.leaf-label')
    .allInnerTexts()
    .catch(() => []);
  const uniq = [...new Set(labels.map((l) => l.trim()).filter(Boolean))];
  return only.length ? uniq.filter((l) => only.includes(l)) : uniq;
}

/**
 * Click a nav leaf by its exact label and wait for the list to render.
 *
 * The rail and every category are opened first (`expandNav`), for the same reason
 * `discoverLeaves` does it: the nav collapses (#237) and each category is a `<details>`
 * whose open state is remembered in prefs, so a run can arrive with the leaf present in the
 * DOM but inside a closed parent.
 *
 * A COLLAPSED category still has its leaves in the DOM, so the locator resolves and the
 * click is attempted — and Playwright reports "element is not stable" and then "element is
 * not visible". Neither message mentions the shut `<details>` actually responsible, which
 * reads as a missing or renamed leaf and sends you looking at the nav registry. Because the
 * collapsed state survives reloads, an earlier version failed every `--leaf` run on this
 * box, twice, with the error pointing at the leaf.
 *
 * `discoverLeaves` was taught this and `openLeaf` was not, so every script that opens ONE
 * leaf kept hitting it. Both go through `expandNav` now — one expansion, one place to fix.
 *
 * A label that is NOT unique must be qualified `Category/Label`. Six categories each have a
 * leaf called `Overview`, and an earlier version took `.first()` unconditionally: asking for
 * `Overview` to reach the Network overview silently opened the CLUSTER one, which renders the
 * same `.ov-*` classes, so the run produced plausible numbers for a page nobody asked for.
 * Ambiguity now throws and names the categories to choose from — a walker that guesses is
 * worse than one that stops, because its output is indistinguishable from a correct run.
 */
export async function openLeaf(page, label) {
  await expandNav(page);
  // `Category/Leaf` scopes the search to one category, and an UNQUALIFIED label that matches
  // more than one leaf throws rather than taking `.first()`. Qualification alone was not
  // enough: it only helps the caller who already knows to qualify, and the caller who does not
  // still gets the Cluster overview when they asked for the Network one, with plausible
  // numbers and no error. `resolveLeaf` is shared with contrast-check's own click-through so
  // the two walkers cannot disagree about which leaf a spec names.
  const leaf = await resolveLeaf(page, label);
  await leaf.click({ timeout: 4000 });
  await page
    .waitForSelector('.n-data-table-tbody tr, .cluster-overview, .empty', { timeout: 8000 })
    .catch(() => {});
  await page.waitForTimeout(400);
}

/**
 * Drag the open detail drawer to a width, and return the width it actually reached.
 *
 * The drawer is USER-RESIZABLE between 360px and 1400px (`Detail.vue`), and nothing here
 * could reach any of that: every script measured it at the 520px default or, with the ⤢
 * control, expanded. So two thirds of the widths a reader can put the drawer at were
 * unmeasurable — which is how #278 (relation tables shredding a node name mid-token once a
 * column is squeezed) stayed invisible to the tools while being obvious on screen. The
 * defect's severity is a function of the pane's width, so a tool that can only see two
 * widths is not measuring the drawer, it is measuring two of its states.
 *
 * Drags the resize handle rather than setting the width: the width lives in a component
 * `ref` there is no other way in to, and dragging is what a reader does.
 *
 * Throws rather than clamping quietly. A request outside 360..1400 would land on the bound
 * and every following number would describe a width nobody asked for — the "plausible
 * numbers for the wrong thing" failure this file keeps collecting.
 */
export async function resizeDrawer(page, px) {
  const drawer = page.locator('.n-drawer').first();
  if (!(await drawer.isVisible().catch(() => false))) {
    throw new Error('drawer: no drawer is open — open one first, e.g. click:td:nth-child(2)');
  }
  if (px < 360 || px > 1400) {
    throw new Error(`drawer: ${px}px is outside the drawer's own 360..1400 resize range`);
  }
  const trigger = page.locator('.n-drawer-resize-trigger, .n-drawer__resize-trigger').first();
  const handle = await trigger.boundingBox();
  if (!handle) {
    throw new Error('drawer: no resize handle — the drawer is expanded (⤢), which disables resizing');
  }
  const before = (await drawer.boundingBox()).width;
  await page.mouse.move(handle.x + handle.width / 2, handle.y + handle.height / 2);
  await page.mouse.down();
  await page.mouse.move(handle.x + (before - px), handle.y + handle.height / 2, { steps: 20 });
  await page.mouse.up();
  await page.waitForTimeout(400);
  const got = Math.round((await drawer.boundingBox()).width);
  // A few px of drag imprecision is normal; anything more means the drag did not take, and a
  // silent miss here would be reported as a measurement at the requested width.
  if (Math.abs(got - px) > 8) {
    throw new Error(`drawer: asked for ${px}px, the drawer is ${got}px — the drag did not take`);
  }
  return got;
}

/**
 * The machine's 1-minute load average.
 *
 * Every timing this repo has ever taken under a parallel build or a concurrent agent has
 * been wrong — a "hang" at 3000 objects turned out to be a load average of 19 while the
 * same shell rendered in 1.1s. Timing results taken above ~8 are not evidence; layout and
 * colour results are unaffected. Scripts that report timings must print this.
 */
export const loadAverage = () => require('node:os').loadavg()[0];

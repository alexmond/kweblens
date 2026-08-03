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
  const v = VIEWPORTS[name];
  if (!v) throw new Error(`unknown viewport '${name}' — one of: ${Object.keys(VIEWPORTS).join(', ')}`);
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
export async function discoverLeaves(page, only = []) {
  const expand = page.locator('.tile-nav');
  if (await expand.isVisible().catch(() => false)) {
    await expand.click().catch(() => {});
    await page.waitForTimeout(300);
  }
  const cats = page.locator('.group > summary');
  const n = await cats.count();
  for (let i = 0; i < n; i += 1) {
    await cats
      .nth(i)
      .click()
      .catch(() => {});
    await page.waitForTimeout(80);
  }
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
 * It expands the tree first when the leaf is not on screen. The nav is a tree of
 * `<details>`, and a COLLAPSED category still has its leaves in the DOM — so the locator
 * resolves, the click is attempted, and Playwright reports "element is not stable" and then
 * "element is not visible". Neither message mentions the closed group actually responsible,
 * and since the collapsed state is remembered in prefs (#237) it survives reloads: an
 * earlier version failed every `--leaf` run on this box for that reason, twice, and the
 * error pointed at the leaf. Same fix `discoverLeaves` already carries.
 */
export async function openLeaf(page, label) {
  const escaped = label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const leaf = page.locator('.leaf-label', { hasText: new RegExp(`^${escaped}$`) }).first();
  if (!(await leaf.isVisible().catch(() => false))) {
    const rail = page.locator('.tile-nav');
    if (await rail.isVisible().catch(() => false)) {
      await rail.click().catch(() => {});
      await page.waitForTimeout(300);
    }
    const summaries = page.locator('.group > summary');
    const n = await summaries.count();
    for (let i = 0; i < n; i += 1) {
      if (await leaf.isVisible().catch(() => false)) break;
      await summaries
        .nth(i)
        .click()
        .catch(() => {});
      await page.waitForTimeout(120);
    }
    // The disclosure animates; clicking mid-transition is what "not stable" means.
    await page.waitForTimeout(400);
  }
  await leaf.click({ timeout: 4000 });
  await page
    .waitForSelector('.n-data-table-tbody tr, .cluster-overview, .empty', { timeout: 8000 })
    .catch(() => {});
  await page.waitForTimeout(400);
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

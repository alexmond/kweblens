/**
 * Layer-2 performance sweep (SCAFFOLD) — a site-wide hang / long-load detector for the SPA.
 *
 * Walks every left-nav leaf (auto-discovered), loads each list, and measures per page:
 *   - time-to-first-content  (the list's first row, or any content for synthetic pages)
 *   - max main-thread block   (longest `longtask` while the page settles — a "hang")
 * Fails (exit 1) if any page exceeds the budgets, so a regression like the ReplicaSets
 * freeze (a ~10s block) is caught automatically instead of by a human noticing.
 *
 * On-demand, not a per-commit gate: it needs a RUNNING instance + a cluster with data.
 * Run it before releases / after big UI changes, or wire it to a nightly workflow.
 *
 * Usage (uses the account-wide shared Playwright, so set NODE_PATH):
 *   export NODE_PATH="${NODE_PATH:-$HOME/.local/lib/playwright/node_modules}"
 *   node scripts/perf-sweep.mjs
 *
 * Env (all optional):
 *   BASE_URL   default http://localhost:8080/ui/
 *   KWEBLENS_USER / KWEBLENS_PASS   default admin / admin  (blank USER = skip sign-in)
 *   BLOCK_MS   max acceptable main-thread block, default 1500. This is a HANG threshold, not
 *              a jank budget: a big table's initial render is legitimately one ~300-800ms
 *              task, while the ReplicaSets freeze this guards against was ~10,000ms. Lower it
 *              if you want to also flag render jank.
 *   LOAD_MS    max acceptable time-to-first-content, default 5000
 *   SETTLE_MS  observation window per page after content appears, default 800
 *   ONLY       comma-separated leaf labels to restrict the sweep (e.g. "Replica Sets,Pods")
 */
import { createRequire } from 'node:module';

// The account-wide Playwright is resolved via NODE_PATH, which only CJS `require` honours.
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:8080/ui/';
const USER = process.env.KWEBLENS_USER ?? 'admin';
const PASS = process.env.KWEBLENS_PASS ?? 'admin';
const BLOCK_MS = Number(process.env.BLOCK_MS ?? 1500);
const LOAD_MS = Number(process.env.LOAD_MS ?? 5000);
const SETTLE_MS = Number(process.env.SETTLE_MS ?? 800);
const ONLY = (process.env.ONLY ?? '')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean);

// Injected before every page load: accumulate the longest longtask into window.__perf.
const PERF_INIT = `
  window.__perf = { maxBlock: 0 };
  try {
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) window.__perf.maxBlock = Math.max(window.__perf.maxBlock, e.duration);
    }).observe({ entryTypes: ['longtask'] });
  } catch {}
`;

const resetPerf = (page) => page.evaluate(() => (window.__perf = { maxBlock: 0 })).catch(() => {});
const readBlock = (page) => page.evaluate(() => Math.round(window.__perf?.maxBlock ?? 0)).catch(() => 0);

async function signIn(page) {
  if (!USER) return;
  const btn = page.getByRole('button', { name: /sign in/i }).first();
  if (!(await btn.isVisible().catch(() => false))) return;
  await btn.click();
  await page.waitForTimeout(400);
  const inputs = page.locator('.n-modal input');
  await inputs.nth(0).fill(USER);
  await inputs.nth(1).fill(PASS);
  await page.getByRole('button', { name: /^sign/i }).last().click();
  await page.waitForTimeout(1800);
}

// Expand every collapsed nav category, then collect the leaf labels.
async function discoverLeaves(page) {
  const cats = page.locator('.group > summary, .nav-group > summary, summary');
  const n = await cats.count();
  for (let i = 0; i < n; i += 1) {
    await cats
      .nth(i)
      .click()
      .catch(() => {});
    await page.waitForTimeout(80);
  }
  const labels = await page.locator('.leaf-label').allInnerTexts().catch(() => []);
  const uniq = [...new Set(labels.map((l) => l.trim()).filter(Boolean))];
  return ONLY.length ? uniq.filter((l) => ONLY.includes(l)) : uniq;
}

async function measureLeaf(page, label) {
  await resetPerf(page);
  const leaf = page.locator('.leaf-label', { hasText: new RegExp(`^${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`) }).first();
  const t0 = Date.now();
  await leaf.click({ timeout: 4000 });
  // First content = a data-table row, or (synthetic pages) any main content / "items" count.
  let rows = 0;
  try {
    await page.waitForSelector('.n-data-table-tbody tr, .count, .cluster-overview, .empty', { timeout: LOAD_MS });
    rows = await page.locator('.n-data-table-tbody tr').count().catch(() => 0);
  } catch {
    /* timed out — recorded as a slow load below */
  }
  const loadMs = Date.now() - t0;
  await page.waitForTimeout(SETTLE_MS); // let any post-load churn register as longtasks
  const block = await readBlock(page);
  const overLoad = loadMs > LOAD_MS;
  const overBlock = block > BLOCK_MS;
  return { label, rows, loadMs, block, ok: !overLoad && !overBlock, overLoad, overBlock };
}

async function main() {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
  await page.addInitScript(PERF_INIT);
  await page.goto(BASE_URL, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);
  await signIn(page);

  const leaves = await discoverLeaves(page);
  if (!leaves.length) {
    console.error('No nav leaves discovered — is the app up and signed in?');
    process.exit(2);
  }
  console.log(`Sweeping ${leaves.length} pages · budgets: load<${LOAD_MS}ms block<${BLOCK_MS}ms\n`);

  const results = [];
  for (const label of leaves) {
    try {
      results.push(await measureLeaf(page, label));
    } catch (e) {
      results.push({ label, rows: 0, loadMs: -1, block: -1, ok: false, error: String(e).slice(0, 60) });
    }
  }
  await browser.close();

  const pad = (s, n) => String(s).padEnd(n);
  console.log(pad('PAGE', 26) + pad('ROWS', 6) + pad('LOAD', 8) + pad('BLOCK', 8) + 'STATUS');
  for (const r of results) {
    const status = r.ok ? 'ok' : [r.overLoad && 'SLOW-LOAD', r.overBlock && 'HANG', r.error].filter(Boolean).join(' ');
    console.log(pad(r.label, 26) + pad(r.rows, 6) + pad(r.loadMs + 'ms', 8) + pad(r.block + 'ms', 8) + status);
  }
  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} within budget.`);
  if (failed.length) {
    console.log('OVER BUDGET: ' + failed.map((r) => r.label).join(', '));
    process.exit(1);
  }
}

main().catch((e) => {
  console.error('perf-sweep failed:', e);
  process.exit(2);
});

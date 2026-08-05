/**
 * Layer-2 performance sweep (SCAFFOLD) — a site-wide hang / long-load detector for the SPA.
 *
 * Walks every left-nav leaf (auto-discovered), loads each list, and measures per page:
 *   - time-to-first-ROW      (the list's first `<tr>`, and nothing else — see below)
 *   - max main-thread block   (longest `longtask` while the page settles — a "hang")
 * Fails (exit 1) if any page exceeds the budgets, so a regression like the ReplicaSets
 * freeze (a ~10s block) is caught automatically instead of by a human noticing.
 *
 * LOAD used to wait for `.n-data-table-tbody tr, .count, .cluster-overview, .empty` and
 * called that "time to first content". `.count` is `ResourceListView`'s items badge, which
 * has NO `v-if` — it renders "0 items" the instant the list shell mounts, before a single
 * byte of data has arrived. So on any page whose data was slow, the wait resolved on an
 * empty shell and LOAD reported the mount time: a Pods list at simulator size=200 was
 * recorded as `0 rows 111ms` while a strict wait for a row measured 917ms on the same
 * instance. The understatement is worst exactly where it matters — the slower the server,
 * the earlier the badge wins — so LOAD was least trustworthy in the regime it exists to
 * police. (It did NOT affect BLOCK, which comes from a PerformanceObserver and is what
 * #286's threshold decision was argued on; that was re-measured against both thresholds
 * and reproduced. See docs/design/scale-measurements.md.)
 *
 * A row is now the only thing that ends the LOAD measurement. That leaves the case the old
 * selector list was really there for: a legitimately EMPTY collection, which never produces
 * a row. It cannot be told from "still loading" by the DOM alone — the badge reads "0 items"
 * in both — so it is resolved by time: a count that stays at zero for EMPTY_MS with no row
 * is reported as `empty` and timed as nothing, while a NON-ZERO count with no row is a real
 * render failure and keeps waiting to LOAD_MS. An empty page is never scored, which is
 * honest: there was nothing to time.
 *
 * On-demand, not a per-commit gate: it needs a RUNNING instance + a cluster with data.
 * Run it before releases / after big UI changes, or wire it to a nightly workflow.
 *
 * Usage (uses the account-wide shared Playwright, so set NODE_PATH):
 *   export NODE_PATH="${NODE_PATH:-$HOME/.local/lib/playwright/node_modules}"
 *   node scripts/perf-sweep.mjs
 *
 * Env (all optional):
 *   PORT       default 8080 — the instance to sweep
 *   BASE_URL   overrides PORT entirely; default http://localhost:$PORT/
 *   KWEBLENS_USER / KWEBLENS_PASS   default admin / admin  (blank USER = skip sign-in)
 *   BLOCK_MS   max acceptable main-thread block, default 1500. This is a HANG threshold, not
 *              a jank budget: a big table's initial render is legitimately one ~300-800ms
 *              task, while the ReplicaSets freeze this guards against was ~10,000ms. Lower it
 *              if you want to also flag render jank.
 *   LOAD_MS    max acceptable time-to-first-row, default 5000
 *   EMPTY_MS   how long a zero count with no row is waited on before the page is called
 *              legitimately empty rather than slow, default 2500. Only affects how long an
 *              empty leaf is waited on; it never becomes a reported timing.
 *   SETTLE_MS  observation window per page after content appears, default 800
 *   ONLY       comma-separated leaf labels to restrict the sweep (e.g. "Replica Sets,Pods")
 */
import { createRequire } from 'node:module';

// The account-wide Playwright is resolved via NODE_PATH, which only CJS `require` honours.
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

// PORT, like every other script here. This one alone took only BASE_URL, so `PORT=8128
// node scripts/perf-sweep.mjs` silently swept :8080 — or, with nothing there, died on
// ERR_CONNECTION_REFUSED naming a port the caller had not asked for. Parallel instances on
// non-default ports are the normal case (dev-run.sh --port), so this is not an edge.
const BASE_URL = process.env.BASE_URL ?? `http://localhost:${process.env.PORT ?? 8080}/`;
const USER = process.env.KWEBLENS_USER ?? 'admin';
const PASS = process.env.KWEBLENS_PASS ?? 'admin';
const BLOCK_MS = Number(process.env.BLOCK_MS ?? 1500);
const LOAD_MS = Number(process.env.LOAD_MS ?? 5000);
const EMPTY_MS = Number(process.env.EMPTY_MS ?? 2500);
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

/**
 * The state a list page is in, read from the page itself.
 *   rows   how many `<tr>` are rendered (windowed, so this is the visible window, not N)
 *   count  the items badge parsed to a number, or null on a page that has no list at all
 *          (the cluster overview, Port Forwards before any exist, ...)
 */
const listState = (page) =>
  page
    .evaluate(() => {
      const rows = document.querySelectorAll('.n-data-table-tbody tr').length;
      const text = document.querySelector('.count')?.textContent ?? '';
      // "N items" or, with a filter applied, "N of M"
      const m = /(\d+)\s*(?:items|of)/.exec(text);
      return { rows, count: m ? Number(m[1]) : null, synthetic: !document.querySelector('.list-view') };
    })
    .catch(() => ({ rows: 0, count: null, synthetic: false }));

async function measureLeaf(page, label) {
  await resetPerf(page);
  const leaf = page.locator('.leaf-label', { hasText: new RegExp(`^${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`) }).first();
  const t0 = Date.now();
  await leaf.click({ timeout: 4000 });

  // Poll rather than waitForSelector: the decision needs the row count AND the badge
  // together, and a single selector cannot express "a row, or a zero that has stopped
  // changing". Note this poll is itself queued behind a long task, which is why a page that
  // blocks for 1.5s cannot report a 100ms load however the wait is written.
  let rows = 0;
  let empty = false;
  let sawData = false;
  const deadline = t0 + LOAD_MS;
  for (;;) {
    const st = await listState(page);
    if (st.rows > 0) {
      rows = st.rows;
      break;
    }
    if (st.count !== null && st.count > 0) sawData = true; // data arrived; a missing row is on us
    if (st.synthetic) {
      empty = true; // no list on this page at all — nothing to time
      break;
    }
    // A zero badge that has stayed zero past the grace period is an empty collection, not a
    // slow one. Once a non-zero count has been seen, only a row ends the wait.
    if (!sawData && Date.now() - t0 > EMPTY_MS) {
      empty = true;
      break;
    }
    if (Date.now() > deadline) break;
    await page.waitForTimeout(60);
  }
  const loadMs = Date.now() - t0;
  await page.waitForTimeout(SETTLE_MS); // let any post-load churn register as longtasks
  const block = await readBlock(page);
  // An empty page is not scored on load: it never had a row to wait for. It is still scored
  // on block, because a page can hang without rendering anything.
  const overLoad = !empty && loadMs > LOAD_MS;
  const overBlock = block > BLOCK_MS;
  return { label, rows, loadMs, block, empty, ok: !overLoad && !overBlock, overLoad, overBlock };
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
    const status = [r.ok ? 'ok' : '', r.overLoad && 'SLOW-LOAD', r.overBlock && 'HANG', r.empty && '(empty — load not scored)', r.error]
      .filter(Boolean)
      .join(' ');
    // An empty page's elapsed time is the grace period, not a load: printing it as a
    // duration is how "0 rows 111ms" came to be read as a fast page.
    const load = r.empty ? '—' : r.loadMs + 'ms';
    console.log(pad(r.label, 26) + pad(r.rows, 6) + pad(load, 8) + pad(r.block + 'ms', 8) + status);
  }
  const failed = results.filter((r) => !r.ok);
  const scored = results.filter((r) => !r.empty).length;
  console.log(`\n${results.length - failed.length}/${results.length} within budget · ${scored} of ${results.length} had rows to time.`);
  if (failed.length) {
    console.log('OVER BUDGET: ' + failed.map((r) => r.label).join(', '));
    process.exit(1);
  }
}

main().catch((e) => {
  console.error('perf-sweep failed:', e);
  process.exit(2);
});

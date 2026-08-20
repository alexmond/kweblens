/**
 * Open the detail drawer ONCE, then interact with the page behind it, and prove the object
 * you were reading is still on screen — and still closable.
 *
 * WHY THIS EXISTS
 *
 * GH#480: clicking the brand bar's theme toggle with a Pod's drawer open closed the drawer
 * and dropped the selection (`.n-drawer` 1 → 0, `.n-data-table-tr.row-active` 1 → 0). The
 * cause was neither of the two the ticket listed — it is not the `NConfigProvider` `:theme`
 * prop re-rendering its subtree, and it is not a watcher clearing `detail`. A maskless
 * `NDrawer` registers evtd's `clickoutside` trap (a mousedown AND a mouseup outside the
 * drawer body), and `maskClosable` defaults to true, so ANY pointer press on the page behind
 * it closed the drawer. The theme toggle was simply the click that got measured; the footer
 * and the brand bar did it too, with no theme change involved.
 *
 * That is why this script does not check the theme toggle alone. The rule it measures is the
 * one the fix establishes: a NON-MODAL drawer is not dismissed by interacting with the page
 * behind it. Two gestures cover it — the theme toggle (the reported one, and the only one
 * that also re-renders the subtree) and a click on the footer (a pointer press with no theme
 * change and no navigation at all). A probe that only toggled the theme would pass a fix that
 * special-cased the toggle and left every other click closing the drawer.
 *
 * THE HALF THAT IS EASY TO FORGET
 *
 * "Never closes" passes a drawer-survives check perfectly. So the run also proves the drawer
 * still closes the two ways it is supposed to: Escape, and the header's ✕. Without those, the
 * obvious fix (`:mask-closable="false"`) could take the close affordances with it and this
 * script would report a clean pass on a drawer nobody can get rid of.
 *
 * THE MEASUREMENT TRAPS IT AVOIDS
 *
 * - **A theme that did not change makes "the drawer survived" vacuous.** Every toggle asserts
 *   `kw-dark` really flipped before it looks at the drawer.
 * - **A drawer that is present is not the drawer you opened.** The object's name is read from
 *   `.drawer-name` at the start and compared after every step, alongside `row-active`: the
 *   ticket's whole point is that the SELECTION was dropped, not just the panel.
 * - **The pointer is parked off the table after each click.** A Playwright click leaves the
 *   pointer on the element, and a row under the pointer is a hovered row; this script reads
 *   counts rather than colours, but the park also keeps `row-active` from being confused with
 *   a hover state by a later reader of this file.
 * - **Another agent's server.** `dev-run.sh` takes a port from whoever had it, so the port's
 *   owner is resolved to a pid and its cwd compared with this checkout before anything is
 *   read (see `cluster-switch-check.mjs`, where a run against someone else's build produced a
 *   confident, wrong finding).
 *
 *   scripts/dev-run.sh --sim --port 8117
 *   export NODE_PATH=$HOME/.local/lib/playwright/node_modules
 *   PORT=8117 node scripts/drawer-persist-check.mjs
 *
 * LEAF defaults to Pods. EXPECT_CWD=any skips the ownership check.
 */
import { execFileSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { PORT, open, openLeaf } from './lib/kw-playwright.mjs';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const LEAF = process.env.LEAF ?? 'Pods';
const VIEW = process.env.VIEW ?? 'wide';

/** Refuse to measure a server that is not the one this checkout built. */
function assertOurServer() {
  const want = process.env.EXPECT_CWD ?? REPO;
  if (want === 'any') return;
  const pids = execFileSync('bash', ['-c', `ss -lptnH "sport = :${PORT}" | grep -o 'pid=[0-9]*' | cut -d= -f2`])
    .toString()
    .split('\n')
    .filter(Boolean);
  if (!pids.length) {
    throw new Error(`nothing is listening on :${PORT} — start it with scripts/dev-run.sh --sim --port ${PORT}`);
  }
  const owners = pids.map((p) => [p, execFileSync('readlink', [`/proc/${p}/cwd`]).toString().trim()]);
  if (!owners.some(([, cwd]) => cwd === want)) {
    throw new Error(
      `:${PORT} is served from ${owners.map(([p, c]) => `${c} (pid ${p})`).join(', ')}, not ${want}. ` +
        `Another agent's dev-run.sh has taken the port. Start yours on a free one and pass PORT=…`,
    );
  }
  console.log(`serving :${PORT} from ${want} (pid ${owners.find(([, c]) => c === want)[0]})`);
}

/** Everything this check is a claim about, in one read. */
const state = (page) =>
  page.evaluate(() => {
    const el = document.querySelector('.n-drawer .drawer-name');
    return {
      drawer: document.querySelectorAll('.n-drawer').length,
      mask: document.querySelectorAll('.n-drawer-mask').length,
      rowActive: document.querySelectorAll('.n-data-table-tr.row-active').length,
      name: el ? el.textContent.trim() : '',
      theme: document.documentElement.classList.contains('kw-dark') ? 'dark' : 'light',
    };
  });

const failures = [];
const line = (label, s) =>
  console.log(`  ${label.padEnd(40)} drawer=${s.drawer} rowActive=${s.rowActive} theme=${s.theme} name=${s.name || '—'}`);

/** The drawer is open, about `want`, and the row it is about is marked. */
function assertHolding(s, want, what) {
  if (s.drawer !== 1 || s.rowActive !== 1 || s.name !== want) {
    failures.push(
      `${what}: expected the drawer open on '${want}' with its row marked, got ` +
        `drawer=${s.drawer} rowActive=${s.rowActive} name='${s.name}'`,
    );
    return false;
  }
  return true;
}

async function openDrawer(page) {
  // The Name cell, not the row centre: `ResourceTable.rowProps` ignores a click inside a
  // checkbox/button/link, so the checkbox column and the Namespace link open nothing.
  await page.click('.n-data-table-tbody tr td:nth-child(2)');
  await page.waitForTimeout(700);
  await page.mouse.move(4, 4);
  await page.waitForTimeout(250);
}

async function toggleTheme(page) {
  const before = (await state(page)).theme;
  await page.click('.theme-toggle');
  await page.waitForTimeout(700);
  const after = await state(page);
  if (after.theme === before) {
    failures.push(`the theme toggle did not change the theme (still '${before}') — nothing below it means anything`);
  }
  return after;
}

assertOurServer();
const { browser, page } = await open({ view: VIEW });
await openLeaf(page, LEAF);
await openDrawer(page);

const opened = await state(page);
line('after open', opened);
if (opened.drawer !== 1 || opened.rowActive !== 1 || opened.name === '') {
  console.error(
    `FAILED to open a drawer on the ${LEAF} list (drawer=${opened.drawer} rowActive=${opened.rowActive}) — ` +
      'nothing was measured. An absent surface is a failed run, not a pass.',
  );
  await browser.close();
  process.exit(1);
}
const want = opened.name;
console.log(`\nthe drawer is about '${want}'; it must still be, after every step below\n`);

// 1. Both theme directions, from ONE open. The ticket's own reproduction is a single toggle;
//    walking back proves the fix is not direction-specific (a light-only or dark-only pass is
//    exactly what a re-render that depends on the outgoing theme would produce).
console.log(`themes (one open, both directions) — starting in ${opened.theme}`);
let s = await toggleTheme(page);
line(`after theme toggle -> ${s.theme}`, s);
assertHolding(s, want, `theme toggle to ${s.theme}`);
s = await toggleTheme(page);
line(`after theme toggle -> ${s.theme}`, s);
assertHolding(s, want, `theme toggle to ${s.theme}`);

// 2. A pointer press on the page behind the drawer that changes NOTHING. This is the general
//    rule; the theme toggle above is one instance of it.
console.log('\nan unrelated click behind the drawer');
const foot = await page.locator('.app-footer, footer').first().boundingBox();
if (!foot) {
  failures.push('no footer on screen — the unrelated-click control did not run, which is a failed measurement');
} else {
  await page.mouse.click(foot.x + 12, foot.y + foot.height / 2);
  await page.waitForTimeout(600);
  s = await state(page);
  line('after a click on the footer', s);
  assertHolding(s, want, 'a click on the footer');
}

// 3. The negative control. "Never closes" passes everything above.
console.log('\nthe drawer must still close the two ways it is meant to');
await page.keyboard.press('Escape');
await page.waitForTimeout(600);
s = await state(page);
line('after Escape', s);
if (s.drawer !== 0) failures.push(`Escape did not close the drawer (drawer=${s.drawer})`);

await openDrawer(page);
s = await state(page);
if (s.drawer !== 1) {
  failures.push('could not re-open the drawer for the ✕ control');
} else {
  await page.click('.n-drawer .n-base-close');
  await page.waitForTimeout(600);
  s = await state(page);
  line('after the header ✕', s);
  if (s.drawer !== 0) failures.push(`the header ✕ did not close the drawer (drawer=${s.drawer})`);
}

await browser.close();
console.log('');
if (failures.length) {
  failures.forEach((f) => console.error(`FAIL  ${f}`));
  console.error(`\n${failures.length} failure(s) — GH#480: the drawer must survive what happens behind it.`);
  process.exit(1);
}
console.log('PASS  the drawer survived both themes and an unrelated click, and still closes on Escape and ✕.');

/**
 * Open the command palette over a resource drawer and prove ONE Escape closes ONE overlay.
 *
 * WHY THIS EXISTS
 *
 * GH#497: with the drawer open, Ctrl/Cmd-K then Escape closed the palette AND the drawer
 * underneath it — the object went and its row lost `row-active`. Measured on the pre-fix
 * build (simulator, wide, Pods, first row):
 *
 *                               drawer  rowActive  palette
 *   drawer open                    1        1         0
 *   palette opened over it         1        1         1
 *   after Escape                   0        0         0     <- both closed
 *
 * The same damage as GH#488 (the YAML editor, `yaml-escape-check.mjs`) from a different
 * overlay, and #488's fix did not cover it: that one fixed the TIMING of a boolean —
 * `Detail.vue` read `yamlEditing` in the capture phase — and there is no equivalent boolean
 * for the palette. A boolean per overlay is a list that goes stale the next time something is
 * rendered over the drawer, so the fix is a question instead: `useEscapeKey` gives the key to
 * the caller only when it is the INNERMOST open dialog, which the page already declares with
 * `role="dialog" aria-modal="true"` on the drawer and on every `NModal`. Nothing registers.
 *
 * That is why this script also asserts that the palette DECLARES itself a dialog. If a future
 * overlay is built without that attribute the drawer will start closing under it again, and a
 * run should say which contract was broken rather than just which count moved.
 *
 * THE HALF THAT IS EASY TO FORGET
 *
 * "Escape does nothing" passes a survives-Escape check perfectly, and so does "the palette
 * never opened". So every step asserts the thing that MUST have happened as well as the thing
 * that must not: the palette is really on screen before the key is pressed, the first Escape
 * really closes it, the second Escape really closes the drawer, the drawer's ✕ still does too,
 * and — the case the drawer must not have stolen — the palette still closes on Escape when
 * there is no drawer at all.
 *
 * THE MEASUREMENT TRAPS IT AVOIDS
 *
 * - **A drawer that is present is not the drawer you opened.** The object's name is read from
 *   `.drawer-name` at the start and compared after every step, alongside `row-active` — the
 *   ticket's complaint is that the SELECTION went with the panel.
 * - **A naive overlay outlives its own close.** The palette's `.n-modal` and its `[aria-modal]`
 *   are still in the DOM through the leave transition — measured present at +210 ms and gone by
 *   +530 ms — so every read here waits the transition out. A check that sampled at +0 ms would
 *   report the palette as still open on a perfectly good close.
 * - **The pointer is parked off the table after each open.** A row under the pointer is a
 *   hovered row, and a later reader should not have to wonder whether `row-active` was really
 *   a selection.
 * - **Another agent's server.** `dev-run.sh` takes a port from whoever had it, so the port's
 *   owner is resolved to a pid and its cwd compared with this checkout before anything is
 *   read — the same guard `yaml-escape-check.mjs` and `drawer-persist-check.mjs` open with.
 *
 *   scripts/dev-run.sh --sim --port 8137
 *   export NODE_PATH=$HOME/.local/lib/playwright/node_modules
 *   PORT=8137 node scripts/palette-escape-check.mjs
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
/** Long enough for naive's leave transition; the palette is gone by ~530 ms, measured. */
const SETTLE = 800;

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
    const palette = document.querySelector('.palette-card');
    return {
      drawer: document.querySelectorAll('.n-drawer').length,
      palette: document.querySelectorAll('.palette-card').length,
      rowActive: document.querySelectorAll('.n-data-table-tr.row-active').length,
      name: el ? el.textContent.trim() : '',
      // The contract the fix rests on: an overlay above the drawer says so in the DOM.
      paletteDeclaresDialog: palette
        ? palette.getAttribute('role') === 'dialog' && palette.getAttribute('aria-modal') === 'true'
        : null,
      drawerDeclaresDialog: (() => {
        const d = document.querySelector('.detail-drawer');
        return d ? d.getAttribute('role') === 'dialog' && d.getAttribute('aria-modal') === 'true' : null;
      })(),
    };
  });

const failures = [];
const line = (label, s) =>
  console.log(
    `  ${label.padEnd(44)} drawer=${s.drawer} palette=${s.palette} rowActive=${s.rowActive} name=${s.name || '—'}`,
  );

/** The drawer is open, about `want`, with its row still marked. */
function assertHolding(s, want, what) {
  if (s.drawer !== 1 || s.rowActive !== 1 || s.name !== want) {
    failures.push(
      `${what}: expected the drawer open on '${want}' with its row marked, got ` +
        `drawer=${s.drawer} rowActive=${s.rowActive} name='${s.name}'`,
    );
  }
}

async function openDrawer(page) {
  // The Name cell, not the row centre: `ResourceTable.rowProps` ignores a click inside a
  // checkbox/button/link, so the checkbox column and the Namespace link open nothing.
  await page.click('.n-data-table-tbody tr td:nth-child(2)');
  await page.waitForTimeout(700);
  await page.mouse.move(4, 4);
  await page.waitForTimeout(250);
}

/** Ctrl-K. Refuses to continue if the palette did not open. */
async function openPalette(page, what) {
  await page.keyboard.press('Control+k');
  await page.waitForTimeout(SETTLE);
  const s = await state(page);
  if (s.palette !== 1) {
    failures.push(`${what}: the command palette did not open (palette=${s.palette}) — nothing after this was measured`);
    return null;
  }
  if (s.paletteDeclaresDialog !== true) {
    failures.push(
      `${what}: the palette is on screen but does not declare itself a modal dialog ` +
        `(role="dialog" aria-modal="true") — that declaration is what tells the drawer the key ` +
        'is not its, so the drawer will close under it',
    );
  }
  return s;
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
if (opened.drawerDeclaresDialog !== true) {
  failures.push(
    'the drawer does not carry `.detail-drawer` with role="dialog" aria-modal="true" — that is ' +
      'the element `useEscapeKey` resolves as its own, and without it the drawer answers Escape ' +
      'for nobody',
  );
}
const want = opened.name;
console.log(`\nthe drawer is about '${want}'; it must still be, until something closes it on purpose\n`);

// 1. The ticket's own sequence: palette open over the drawer, then ONE Escape.
console.log('one Escape must close one overlay');
let s = await openPalette(page, 'the first open');
if (s) {
  line('palette open over the drawer', s);
  assertHolding(s, want, 'opening the palette');

  await page.keyboard.press('Escape');
  await page.waitForTimeout(SETTLE);
  s = await state(page);
  line('after Escape', s);
  assertHolding(s, want, 'the first Escape');
  // Both halves. Without the first, a drawer nobody can leave passes; without the second,
  // an Escape that does nothing at all passes.
  if (s.palette !== 0) failures.push(`the first Escape did not close the palette (palette=${s.palette})`);

  // 2. The second Escape belongs to the drawer again. Only a drawer the first Escape LEFT
  //    OPEN can be closed by a second one — asserting `drawer === 0` unconditionally would
  //    be satisfied by the very bug this file exists for, so say so instead of scoring it.
  if (s.drawer !== 1) {
    console.log('  (the second Escape was not measured — the first one had already closed the drawer)');
  } else {
    await page.keyboard.press('Escape');
    await page.waitForTimeout(SETTLE);
    s = await state(page);
    line('after a second Escape', s);
    if (s.drawer !== 0) failures.push(`the second Escape did not close the drawer (drawer=${s.drawer})`);
  }
}

// 3. The palette still closes on Escape when nothing is under it. A fix that made the drawer
//    safe by taking Escape away from the palette would otherwise read as a clean pass.
console.log('\nwith no drawer, Escape still closes the palette');
s = await openPalette(page, 'the open with no drawer');
if (s) {
  line('palette open, no drawer', s);
  if (s.drawer !== 0) failures.push(`a drawer was still open for the no-drawer case (drawer=${s.drawer})`);
  await page.keyboard.press('Escape');
  await page.waitForTimeout(SETTLE);
  s = await state(page);
  line('after Escape', s);
  if (s.palette !== 0) {
    failures.push(`Escape did not close the palette when nothing was under it (palette=${s.palette})`);
  }
}

// 4. The drawer's ✕ still closes the drawer — the close affordance #480 kept.
console.log('\nthe drawer’s ✕ still closes the drawer');
await openDrawer(page);
s = await state(page);
assertHolding(s, want, 'the re-open for the ✕ control');
await page.click('.n-drawer .n-base-close');
await page.waitForTimeout(SETTLE);
s = await state(page);
line('after the drawer’s ✕', s);
if (s.drawer !== 0) failures.push(`the drawer’s ✕ did not close the drawer (drawer=${s.drawer})`);

await browser.close();
console.log('');
if (failures.length) {
  failures.forEach((f) => console.error(`FAIL  ${f}`));
  console.error(`\n${failures.length} failure(s) — GH#497: one Escape closes one overlay.`);
  process.exit(1);
}
console.log('PASS  Escape closed the palette and left the drawer, and still closes the palette on its own.');

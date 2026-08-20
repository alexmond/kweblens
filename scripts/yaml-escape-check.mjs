/**
 * Open the YAML editor over a resource drawer and prove ONE Escape closes ONE overlay.
 *
 * WHY THIS EXISTS
 *
 * GH#488: with the pop-out YAML editor open, a single Escape closed the editor AND the drawer
 * underneath it — the object being edited disappeared and its row lost `row-active`
 * (`.n-drawer` 1 → 0, `.n-data-table-tr.row-active` 1 → 0). `Detail.vue` has a guard for
 * exactly this (`yamlEditing`), and the guard was reading a value the same keypress had just
 * written. Measured on one keypress, with the two handlers instrumented:
 *
 *   window capture  →  YamlTab.closeEditor  →  Detail.useEscapeKey (yamlEditing: false)
 *
 * naive-ui closes the editor's `NModal` through vueuc's FocusTrap, which registers through
 * evtd, and evtd registers EVERY handler as `window.addEventListener(type, unified, capture)`
 * whatever element was named — so naive's Escape and `useEscapeKey`'s plain `window` listener
 * were both window-BUBBLE listeners and the winner was whichever registered first. It was
 * naive's, so the drawer's guard always read the state its own keypress had produced. The fix
 * is the capture phase; `useEscapeKey.test.ts` is the unit gate on it and this is the
 * end-to-end control.
 *
 * THE HALF THAT IS EASY TO FORGET
 *
 * "Escape does nothing" passes a survives-Escape check perfectly, and so does "the editor
 * never opened". So every step asserts the thing that MUST have happened as well as the thing
 * that must not: the editor is really on screen before the key is pressed, the first Escape
 * really closes it, the second Escape really closes the drawer, and the header ✕ still does
 * too. The editor's own ✕ is exercised as well, because a fix that made Escape safe by making
 * the editor undismissable would otherwise read as a clean pass.
 *
 * THE MEASUREMENT TRAPS IT AVOIDS
 *
 * - **A drawer that is present is not the drawer you opened.** The object's name is read from
 *   `.drawer-name` at the start and compared after every step, alongside `row-active` — the
 *   ticket's complaint is that the SELECTION went with the panel.
 * - **The pointer is parked off the table after each open.** A row under the pointer is a
 *   hovered row, and a later reader of this file should not have to wonder whether
 *   `row-active` was really a selection.
 * - **Another agent's server.** `dev-run.sh` takes a port from whoever had it, so the port's
 *   owner is resolved to a pid and its cwd compared with this checkout before anything is
 *   read — the same guard `cluster-switch-check.mjs` and `drawer-persist-check.mjs` open with.
 *
 *   scripts/dev-run.sh --sim --port 8123
 *   export NODE_PATH=$HOME/.local/lib/playwright/node_modules
 *   PORT=8123 node scripts/yaml-escape-check.mjs
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
      editor: document.querySelectorAll('.yaml-editor-modal').length,
      rowActive: document.querySelectorAll('.n-data-table-tr.row-active').length,
      name: el ? el.textContent.trim() : '',
    };
  });

const failures = [];
const line = (label, s) =>
  console.log(
    `  ${label.padEnd(44)} drawer=${s.drawer} editor=${s.editor} rowActive=${s.rowActive} name=${s.name || '—'}`,
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

/** Drawer → YAML tab → the pop-out editor. Refuses to continue if it did not open. */
async function openEditor(page, what) {
  await page.click('.n-drawer .n-tabs-tab:has-text("YAML")');
  await page.waitForTimeout(1200);
  await page.click('.yaml-toolbar button:has-text("Edit"), .yaml-toolbar button:has-text("View")');
  await page.waitForTimeout(1200);
  const s = await state(page);
  if (s.editor !== 1) {
    failures.push(`${what}: the YAML editor did not open (editor=${s.editor}) — nothing after this was measured`);
    return null;
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
const want = opened.name;
console.log(`\nthe drawer is about '${want}'; it must still be, until something closes it on purpose\n`);

// 1. The ticket's own sequence: editor open, a click inside it, then ONE Escape.
console.log('one Escape must close one overlay');
let s = await openEditor(page, 'the first open');
if (s) {
  line('YAML editor open', s);
  assertHolding(s, want, 'opening the YAML editor');

  await page.click('.yaml-editor-modal .cm-yaml');
  await page.waitForTimeout(500);
  s = await state(page);
  line('after a click inside the editor', s);
  assertHolding(s, want, 'a click inside the editor');
  if (s.editor !== 1) failures.push('a click inside the editor closed it');

  await page.keyboard.press('Escape');
  await page.waitForTimeout(700);
  s = await state(page);
  line('after Escape', s);
  assertHolding(s, want, 'the first Escape');
  // Both halves. Without the first, a drawer nobody can leave passes; without the second,
  // an Escape that does nothing at all passes.
  if (s.editor !== 0) failures.push(`the first Escape did not close the editor (editor=${s.editor})`);

  // 2. The second Escape belongs to the drawer again. Only a drawer the first Escape LEFT
  //    OPEN can be closed by a second one — asserting `drawer === 0` unconditionally would
  //    be satisfied by the very bug this file exists for, so say so instead of scoring it.
  if (s.drawer !== 1) {
    console.log('  (the second Escape was not measured — the first one had already closed the drawer)');
  } else {
    await page.keyboard.press('Escape');
    await page.waitForTimeout(700);
    s = await state(page);
    line('after a second Escape', s);
    if (s.drawer !== 0) failures.push(`the second Escape did not close the drawer (drawer=${s.drawer})`);
  }
}

// 3. The editor's own ✕ closes the editor and nothing else, and the drawer's ✕ still works.
console.log('\nthe ✕ on each overlay closes that overlay');
await openDrawer(page);
s = await openEditor(page, 'the re-open for the ✕ controls');
if (s) {
  await page.click('.yaml-editor-modal .n-base-close');
  await page.waitForTimeout(700);
  s = await state(page);
  line('after the editor’s ✕', s);
  assertHolding(s, want, 'the editor’s ✕');
  if (s.editor !== 0) failures.push(`the editor’s ✕ did not close the editor (editor=${s.editor})`);

  await page.click('.n-drawer .n-base-close');
  await page.waitForTimeout(700);
  s = await state(page);
  line('after the drawer’s ✕', s);
  if (s.drawer !== 0) failures.push(`the drawer’s ✕ did not close the drawer (drawer=${s.drawer})`);
}

await browser.close();
console.log('');
if (failures.length) {
  failures.forEach((f) => console.error(`FAIL  ${f}`));
  console.error(`\n${failures.length} failure(s) — GH#488: one Escape closes one overlay.`);
  process.exit(1);
}
console.log('PASS  Escape closed the editor and left the drawer, and every ✕ still closes its own overlay.');

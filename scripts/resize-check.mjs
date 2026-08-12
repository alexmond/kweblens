#!/usr/bin/env node
/**
 * Is a multiline field actually pull-resizable, and does the pulled height SURVIVE typing?
 *
 * ## Why this script exists
 *
 * A user asked for the native corner grabber on the fields that can hold multiline content
 * (a PEM certificate in a ConfigMap `data` value, a manifest, a values file). Two things make
 * that impossible to settle by reading the stylesheet, and both were guessed wrong first:
 *
 * 1. **The grabber is not on the `<textarea>`.** naive-ui pins every textarea it renders to
 *    `resize: none; height: 100%` and puts `resize: vertical` on the WRAPPER
 *    (`.n-input--resizable .n-input-wrapper`, `input.cssr.mjs`). So reading
 *    `getComputedStyle(textarea).resize` reports `none` for a field that resizes perfectly
 *    and for one that cannot resize at all — the first probe written for this did exactly
 *    that and would have "proved" that ClusterEditModal's working kubeconfig box was broken.
 *    `probe()` below walks the element AND its input wrapper and reports which one, if any,
 *    the browser will let the pointer drag.
 *
 * 2. **A grabber that is drawn is not a grabber that holds.** naive-ui's `autosize` drives the
 *    height from a hidden mirror element on every input event, so a field can draw a grip and
 *    snap back the moment the reader types — a defect that is invisible in a screenshot and
 *    invisible in the stylesheet. Nothing here could see it, so this script drags and then
 *    TYPES A NEW LINE and re-measures. A height that does not survive that is reported as
 *    `NOT DURABLE`, which fails the run, because a pull the app silently undoes is worse than
 *    no pull at all: the reader believes the box is theirs to size.
 *
 * The drag is a real `page.mouse` drag on the host's bottom-right corner, not a `style.height`
 * assignment. An assignment cannot tell a grabber from no grabber — it works on `resize: none`
 * too (measured: 243px -> 600px on an autosize textarea with no grip anywhere).
 *
 * ## Usage
 *
 *   PORT=8093 node scripts/resize-check.mjs                # every scene, both themes
 *   PORT=8093 node scripts/resize-check.mjs --scene create-modal
 *   node scripts/resize-check.mjs --self-test              # check the instrument first
 *
 * Exits 1 when a field that should be pullable is not, or a pulled height does not survive.
 *
 * It is an ON-DEMAND tool, not a gate: against the current tree it exits 1 on the two Form-tab
 * fields, because a keystroke there freezes the page (**GH#334**, present on the unchanged build
 * too). Those rows say `FROZE ON TYPING` and still report the height the drag reached, so the
 * resizability question is answered even where the durability question cannot be.
 */
import { createRequire } from 'node:module';

import { BASE_URL, loadAverage, open, runPrepare, setTheme } from './lib/kw-playwright.mjs';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const DRAG_PX = Number(process.env.DRAG_PX ?? 120);
// A native drag lands within a pixel or two of the request; anything looser would let a field
// that ignored the drag pass because something else nudged its layout.
const DRAG_TOLERANCE = 12;
// After typing, the host must still be within this of where the drag left it. naive's autosize
// moves in whole line-heights (~18px), so this cannot be loose.
const DURABLE_TOLERANCE = 4;

/**
 * Scenes: how to bring a resizable field on screen, and what to measure once it is there.
 *
 * `sel` names the naive `.n-input` ROOT (or a raw textarea). Never name the inner
 * `textarea` — see the header: it always reports `resize: none`.
 */
const SCENES = {
  'create-modal': {
    prepare: 'leaf:Config/Config Maps;click:.content-head button:has-text("Create");wait:800',
    fields: [{ label: 'CreateModal manifest', sel: '.n-modal .n-input--textarea' }],
  },
  'cluster-kubeconfig': {
    // NOT `goto:/clusters` — the SPA has no deep links at all (`SpaController` maps only `/`,
    // `/ui`, `/ui/`), so that path returns Boot's Whitelabel 404 and the scene screenshots an
    // error page while reporting a click timeout. The rail's "All clusters" tile is the way in.
    prepare: 'click:[aria-label="All clusters"];wait:900;click:button:has-text("Add cluster");wait:700',
    fields: [{ label: 'ClusterEditModal kubeconfig', sel: '.n-modal .n-input--textarea' }],
  },
  'form-tab': {
    prepare:
      'leaf:Config/Config Maps;click:td:nth-child(2);wait:1000;' +
      'click:.n-drawer .n-tabs-tab:has-text("YAML");wait:800;' +
      'click:button:has-text("Edit ⤢");wait:1200;' +
      'click:.yaml-editor-modal .n-tabs-tab:has-text("Form");wait:800',
    fields: [
      { label: 'KeyValueEditor value (data)', sel: '.form-section:has(h4:text-is("Data")) .kv-row .n-input:nth-child(2)' },
      { label: 'KeyValueEditor value (annotations)', sel: '.form-section:has(h4:text-is("Annotations")) .kv-row .n-input:nth-child(2)' },
    ],
  },
  // A Secret's values are MASKED by default, and a masked field is a single-line password input
  // that cannot be a textarea. So the scene reveals one first (the app's own Show button) and
  // measures what the reader gets after that explicit act. Without the reveal step this scene
  // would correctly report NO GRABBER — which is the designed behaviour, not a defect.
  'secret-form': {
    prepare:
      'leaf:Config/Secrets;click:td:nth-child(2);wait:1000;' +
      'click:.n-drawer .n-tabs-tab:has-text("YAML");wait:800;' +
      'click:button:has-text("Edit ⤢");wait:1200;' +
      'click:.yaml-editor-modal .n-tabs-tab:has-text("Form");wait:800;' +
      'click:.kv-actions button:has-text("Show");wait:400',
    fields: [{ label: 'KeyValueEditor secret value (revealed)', sel: '.kv-row .n-input:nth-child(2)' }],
  },
  'helm-values': {
    // The chart row's action is a `⋮` dropdown, not a button labelled Install.
    prepare:
      'leaf:Helm/Charts;wait:1500;click:.n-data-table-tbody tr:first-child button;wait:800;' +
      'click:.n-dropdown-option:has-text("Install");wait:1800',
    fields: [{ label: 'HelmValuesEditor values', sel: '.n-modal .n-input--textarea' }],
  },
};

async function probe(page, sel, label) {
  const el = page.locator(sel).first();
  if (!(await el.count())) return { label, status: 'ABSENT', note: sel };
  if (!(await el.isVisible().catch(() => false))) return { label, status: 'ABSENT', note: 'present but not visible' };

  // The element the pointer can actually drag: the match itself, or the input wrapper naive
  // puts `resize` on. `null` when nothing in that pair is resizable — which is the finding,
  // not an error.
  const host = await el.evaluateHandle((root) => {
    for (const e of [root, ...root.querySelectorAll('.n-input-wrapper, .n-input__textarea')]) {
      const r = getComputedStyle(e).resize;
      if (r && r !== 'none') return e;
    }
    return null;
  });
  const hostEl = host.asElement();
  if (!hostEl) return { label, status: 'NO GRABBER', note: 'neither the field nor its wrapper is resizable' };

  const axis = await hostEl.evaluate((e) => getComputedStyle(e).resize);
  const box0 = await hostEl.boundingBox();
  // Drag the bottom-right corner. 3px in from each edge is inside the native grip.
  await page.mouse.move(box0.x + box0.width - 3, box0.y + box0.height - 3);
  await page.mouse.down();
  await page.mouse.move(box0.x + box0.width - 3, box0.y + box0.height - 3 + DRAG_PX, { steps: 15 });
  await page.mouse.up();
  await page.waitForTimeout(300);
  const box1 = await hostEl.boundingBox();
  const grew = Math.round(box1.height - box0.height);
  if (Math.abs(grew - DRAG_PX) > DRAG_TOLERANCE) {
    return { label, status: 'DRAG IGNORED', note: `resize:${axis}, asked +${DRAG_PX}px, moved +${grew}px` };
  }

  // Type a NEW LINE, not a character: autosize is computed in whole rows, so a character that
  // does not change the row count cannot expose a field that snaps back. The first version of
  // this check typed one `x` and reported a durable height for a box that was never tested.
  // The match may BE the field (a raw textarea) or CONTAIN it (a naive `.n-input` root). An
  // earlier version always looked for a descendant, and the self-test's raw-textarea control
  // timed out waiting for a `textarea` inside a `textarea`.
  const ta = (await el.evaluate((e) => e.matches('textarea, input')))
    ? el
    : el.locator('textarea, input').first();

  // The typing phase is bounded and its failure is a RESULT, not an exception. A keystroke in
  // the editor's Form tab spins the page until the renderer stops answering (GH#334 — it does
  // this on the unchanged build too), and an unguarded `type()` therefore took the whole run
  // down with "Target page, context or browser has been closed" after ~50 minutes, naming no
  // scene. A checker that an application bug can hang is a checker that stops being run.
  let box2;
  try {
    box2 = await Promise.race([
      (async () => {
        await ta.click({ timeout: 8000 });
        await page.keyboard.press('End');
        await page.keyboard.type('\nzzz');
        await page.waitForTimeout(500);
        return await hostEl.boundingBox();
      })(),
      new Promise((_, reject) => setTimeout(() => reject(new Error('page stopped responding')), 20000)),
    ]);
  } catch (e) {
    return { label, status: 'FROZE ON TYPING', note: `pulled to ${Math.round(box1.height)}px, then ${e.message}` };
  }

  const drift = Math.round(box2.height - box1.height);
  const status = Math.abs(drift) > DURABLE_TOLERANCE ? 'NOT DURABLE' : 'OK';
  return {
    label,
    status,
    note: `resize:${axis}  ${Math.round(box0.height)}px --drag--> ${Math.round(box1.height)}px --type--> ${Math.round(box2.height)}px`,
  };
}

/**
 * Positive controls. Written BEFORE trusting a single app number, because two of the three
 * failure modes here look identical to a passing run from the outside: a drag that silently
 * does nothing, and a height that is quietly restored.
 *
 * `ok` must pass; `none` and `snapback` must FAIL, and the run reports the instrument broken
 * if they do not — a checker that cannot fire is not a checker.
 */
const SELF_TEST_PAGE = `
<style>
  body { margin: 0; padding: 20px; font: 14px sans-serif; }
  textarea { display:block; width: 400px; height: 120px; margin-bottom: 24px; box-sizing: border-box; }
  #ok { resize: vertical; }
  #none { resize: none; }
  #snapback { resize: vertical; }
  /* naive's own shape: the grip is on the WRAPPER and the textarea is pinned inside it. */
  #naive .n-input-wrapper { resize: vertical; width: 400px; height: 120px; overflow: hidden; box-sizing: border-box; }
  #naive textarea { resize: none; height: 100%; width: 100%; margin: 0; }
</style>
<div class="n-input" id="w-ok"><textarea id="ok">line
line</textarea></div>
<div class="n-input" id="w-none"><textarea id="none">line
line</textarea></div>
<div class="n-input" id="w-snap"><textarea id="snapback">line
line</textarea></div>
<div class="n-input" id="naive"><div class="n-input-wrapper"><textarea>line
line</textarea></div></div>
<script>
  // Stands in for naive's autosize: re-drives the height on every input event, so a drag is
  // undone the instant the reader types. This is the case the whole script exists to catch.
  const s = document.getElementById('snapback');
  s.addEventListener('input', () => { s.style.height = '120px'; });
</script>`;

async function selfTest() {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 900, height: 900 } });
  await page.setContent(SELF_TEST_PAGE);
  const want = { '#ok': 'OK', '#none': 'NO GRABBER', '#snapback': 'NOT DURABLE', '#naive': 'OK' };
  let bad = 0;
  for (const [sel, expect] of Object.entries(want)) {
    const r = await probe(page, sel, sel);
    const pass = r.status === expect;
    if (!pass) bad++;
    console.log(`  ${pass ? 'ok  ' : 'FAIL'}  ${sel.padEnd(12)} expected ${expect.padEnd(12)} got ${r.status.padEnd(12)} ${r.note ?? ''}`);
  }
  await browser.close();
  console.log(bad ? `\nself-test FAILED (${bad}) — do not trust this run` : '\nself-test passed — the checker fires on both failure modes');
  return bad;
}

async function main() {
  const args = process.argv.slice(2);
  if (args.includes('--self-test')) process.exit((await selfTest()) ? 1 : 0);

  const only = args.includes('--scene') ? args[args.indexOf('--scene') + 1] : null;
  const scenes = only ? { [only]: SCENES[only] } : SCENES;
  if (only && !SCENES[only]) throw new Error(`unknown scene '${only}' — one of: ${Object.keys(SCENES).join(', ')}`);

  console.log(`resize-check  ${BASE_URL}  load ${loadAverage().toFixed(2)}\n`);
  const rows = [];
  for (const theme of ['dark', 'light']) {
    for (const [name, scene] of Object.entries(scenes)) {
      // A fresh page per scene: these scenes open modals over modals, and a scene that
      // inherited the previous one's overlay would measure the wrong dialog with no error.
      const { browser, page } = await open({ view: 'wide' });
      try {
        await setTheme(page, theme);
        await runPrepare(page, scene.prepare);
        for (const [i, f] of scene.fields.entries()) {
          const r = await probe(page, f.sel, f.label);
          record({ theme, scene: name, ...r });
          // A page that has stopped answering cannot answer about the NEXT field either, and
          // the calls that ask have no timeout of their own — so the run hung after correctly
          // reporting the first field. Abandon the rest of the scene and say so.
          if (r.status === 'FROZE ON TYPING') {
            for (const rest of scene.fields.slice(i + 1)) {
              record({ theme, scene: name, label: rest.label, status: 'NOT REACHED', note: 'the page froze on an earlier field' });
            }
            break;
          }
        }
      } catch (e) {
        record({ theme, scene: name, label: '(scene)', status: 'SCENE FAILED', note: e.message.split('\n')[0] });
      } finally {
        // Bounded, because closing a browser whose renderer has stopped answering can itself
        // hang — which is how a run that had already MEASURED everything still died at the
        // wrapper's `timeout` with nothing but its header on stdout.
        await Promise.race([browser.close().catch(() => {}), new Promise((r) => setTimeout(r, 15000))]);
      }
    }
  }

  function record(r) {
    rows.push(r);
    // Printed as it is measured, not collected and printed at the end. Three runs against a
    // surface that freezes the page (GH#334) were killed by their own outer timeout and lost
    // every row they had already established — a buffered report is no report at all when the
    // thing being measured is why the run does not finish.
    console.log(`${r.theme.padEnd(6)} ${r.scene.padEnd(20)} ${String(r.label).padEnd(34)} ${r.status.padEnd(15)} ${r.note ?? ''}`);
  }
  const bad = rows.filter((r) => r.status !== 'OK');
  console.log(`\n${rows.length - bad.length}/${rows.length} pullable and durable`);
  // ABSENT and SCENE FAILED are failures too: a selector that is not on screen measured
  // nothing, and a run of "nothing" reads exactly like a clean sweep.
  process.exit(bad.length ? 1 : 0);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});

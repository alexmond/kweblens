#!/usr/bin/env node
/**
 * Click every state on a category overview card and check that the list it opens
 * shows exactly the objects the card counted (GH#336, verified for #340's pages).
 *
 * WHY THIS EXISTS, AND WHY IT READS THREE NUMBERS RATHER THAN ONE.
 *
 * The defect this epic is about is not a layout one and no screenshot shows it: the card
 * says `3 Pending`, the link opens a list showing 2, and nothing on screen admits the
 * discrepancy. Comparing the filter box's own "N of M" against the card is necessary but
 * NOT sufficient — the header is the list agreeing with itself, so it would keep agreeing
 * with a filter that selected the wrong set. So this reads:
 *
 *   1. the number printed ON the card's state line       (`.ov-state-n`)
 *   2. the "N of M" the list header shows on arrival     (`.count`)
 *   3. the number of rows the table actually renders     (`tbody tr`)
 *
 * and fails when any two disagree. (3) is what catches a header computed from a different
 * collection than the one being drawn.
 *
 * Two traps met while writing it, both now handled rather than documented as caveats:
 *
 * - **A state line is only a link when it has objects in it** (#338: `0 Failed` is text).
 *   A run that "found no links" therefore has to say so rather than pass — a category whose
 *   cards are all empty measures nothing, and looks identical to a category whose links are
 *   broken.
 * - **The list is virtualised**, so counting `tbody tr` once counts the rendered WINDOW, not
 *   the collection. (3) is therefore collected by scrolling the body to the end and counting
 *   DISTINCT rows — see `drawnRows`. It used to be skipped for anything over a page instead,
 *   which meant the number that catches a wrong set was only ever read on short lists.
 *
 * Usage:
 *   PORT=8094 CLUSTER_NS=kwfx-network node scripts/state-link-check.mjs network storage config
 */
import { createRequire } from 'node:module';

import { BASE_URL, USER, PASS, VIEWPORTS, openLeaf, setTheme } from './lib/kw-playwright.mjs';

// The account-wide install, resolved through NODE_PATH — never a local node_modules, or the
// browser build stops matching the library (see the playwright skill's preflight).
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const CATEGORIES = process.argv.slice(2).filter((a) => !a.startsWith('-'));
const NAMESPACE = process.env.CLUSTER_NS ?? '';
const THEMES = (process.env.THEMES ?? 'dark,light').split(',');

// `cluster` was missing until #357 put Nodes and Namespaces into the Status column: the Cluster
// overview's cards have been clickable since #339, so the one category this could not check was
// the one whose two kinds still rendered a second opinion beside the chip.
const CATEGORY_LABEL = { cluster: 'Cluster', network: 'Network', storage: 'Storage', config: 'Config' };

async function openOverview(page, category) {
  // Through the SHARED openLeaf, qualified `Category/Overview`. Both halves matter: every
  // category has a leaf called `Overview`, so an unqualified lookup silently opens the
  // Cluster one; and this file must not grow its own nav walker, which is the mistake three
  // separate scripts here have already made (the skill's 2026-08-12 perf-sweep entry).
  await openLeaf(page, `${CATEGORY_LABEL[category] ?? category}/Overview`);
  await page.waitForSelector('.ov-cards .ov-card', { timeout: 15000 });
  await page.waitForTimeout(400);
}

/**
 * The namespace filter is a naive-ui `NSelect`, NOT a native `<select>` — the first version
 * of this called `selectOption` on `.bar-filter select`, found nothing, and reported "no
 * namespace selector on the top bar" about a control that is right there. It has to be
 * clicked open and its option picked out of the floating menu.
 */
async function setNamespace(page) {
  if (!NAMESPACE) {
    return;
  }
  const control = page.locator('.bar-filter').first().locator('.n-base-selection');
  await control.click();
  const option = page.locator('.n-base-select-option').filter({ hasText: new RegExp(`^${NAMESPACE}$`) });
  // ...and its menu is a VIRTUAL list, so an option below the fold is not merely off screen,
  // it is not in the DOM. Scroll until it is rendered rather than waiting for it to appear.
  for (let i = 0; i < 60 && (await option.count()) === 0; i++) {
    await page.evaluate(() => {
      const list = document.querySelector('.n-virtual-list');
      if (list) {
        list.scrollTop += 200;
      }
    });
    await page.waitForTimeout(120);
  }
  await option.first().click({ timeout: 10000 });
  await page.waitForTimeout(1500);
  const shown = await page.locator('.bar-filter').first().innerText();
  if (!shown.includes(NAMESPACE)) {
    throw new Error(`namespace filter did not take: shows ${JSON.stringify(shown)}`);
  }
}

/** Every state line that is a link, with the number printed on it. */
async function stateLinks(page) {
  return page.$$eval('.ov-card', (cards) =>
    cards.flatMap((card) => {
      const kind = card.querySelector('.ov-kind')?.textContent?.trim() ?? '?';
      return [...card.querySelectorAll('.ov-state-line')].map((line, i) => ({
        kind,
        index: i,
        label: line.querySelector('.ov-state-l')?.textContent?.trim() ?? '',
        count: Number(line.querySelector('.ov-state-n')?.textContent?.trim() ?? 'NaN'),
        link: line.tagName === 'BUTTON',
      }));
    }),
  );
}

/**
 * How many DISTINCT rows the table draws, by scrolling its virtual body to the end and
 * collecting each row's name+namespace.
 *
 * Counting `tbody tr` once counts the rendered WINDOW, not the list: naive's data table
 * virtualises, so `50 of 66` drew 19 rows and the run reported the epic's own page as broken
 * — while `45 of 100` drew all 45 and passed. Whether the third number could be trusted
 * therefore depended on how many rows happened to fit the viewport, which is exactly the kind
 * of instrument this file exists to not be. An earlier version papered over it with a
 * `filtered <= PAGE_SIZE` skip, which both let a genuinely wrong long list through unchecked
 * and still failed at the boundary.
 */
async function drawnRows(page) {
  const seen = new Set();
  const collect = async () =>
    (
      await page.$$eval('.n-data-table-tbody tr', (rows) =>
        rows.map((tr) =>
          [...tr.querySelectorAll('td')]
            .slice(1, 3)
            .map((td) => td.innerText.trim())
            .join('/'),
        ),
      )
    ).forEach((k) => seen.add(k));

  await collect();
  for (let i = 0; i < 400; i++) {
    const more = await page.evaluate(() => {
      // The element that OVERFLOWS, not the first plausible wrapper. Naive's virtual body is
      // `.v-vl`; `.n-data-table-base-table-body` is present and does not scroll, so an
      // ordered guess-list that named it first assigned scrollTop to a non-scroller, saw no
      // movement, and reported "nothing more to scroll" on the first iteration — a silent
      // no-op wearing the shape of a completed walk.
      const el = [...document.querySelectorAll('.v-vl, .n-data-table-base-table-body')].find(
        (e) => e.scrollHeight > e.clientHeight + 4,
      );
      if (!el) return false;
      const before = el.scrollTop;
      el.scrollTop = before + el.clientHeight * 0.8;
      return el.scrollTop > before;
    });
    await page.waitForTimeout(90);
    await collect();
    if (!more) break;
  }
  seen.delete('/');
  return seen.size;
}

/** What the list says about itself after the link opened it. */
async function listReading(page) {
  await page.waitForSelector('.content-head .count', { timeout: 15000 });
  await page.waitForTimeout(600);
  const head = await page.evaluate(() => ({
    query: document.querySelector('.content-head input')?.value ?? '',
    count: document.querySelector('.content-head .count')?.textContent?.trim() ?? '',
    window: document.querySelectorAll('.n-data-table-tbody tr').length,
  }));
  return { ...head, rows: await drawnRows(page) };
}

/** "12 of 66 items" -> 12; "66 items" -> 66. */
function filteredFromLabel(label) {
  const of = /^\s*(\d+)\s+of\s+(\d+)/.exec(label);
  if (of) {
    return Number(of[1]);
  }
  const plain = /^\s*(\d+)/.exec(label);
  return plain ? Number(plain[1]) : NaN;
}

async function run() {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: VIEWPORTS.normal, ignoreHTTPSErrors: true });
  await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' });
  // Reads are open, so no sign-in is needed for any of this; the login modal is dismissed
  // if the instance is in closed mode.
  const signIn = page.locator('text=Sign in').first();
  if ((await signIn.count()) > 0 && (await signIn.isVisible().catch(() => false))) {
    await signIn.click();
    await page.fill('input[type=text]', USER).catch(() => {});
    await page.fill('input[type=password]', PASS).catch(() => {});
    await page.keyboard.press('Enter');
    await page.waitForTimeout(1000);
  }
  await setNamespace(page);

  let checked = 0;
  let failures = 0;
  for (const theme of THEMES) {
    await setTheme(page, theme);
    for (const category of CATEGORIES) {
      await openOverview(page, category);
      const lines = await stateLinks(page);
      const links = lines.filter((l) => l.link);
      console.log(
        `\n== ${category} [${theme}] ns=${NAMESPACE || '(all)'} — ` +
          `${links.length} of ${lines.length} state lines are links`,
      );
      for (const line of lines.filter((l) => !l.link)) {
        console.log(`   (text) ${line.count} ${line.label} on ${line.kind} — not a link`);
      }
      for (const [i, line] of links.entries()) {
        await openOverview(page, category);
        const all = await page.$$('.ov-state-line');
        const target = (await stateLinks(page)).filter((l) => l.link)[i];
        const nth = (await stateLinks(page)).findIndex(
          (l) => l.kind === target.kind && l.label === target.label,
        );
        await all[nth].click();
        const list = await listReading(page);
        const filtered = filteredFromLabel(list.count);
        const agree = filtered === line.count && list.rows === line.count;
        checked++;
        if (!agree) {
          failures++;
        }
        const rowNote = `${list.rows} rows drawn (${list.window} in the window)`;
        console.log(
          `   ${agree ? 'OK  ' : 'FAIL'} ${line.kind}: card ${line.count} ${line.label} -> ` +
            `header "${list.count}" (${filtered}), ${rowNote}, query ${JSON.stringify(list.query)}`,
        );
      }
      if (links.length === 0) {
        console.log('   !! no state on this category is a link — nothing was measured');
        failures++;
      }
    }
  }
  await browser.close();
  console.log(`\n${checked} state links checked, ${failures} problems`);
  process.exit(failures ? 1 : 0);
}

run().catch((e) => {
  console.error(e);
  process.exit(1);
});

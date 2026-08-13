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
 * - **The list is paginated and virtualised**, so counting `tbody tr` counts the PAGE, not
 *   the collection. The comparison against (3) is therefore only made when the header says
 *   the filtered count is at most what one page holds; otherwise (3) is printed and skipped,
 *   with the reason, instead of failing a correct list for being long.
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
/** Naive's data table draws one page; a filtered count above this is not compared to rows. */
const PAGE_SIZE = Number(process.env.PAGE_SIZE ?? 50);

const CATEGORY_LABEL = { network: 'Network', storage: 'Storage', config: 'Config' };

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

/** What the list says about itself after the link opened it. */
async function listReading(page) {
  await page.waitForSelector('.content-head .count', { timeout: 15000 });
  await page.waitForTimeout(600);
  return page.evaluate(() => ({
    query: document.querySelector('.content-head input')?.value ?? '',
    count: document.querySelector('.content-head .count')?.textContent?.trim() ?? '',
    rows: document.querySelectorAll('.n-data-table-tbody tr').length,
  }));
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
        const rowsComparable = filtered <= PAGE_SIZE;
        const agree = filtered === line.count && (!rowsComparable || list.rows === line.count);
        checked++;
        if (!agree) {
          failures++;
        }
        const rowNote = rowsComparable ? `${list.rows} rows` : `${list.rows} rows (page only, not compared)`;
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

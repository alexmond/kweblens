#!/usr/bin/env node
//
// Capture kweblens screenshots reproducibly — named viewport(s) x theme(s).
//
// This exists because screenshots here were taken ad hoc, always at roughly the same
// width and usually in one theme, and that shaped what got found. A 338-character prose
// line (#235) survived weeks of captures because every one was ~1400px wide. Black-on-
// black stat cards (1.34:1) survived because the captures were light-mode. The defect
// was always in the product; the blind spot was in the capture.
//
// So the default is the matrix, not one image: capture wide AND narrow, dark AND light,
// and look at all of them.
//
// Usage (needs a running instance — scripts/dev-run.sh, never `java -jar`):
//   export NODE_PATH="${NODE_PATH:-$HOME/.local/lib/playwright/node_modules}"
//   node scripts/ui-shot.mjs                                  # matrix of the shell
//   node scripts/ui-shot.mjs --leaf Pods                      # a nav leaf
//   node scripts/ui-shot.mjs --view wide --theme dark         # one cell of the matrix
//   node scripts/ui-shot.mjs --full                           # full page, not viewport
//   PREPARE='click:.n-data-table-tbody tr' node scripts/ui-shot.mjs --leaf Pods
//
// Options:
//   --leaf <label>    click a nav leaf first (exact label, e.g. "Replica Sets")
//   --path <path>     go to a path under the base URL instead
//   --view <names>    comma-separated: narrow(1024) normal(1400) wide(1900). default all
//   --theme <names>   comma-separated: light dark.                        default both
//   --out <dir>       output dir. default .playwright/shots (gitignored)
//   --name <prefix>   file name prefix. default derived from --leaf/--path
//   --full            full-page capture rather than just the viewport
//
// Env: PORT, BASE_URL, KWEBLENS_USER/PASS, PREPARE (see scripts/lib/kw-playwright.mjs).
//
// Prints the path of every image written, one per line, so a caller can read them back.

import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

import { BASE_URL, loadAverage, open, openLeaf, runPrepare, setTheme, VIEWPORTS } from './lib/kw-playwright.mjs';

const argv = process.argv.slice(2);
const flag = (name, fallback = null) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : fallback;
};
const has = (name) => argv.includes(`--${name}`);

const leaf = flag('leaf');
const path = flag('path');
const views = (flag('view') ?? Object.keys(VIEWPORTS).join(',')).split(',').map((s) => s.trim());
const themes = (flag('theme') ?? 'light,dark').split(',').map((s) => s.trim());
const outDir = resolve(flag('out') ?? '.playwright/shots');
const fullPage = has('full');
const slug = (s) => s.replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '').toLowerCase();
const prefix = flag('name') ?? (leaf ? slug(leaf) : path ? slug(path) : 'shell');

mkdirSync(outDir, { recursive: true });

const load = loadAverage();
if (load > 8) {
  console.error(`!!  load average ${load.toFixed(1)} — layout and colour in these shots are`);
  console.error('!!  still valid, but do NOT read anything about speed or rendering from them.');
}

const written = [];
for (const view of views) {
  const { browser, page } = await open({ view, url: path ? new URL(path, BASE_URL).href : BASE_URL });
  try {
    for (const theme of themes) {
      await setTheme(page, theme);
      if (leaf) await openLeaf(page, leaf);
      await runPrepare(page, process.env.PREPARE);
      await page.waitForTimeout(400);
      const file = `${outDir}/${prefix}-${view}-${theme}.png`;
      await page.screenshot({ path: file, fullPage });
      written.push(file);
      console.log(file);
    }
  } finally {
    await browser.close();
  }
}

if (!written.length) {
  console.error('no screenshots written — check --view/--theme');
  process.exit(1);
}
console.error(
  `\n${written.length} image(s) in ${outDir}. Read ALL of them, and sweep each whole image, ` +
    'not only the thing you came for.',
);

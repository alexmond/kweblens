/**
 * Switch cluster and prove that nothing from the previous one is still on screen.
 *
 * WHY THIS EXISTS
 *
 * GH#323 was found by sweeping a screenshot taken for something else. Switching from a healthy
 * cluster to one whose API server was not there left `DiagnosisPanel`'s header reading
 * "11 critical, 19 warning" and the Helm nav badge reading "50" — the previous cluster's real
 * numbers — beside an error saying this cluster could not be reached. Every other tool here
 * would have passed it: the layout was fine, the contrast was fine, nothing overflowed, the
 * page loaded fast. The defect is not in how a value looks, it is in WHICH CLUSTER it is about,
 * and no script measured that.
 *
 * THE INSTRUMENT PROBLEM, AND HOW THIS AVOIDS IT
 *
 * "The number is the same as the last cluster's" is not on its own evidence of staleness — two
 * clusters can honestly both have 3 namespaces, and a checker that fails on coincidence gets
 * ignored. Two things make the verdict provenance rather than coincidence:
 *
 * 1. The cluster being switched TO must be one that CANNOT ANSWER (an API server that is not
 *    listening — this box has several dead `kind-*` contexts). A failed read is confirmed on
 *    screen before any verdict is given; if the target answers, the run scores nothing rather
 *    than inventing a verdict.
 * 2. A COLD CONTROL. After the switch the page is reloaded, which lands straight on the target
 *    (the shell remembers the last cluster) with nothing to carry from anywhere, and whatever
 *    the target shows on that cold load is subtracted. The first version had no control and
 *    called `Charts = 48` and `Repositories = 2` carried over — they are not: charts are listed
 *    from the SERVER's configured repositories, so `…/helm/charts` returns 200 with the same 48
 *    for a cluster whose API server is not there. Equality alone would have failed a fix that
 *    works, which is how a checker stops being read.
 *
 * WHAT IT SAMPLES
 *
 * Every cluster-scoped claim the shell keeps on screen across a switch: the nav badges (which is
 * where the Helm count lives), the Diagnosis header count line, the overview stat cards, and the
 * nav title. Samples are taken repeatedly for SETTLE_MS, because the bug is a WINDOW — the
 * previous cluster's Helm badge survived exactly as long as the new cluster's requests were in
 * flight, which against a dead API server is the whole 20-second timeout. A single sample taken
 * after everything settled would have missed it.
 *
 *   scripts/dev-run.sh --port 8099
 *   export NODE_PATH=$HOME/.local/lib/playwright/node_modules
 *   PORT=8099 FROM=default TO=kind-jhelm666 node scripts/cluster-switch-check.mjs
 *
 * FROM/TO default to the first two ids from `GET /api/v1/clusters`. Never assume an id
 * `default` exists — `ClusterBootstrap` names clusters after kubeconfig contexts.
 */
import { execFileSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { BASE_URL, PORT, open } from './lib/kw-playwright.mjs';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/**
 * Refuse to measure a server that is not the one this checkout built.
 *
 * Several agents share this box and each runs `dev-run.sh`, which STOPS whatever is on the port
 * before starting its own. A run of this script against :8099 produced a clean pass and then, a
 * few minutes later, a confident "the diagnosis header is carried over" — both against someone
 * else's build, because their `dev-run.sh` had taken the port in between. Nothing in the output
 * said so: an app is an app. So the port's owner is resolved to a pid and its working directory
 * compared with this checkout before a single number is read. Set EXPECT_CWD to override, or
 * `EXPECT_CWD=any` to skip when the server is genuinely elsewhere.
 */
function assertOurServer() {
  const want = process.env.EXPECT_CWD ?? REPO;
  if (want === 'any') return;
  const pids = execFileSync('bash', ['-c', `ss -lptnH "sport = :${PORT}" | grep -o 'pid=[0-9]*' | cut -d= -f2`])
    .toString()
    .split('\n')
    .filter(Boolean);
  if (!pids.length) throw new Error(`nothing is listening on :${PORT} — start it with scripts/dev-run.sh --port ${PORT}`);
  const owners = pids.map((p) => [p, execFileSync('readlink', [`/proc/${p}/cwd`]).toString().trim()]);
  if (!owners.some(([, cwd]) => cwd === want)) {
    throw new Error(
      `:${PORT} is served from ${owners.map(([p, c]) => `${c} (pid ${p})`).join(', ')}, not ${want}. ` +
        `Another agent's dev-run.sh has taken the port. Start yours on a free one and pass PORT=…`,
    );
  }
  console.log(`serving :${PORT} from ${want} (pid ${owners.find(([, c]) => c === want)[0]})`);
}

const FROM = process.env.FROM ?? '';
const TO = process.env.TO ?? '';
// Long enough to OUTLAST the failure it depends on. The first version sampled for 12s and
// reported `NOT SCORED — the cluster answered`, because an API server that is not listening
// takes the client's full ~20s timeout to say so: the run ended while the requests were still
// in flight and read "no error yet" as "no error". A check whose verdict is gated on a failure
// must run longer than the failure takes to arrive, or its green line means nothing.
const SETTLE_MS = Number(process.env.SETTLE_MS ?? 45000);
const STEP_MS = Number(process.env.STEP_MS ?? 1500);
const SHOT_DIR = process.env.SHOT_DIR ?? '.playwright';

/** Everything on screen that is a claim ABOUT THE CURRENT CLUSTER. */
const probe = (page) =>
  page.evaluate(() => {
    const text = (el) => (el?.textContent ?? '').trim().replace(/\s+/g, ' ');
    const badges = {};
    document.querySelectorAll('.leaf').forEach((leaf) => {
      const b = leaf.querySelector('.nav-badge');
      if (b) badges[text(leaf.querySelector('.leaf-label'))] = text(b);
    });
    // Categories too. The first version read only leaves — and a collapsed category shows its
    // own summed badge with every leaf hidden, so `HELM 50`, the exact badge GH#323 names, was
    // invisible to a run that reported on the Helm counts. Read what is ON SCREEN, not the level
    // of the tree you happened to think in.
    document.querySelectorAll('.group > summary').forEach((s) => {
      const b = s.querySelector('.nav-badge');
      if (b && text(b) !== '') badges[`${text(s.querySelector('.cat-label'))} (category)`] = text(b);
    });
    const cards = {};
    document.querySelectorAll('.ov-head').forEach((h) => {
      cards[text(h.querySelector('.ov-kind'))] = text(h.querySelector('.ov-num'));
    });
    return {
      navTitle: text(document.querySelector('.nav-title')),
      badges,
      cards,
      diagnosis: text(document.querySelector('.dx-count')),
      // Whether this cluster has visibly FAILED to answer. Without it, "the number is gone"
      // and "the page has not rendered yet" look the same.
      failed: document.querySelectorAll('.error-notice, .failure-notice').length > 0,
    };
  });

/** Values from `before` that are still displayed under the same selector in `after`. */
function carried(before, after) {
  const out = [];
  const cmp = (group, a, b) => {
    Object.entries(a).forEach(([k, v]) => {
      if (v !== '' && b[k] === v) out.push(`${group} ${k} = ${v}`);
    });
  };
  cmp('badge', before.badges, after.badges);
  cmp('card', before.cards, after.cards);
  if (before.diagnosis !== '' && before.diagnosis === after.diagnosis) {
    out.push(`diagnosis header = ${before.diagnosis}`);
  }
  return out;
}

async function clusterIds() {
  const res = await fetch(new URL('/api/v1/clusters', BASE_URL));
  if (!res.ok) throw new Error(`GET /api/v1/clusters -> ${res.status}`);
  return (await res.json()).map((c) => c.id);
}

async function selectCluster(page, id) {
  const tile = page.locator(`.rail .tile[title="${id}"]`);
  if (!(await tile.count())) {
    // The rail shows a bounded number of clusters; the rest live behind the Clusters page.
    await page.click('.tile-more');
    await page.waitForTimeout(600);
    await page.locator(`.cl-row:has-text("${id}") button, tr:has-text("${id}") button`).first().click();
  } else {
    await tile.first().click();
  }
}

const main = async () => {
  assertOurServer();
  const ids = await clusterIds();
  const from = FROM || ids[0];
  const to = TO || ids.find((i) => i !== from);
  if (!from || !to) throw new Error(`need two clusters, got: ${ids.join(', ') || '(none)'}`);
  console.log(`kweblens on :${PORT} — switching ${from} -> ${to}\n`);

  const { browser, page } = await open({ view: 'wide' });
  try {
    await selectCluster(page, from);
    await page.waitForTimeout(6000);
    const before = await probe(page);
    console.log(`[${from}] nav title "${before.navTitle}"`);
    console.log(`[${from}] badges    ${JSON.stringify(before.badges)}`);
    console.log(`[${from}] cards     ${JSON.stringify(before.cards)}`);
    console.log(`[${from}] diagnosis "${before.diagnosis}"\n`);
    await page.screenshot({ path: `${SHOT_DIR}/switch-before-${from}.png` });

    await selectCluster(page, to);
    let worst = [];
    let sawFailure = false;
    for (let t = STEP_MS; t <= SETTLE_MS; t += STEP_MS) {
      await page.waitForTimeout(STEP_MS);
      const after = await probe(page);
      sawFailure ||= after.failed;
      const stale = carried(before, after);
      console.log(
        `t+${String(t).padStart(5)}ms  title "${after.navTitle}"  failed=${after.failed}  carried-over: ${
          stale.length ? stale.join(' | ') : 'none'
        }`,
      );
      if (stale.length > worst.length) worst = stale;
    }
    await page.screenshot({ path: `${SHOT_DIR}/switch-after-${to}.png` });
    console.log(`\nscreenshots: ${SHOT_DIR}/switch-before-${from}.png, ${SHOT_DIR}/switch-after-${to}.png`);

    // The control: the same target reached with nothing to carry. Anything it shows here that
    // matches `from` is cluster-independent or coincidence, and is not the bug.
    // NOT `networkidle`: the shell holds an SSE watch open, so the network never goes idle and
    // the reload times out after 30s having actually loaded the page.
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(SETTLE_MS);
    const cold = await probe(page);
    if (cold.navTitle !== to) {
      throw new Error(`cold control landed on "${cold.navTitle}", not "${to}" — control not established`);
    }
    const innocent = new Set(carried(before, cold));
    if (innocent.size) {
      console.log(`\ncold control on "${to}" shows these independently of "${from}" — not carried over:`);
      innocent.forEach((s) => console.log(`  ${s}`));
    }
    worst = worst.filter((s) => !innocent.has(s));

    if (!sawFailure) {
      console.log(
        `\nNOT SCORED — "${to}" answered, so an equal value could be a coincidence rather than a` +
          ` carry-over. Point TO at a cluster whose API server is not listening.`,
      );
      return 0;
    }
    if (worst.length) {
      console.log(`\nFAIL — "${to}" could not be reached, yet it displayed ${worst.length} value(s) from "${from}":`);
      worst.forEach((s) => console.log(`  ${s}`));
      return 1;
    }
    console.log(`\nOK — "${to}" could not be reached and claimed nothing from "${from}".`);
    return 0;
  } finally {
    await browser.close();
  }
};

main().then(
  (code) => process.exit(code),
  (e) => {
    console.error(e);
    process.exit(2);
  },
);

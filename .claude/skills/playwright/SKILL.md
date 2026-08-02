---
name: playwright
description: >-
  Drive the kweblens UI with Playwright — capture screenshots, measure geometry and
  overflow, check WCAG contrast, sweep for hangs — and run the surrounding dev scripts
  (start, gate, watch CI, deploy). Use whenever a task needs to SEE or MEASURE the running
  app rather than read its source: a visual bug report, a layout or colour change, "does
  this look right", a responsive question, a perf claim. Every script lives in `scripts/`
  and is specific to this project. Self-improving: when a run misleads you, fix the script
  AND record the miss below.
---

# Driving kweblens with Playwright

Every claim about how this UI looks or behaves must come from a **number taken off the
running app**, not from reading CSS and reasoning forward. That rule is not fastidiousness
— it is the accumulated result of getting it wrong: a badge shipped at 1.93:1, a command
palette styled twice at 3.02:1 and 3.80:1 while looking fine, a drawer whose close button
was 34px off-screen, and 338-character prose lines that survived weeks of screenshots.
None of those were visible to careful looking. All of them were one measurement away.

## Preflight — every script here needs both of these

```bash
scripts/dev-run.sh                 # or --sim for no cluster; NEVER `java -jar`
export NODE_PATH=$HOME/.local/lib/playwright/node_modules
```

- **`dev-run.sh`, not `java -jar`.** With no admin password set, `SecurityConfig`
  generates one per run and only logs it, so `admin`/`admin` silently stops working. The
  script passes the dev credentials and fails loudly if one is generated anyway. It also
  rebuilds when the jar is older than the sources — without that you measure code you did
  not write (this cost a full validation cycle on #228).
- **The account-wide Playwright install**, resolved via `NODE_PATH`. Never
  `npm i playwright` into this repo: the browser build has to match the shared install.
  In a non-interactive shell that has not sourced `~/.bashrc`, set it defensively.

## The scripts

Everything in `scripts/`. Each exists because doing it by hand went wrong at least once,
and the reason is in its own header comment — read that before changing one.

| Script | Reach for it when |
|---|---|
| `dev-run.sh` | You need something to drive. `--sim` (no cluster), `--ai`, `--files`, `--port`, `--stop`. |
| `ui-shot.mjs` | You need to *see* it. Captures the viewport × theme matrix, not one image. |
| `ui-measure.mjs` | You need geometry: box, overflow vs container, characters per line. Exits 1 over budget. |
| `contrast-check.mjs` | You touched `styles.css`. WCAG in both themes, composited properly. Exits 1 under the floor. |
| `perf-sweep.mjs` | You changed how a list renders. Walks every nav leaf, fails on slow loads or main-thread hangs. |
| `lib/kw-playwright.mjs` | You are writing a new browser script. Sign-in, themes, viewports, `PREPARE`, nav. |
| `dev-verify.sh` | Before every commit. Format + full reactor. Green here means green on the PR. |
| `dev-test.sh` | A targeted `-Dtest` run while iterating. |
| `pr-watch.sh` | Waiting on CI. `--merge` squash-merges only if everything passed. |
| `deploy-k8s.sh` | Deploying. Argument-driven, no environment defaults — see `docs/deployment.md`. |

## Recipes

```bash
# See it — the whole matrix, then read every image
node scripts/ui-shot.mjs                                  # shell: 3 widths x 2 themes
node scripts/ui-shot.mjs --leaf Pods --view wide          # one nav leaf
node scripts/ui-shot.mjs --path /clusters --full          # full page, not just viewport
PREPARE='click:.n-data-table-tbody tr' node scripts/ui-shot.mjs --leaf Pods   # open the drawer first

# Measure it — settles "is it cut off / too wide / too long a line"
node scripts/ui-measure.mjs --view wide '.n-drawer' '.drawer-title'
node scripts/ui-measure.mjs --view narrow --leaf Pods '.n-data-table'

# Colour — both themes, exits non-zero under AA
node scripts/contrast-check.mjs
node scripts/contrast-check.mjs '.leaf.active' '.ov-card'
PREPARE='press:Control+k' node scripts/contrast-check.mjs '.palette-row.active'

# Hangs and slow loads
node scripts/perf-sweep.mjs
ONLY='Replica Sets,Pods' BLOCK_MS=800 node scripts/perf-sweep.mjs
```

`PREPARE` brings a surface on screen before it is sampled — `press:` `click:`
`fill:<sel>=<text>` `upload:<sel>=<path>` `wait:<ms>` `goto:<path>`, semicolon-separated.
Prefix a step with `?` to skip it when its selector is absent; that matters because
`PREPARE` runs once per theme and per viewport, and a step like signing in applies only
the first time — without `?` the second pass waits for a modal that is already dealt with
until it times out and takes the run with it.

## Rules that keep the results true

**An absent selector is a failed run, not a pass.** `contrast-check` prints `not present`
and `ui-measure` prints `absent` rather than quietly succeeding. A screenful of those means
you measured nothing. Use `--leaf` / `--path` / `PREPARE` and measure again.

**Look at both themes and both extremes of width.** Every default here is a matrix for
that reason. A dark-mode-only defect and a wide-viewport-only defect have both shipped.

**Beware the oracle.** When a measurement contradicts sound reasoning, suspect the
measurement first. Every wrong conclusion in this project's history came from a broken
instrument, not broken maths: a sample point that landed on a glyph, a comparison of
encoded PNG bytes rather than decoded pixels, a `0.5em` glyph-width guess that was 20% out,
a machine under load. Build a positive control — a case whose answer you already know —
before believing a tool that has just told you something surprising.

**Screenshots taken under load are not evidence of slowness.** `ui-shot.mjs` prints a
warning above load average 8. Layout and colour survive load; timings do not. A "hang at
3000 objects" here turned out to be a load average of 19 from a concurrent agent — the
same shell rendered in 1.1s.

**Sweep the whole image.** You captured it to look at one thing; read all of it. Anything
unrelated and genuinely visible gets a GitHub issue and a todo entry, not a fix inside the
current change. (The `screenshot-sweep` skill carries the full checklist.)

## Self-improvement — prose *and* scripts

This skill is wrong until something slips past it, and so is every script it lists.

**When a run misleads you** — a false positive, a false pass, a number that turned out to
be an artefact:

1. **Fix the script**, not just your reading of it. A tool that needs a caveat to be
   trusted will eventually be trusted without the caveat.
2. **Record the reason in the script's own header comment**, next to the code it explains,
   in the form "an earlier version did X, which produced Y". That is why those headers are
   long; they are the reason the tools are believed.
3. **Add a dated line to Learnings below**, so the next person meets the trap before it
   costs them a cycle.

**When a defect is found that these tools could have caught but did not**, add the check.
If `ui-measure` should have flagged it, extend `ui-measure`. If no script covers that class
of thing at all, that is a new script — put it in `scripts/`, give it a header explaining
what went wrong without it, and add a row to the table above and to `scripts/README.md`.

Do not delete Learnings entries. Once an entry's fix is established in a script, it can be
compressed to one line, but the script change stays.

## Learnings

Format: `- YYYY-MM-DD — what happened → what changed.`

- 2026-08-02 — `contrast-check` read `color(srgb 0.89 0.91 0.93 / 0.75)` — which is what
  Naive UI's controls actually compute to — as channels 0.89/255, i.e. near-black, and
  reported near-white text as a **1.42:1 FAIL** (#245). → Both parsers in the file now scale
  srgb floats to 0-255, and refuse outright on colour spaces that need a real gamut
  conversion rather than guessing. `getComputedStyle` does not always answer in `rgb()`.
- 2026-08-02 — Same tool, separate bug, found while verifying the fix above: `behind()`
  walks *ancestors* for the backdrop, but Naive paints a select's white box as a **sibling**
  of the input. The walk climbed past it to the dark top bar and called 12.16:1 a 1.21:1
  FAIL. Decoded pixel said `rgb(255,255,255)`; the tool said `rgb(27,42,51)`. → Filed as
  **#250** with the evidence and a fix (sample the rendered pixel instead of deriving the
  backdrop from the DOM) rather than rewritten in passing — this instrument has now been
  wrong three times, and hasty is how it got there. **Until #250 lands, treat any reading
  taken through a Naive control as suspect and check it against a decoded pixel.**
- 2026-08-02 — `ui-measure`'s first version estimated characters-per-line as
  `width / (fontSize * 0.5)`. Against a known 900px of 14px monospace (8.40px per glyph) it
  read 129 where the truth was 107 — **20.6% out, in the direction that invents defects**.
  → It now lays the actual string out once in the element's own font and divides; a
  positive control against ground truth reads 0.0% error. **Never ship a metric without a
  case whose answer you already know.**
- 2026-08-02 — The same script called `.content-col` a "229 chars/line DEFECT". It holds no
  prose at all: `textContent` concatenates every descendant, so a layout container measures
  as one enormous line. → Only **direct** text nodes count now. A checker that fails things
  that are fine gets ignored, which costs more than not having it.
- 2026-08-02 — `dev-run.sh` built only when the jar was *absent*, so a post-merge run served
  a jar from before the merge and a rewritten LLM prompt looked like it had not taken (#228,
  #243). → It now compares mtimes and says which path triggered the rebuild. **Confirm the
  binary is newer than the change before concluding the change did nothing.**
- 2026-08-02 — `perf-sweep`'s nav discovery predates the collapsible nav (#237): with the
  tree collapsed and the state remembered in prefs, every leaf lookup fails as "not found".
  → `lib/kw-playwright.mjs`'s `discoverLeaves` re-expands the rail tile first. **A UI feature
  that hides things breaks every tool that walks them.**
- 2026-08-01 — Overview stat cards measured 1.34:1 in dark mode and were reported twice by
  the user before being investigated; `contrast-check`'s watchlist had `.ov-card.danger`,
  which matched a `<div>` and passed, while the clickable cards were `<button>`s. → The base
  class is on the watchlist now. **A `<button>` does not inherit `color`, so an element that
  changes tag by state changes colour by state.**
- 2026-08-01 — An earlier `contrast-check` stopped at the first non-transparent ancestor and
  treated it as opaque, so nested tints read far too dark and it failed text that was fine —
  a real 8.12:1 reported as 1.78:1. → It composites every translucent layer down to the first
  opaque one. Verified against decoded pixels: computed `rgb(41,63,80)` vs the browser's
  `rgb(40,63,79)`.

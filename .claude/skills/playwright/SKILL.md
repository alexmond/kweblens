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
| `ui-measure.mjs` | You need geometry: box, overflow vs container, characters per line, a word the browser broke mid-word or that spills past its own padding box, what an ellipsis is cutting (sub-pixel), two labels that truncate to the same string, a pill squeezed below its own label, width a row leaves unused, controls that share a line without agreeing where they sit on it. Exits 1 over budget; `--style` rebuilds a fixed defect so a check can be watched to fire; `--self-test` checks the instrument. |
| `contrast-check.mjs` | You touched `styles.css`. WCAG in both themes, backdrop decoded from the rendered pixels. Exits 1 under the floor; `--self-test` checks the instrument itself. |
| `perf-sweep.mjs` | You changed how a list renders. Walks every nav leaf, fails on slow loads or main-thread hangs. |
| `resize-check.mjs` | You changed a multiline field. Proves the corner grabber exists AND that a pulled height survives typing. `--self-test` checks the instrument. |
| `cluster-switch-check.mjs` | You touched per-cluster state. Switches cluster and fails if a value from the previous one is still on screen. |
| `state-link-check.mjs` | You touched an overview card, a state vocabulary, or the `status:` filter. Clicks every state and fails unless the card's number, the list header's `N of M` and the rows drawn are all the same number. |
| `drawer-persist-check.mjs` | You touched the detail drawer, or anything that re-renders the shell. Opens the drawer ONCE, then walks both themes and clicks behind it, and fails unless the same object and its `row-active` row are still there — and unless Escape and ✕ still close it. |
| `tui-drive.sh` | The surface is the **terminal**, not the browser. Runs `kweblens-tui`'s shipped jar in a real `tmux` pane, sends keys, returns the frame as text. `--self-check` proves the rig first. See "The other screen" below. |
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
node scripts/ui-shot.mjs --full                           # full page, not just viewport
PREPARE='click:.n-data-table-tbody tr' node scripts/ui-shot.mjs --leaf Pods   # open the drawer first

# Measure it — settles "is it cut off / too wide / too long a line"
node scripts/ui-measure.mjs --view wide '.n-drawer' '.drawer-title'
node scripts/ui-measure.mjs --view narrow --leaf Pods '.n-data-table'

# Colour — both themes, exits non-zero under AA
node scripts/contrast-check.mjs            # watchlist AND the scene walk (drawer, YAML, modal, hover)
node scripts/contrast-check.mjs '.leaf.active' '.ov-card'
PREPARE='press:Control+k' node scripts/contrast-check.mjs '.palette-row.active'
PREPARE='leaf:Pods;click:.n-data-table-tbody tr;hover:.btn' node scripts/contrast-check.mjs '.btn'

# Hangs and slow loads
node scripts/perf-sweep.mjs
ONLY='Replica Sets,Pods' BLOCK_MS=800 node scripts/perf-sweep.mjs

# Does clicking `15 No endpoints` open exactly those 15? (#336)
CLUSTER_NS=monitoring node scripts/state-link-check.mjs cluster network storage config

# Does the object you were reading survive the page around it? (#480)
node scripts/drawer-persist-check.mjs
```

`PREPARE` brings a surface on screen before it is sampled — `press:` `click:` `hover:<sel>`
`fill:<sel>=<text>` `upload:<sel>=<path>` `wait:<ms>` `scroll:<sel>`
`drawer:<px>` (drag the open detail drawer inside its own 360..1400 resize range),
`leaf:<nav label>` (or `leaf:<Category>/<label>` — `Overview` is a leaf in every category, and
an ambiguous label THROWS rather than opening the first match), semicolon-separated.
**Several verbs are runner-specific, and the wrong one throws `unknown PREPARE verb` rather
than being skipped:** `goto:<path>` exists only in the shared runner (`ui-shot`,
`ui-measure`); `close` (shut an open drawer/modal), `signin:<password>` / `signout` (the admin
login, idempotent), `deny` / `allow` and `partial` / `full` exist only in `contrast-check`'s
own copy. For a
signed-in surface under `ui-shot`/`ui-measure`, spell the login out as `?click:` + `?fill:`
steps.

`deny` stubs `GET …/access` with a refusal, so the controls the deployment's service account
is not allowed to use render greyed out with their reason (#354); `allow` takes the stub down.
`partial` does the same to `GET …/diagnose`, with a scope in which the audit could not see
everything — an `info` truncation notice and an RBAC read failure (#381); `full` takes it down.
**Those two are the only faked responses in this file, and each is faked because it cannot be
produced here** — an admin kubeconfig is allowed everything and lists its own bindings, and the
simulator answers no review at all, so without them those selectors are `not present` forever,
which reads as a pass.
Prefix a step with `?` to skip it when its selector is absent; that matters because
`PREPARE` runs once per theme and per viewport, and a step like signing in applies only
the first time — without `?` the second pass waits for a modal that is already dealt with
until it times out and takes the run with it.

## The other screen — `kweblens-tui` on a real terminal

Everything above drives a browser. `kweblens-tui` has no browser, and until #426 nothing drove
it on a terminal at all: every claim about it came from `FakeBackend`, which runs the real event
loop with no terminal under it. `scripts/tui-drive.sh` is the sibling instrument.

```bash
./mvnw -pl kweblens-tui -am package -DskipTests    # the SHIPPED exec jar is the point
scripts/tui-drive.sh --self-check                  # positive control; no jar, no cluster
scripts/tui-drive.sh --context k3stest --keys ':,svc,Enter' --hold 4 --quit
scripts/tui-drive.sh --context k3stest --size 80x20 --until 'every binding' --keys '?'
```

`tmux` (≥ 3.2) is the only requirement — no Python package, no npm install. **It is on demand
and never a gate**; a pty rig that is flaky in CI is worse than no rig (#423).

Three rules carry over from the browser side and one is new:

- **A rig that drives `TuiScreen` in-process is `FakeBackend` again.** The shipped jar and a real
  terminal are the parts that were never covered.
- **Prove the rig before believing it.** `--self-check` drives six known-answer cases through the
  identical pane: two cursor-addressed writes read back from the cells they were addressed to,
  the **alternate screen** (the app draws on it; a capture that only read the primary screen
  would call a perfect frame blank), `stty size`, an `ESC[c` reply reaching the program, and a
  keystroke arriving at its stdin.
- **Know which debts your terminal does not pay, and say so.** tmux 3.5a does **not** answer
  `ESC[?2027$p`, which is the app's very first output; the self-check reports that rather than
  failing on it, because the app renders regardless.
- **No frame is a failed measurement, not a pass** — the same rule as an absent selector. The
  script says so, `jcmd Thread.print`s every `java` under the pane, prints the parked threads and
  exits 1.

Captures land in `.tui/`, **gitignored like `.playwright/`** and for the same reason: a rendered
frame is a picture of a live cluster in text — context name, namespaces, node and object names.

## Rules that keep the results true

**An absent selector is a failed run, not a pass.** `contrast-check` prints `not present`,
`present, but no text of its own`, `covered by another layer` or `outside the viewport`, and
`ui-measure` prints `absent`, rather than quietly succeeding. A screenful of those means you
measured nothing. Use `--leaf` / `--path` / `PREPARE`, a wider `--view`, or close the drawer
that is sitting on top of what you asked for, and measure again.

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

- 2026-08-20 — **Three of #484's four surfaces had never been rendered by any run, and reaching
  them cost far more than the one-line fix each needed.** The pattern is now three deep: `deny`
  (#354), `partial` (#381) and now `refuse` (a 403 on a drawer relation — an admin kubeconfig is
  never refused a join) and `files:blocked` / `files:browse` (the pod file browser is off by
  default *and* the simulator has no container to attach to, so a listing can only fail and a
  file can never be opened). Three traps came out of building them, each now fixed in the script
  rather than remembered. **(a) A reload inside PREPARE undoes `openNav`, and the symptom is
  indistinguishable from a hang.** The `about` stub is the one that needs a reload — the shell
  reads the feature's gates once, at startup — and after it the rail's `<details>` come back from
  prefs, so `openLeafHere` fell back to CLICKING each shut summary: measured, a `leaf:Pods` that
  takes ~1.5 s everywhere else had not returned after **100 s**, and because the run prints
  nothing until it ends, the only evidence was the wall clock. `stubFilesFeature` now calls
  `openNav` after its reload, and `PREPARE_TRACE=1` prints each step with its duration, because
  *a slow chain and a stuck chain look identical*. **(b) A notice's container has no text of its
  own** — `.files-notice.blocked` is a `<div>` whose three `<p>`s carry every word, and this tool
  measures only elements with a direct text node, so the ticket's own selector reports `present,
  but no text of its own`: point the scene at the children. **(c) An invariant no scene can reach
  belongs in the gate, not the watchlist** — `textOnTint.test.ts` reads `styles.css` off disk and
  fails on any rule pairing `--<state>-fg` with `--<state>-tint`, which needs no browser, no
  cluster and no stub at all. Two self-inflicted ones worth not repeating: **`pgrep -f
  <pattern>` matches the waiting shell that contains the pattern**, so an `until ! pgrep -f …`
  waiter never fires and a `kill` on what it found killed the measurement it was waiting for
  (the 2026-08-07 entry below is the same trap from the other end); and **an in-place `sed`
  round-trip is not a way to build a control** — reverting `color: var(--warn-on-tint)` to
  rebuild the defect flipped three unrelated warn rules with it, and only `git diff` said so.

- 2026-08-19 — **#480's two candidates were both wrong, and one keyboard press said so.** The
  ticket (written off a good measurement) offered the `NConfigProvider` `:theme` prop
  re-rendering its subtree, or a watcher clearing `detail`. Neither: **activating the SAME
  toggle from the KEYBOARD** changed the theme light→dark with `.n-drawer` and
  `.n-data-table-tr.row-active` both still 1, while a **mouse click on the footer** — no theme
  change, no navigation — closed the drawer exactly as the toggle did. A maskless `NDrawer`
  registers evtd's `clickoutside` trap, which is **mousedown + mouseup**, and routes it into
  the mask-click handler whose only gate is `maskClosable`, defaulted true. So the discriminator
  was a gesture that produces the *effect* without the *pointer event*, and it cost one run.
  **When a symptom has two suspects, look for the input that separates them rather than the
  code that explains one of them** — a click carries two things at once (a pointer press and
  whatever the button does), and only one of them was guilty. → `drawer-persist-check.mjs`,
  which asserts the general rule (the object survives BOTH themes and an unrelated click, from
  ONE open) and, in the same run, that the drawer **still closes** on Escape and ✕ — "never
  closes" passes a survival check perfectly. Two traps it bakes in: every toggle asserts
  `kw-dark` actually flipped (a theme that did not change makes "it survived" vacuous), and the
  object's name is compared after every step, because #480's real loss was the SELECTION, not
  the panel. The 2026-08-18 entry below is now half stale: a probe still has to set the theme
  first *if it is measuring the pre-fix build*, but on this build the drawer survives a walk.

- 2026-08-19 — **The check that caught #482's regression caught it by luck, and the same run
  proved it: one theme measured the defect, the other measured a different element under the same
  name.** Retuning the warn tint turned a warn BADGE inside a warn ROW — two translucent layers of
  one tone, because `EventsPane` marks the row and badges its Type cell from the same token — from
  5.20:1 to **4.10:1**, and `.ov-sec .n-data-table-tbody .n-data-table-td` failed on it. But that
  selector names a CELL: the sampler takes the first descendant carrying text, which in dark was
  the badge and in **light was the cell's own message**, so the identical run reported the stack
  and did not report it, and the light half of a regression that had just been introduced was
  invisible. The third instance of #389's shape (`.n-tag`) and #393's (a Ready pill under the
  status pill's name), and the first where the *accident* was the thing that worked. → A named
  scene, `category overview: a warn badge inside a warn row`, selector
  `.ov-sec .n-data-table-tr.warn .status-badge.tone-warn`, with a `REQUIRED_WHEN` whose oracle is
  the warn ROW — the page saying a highlighted row is on screen means the badge in it must produce
  a ratio. Deliberately **not** in `FLOOR_OVERRIDE`: 5.5 is a claim about one tint over one panel,
  and this is a stack. Two other things that cost time in the same run: **a stylesheet comment sent
  the probe to the wrong surface** (it said the `warn` row class reaches "the Events list", which is
  a plain `ResourceTable` — the class comes from `EventsPane` and nowhere else, so a scene pointed
  at the Events leaf reported `not present` on a cluster with Warning events on every page; the
  comment is fixed), and **a colour is worth computing before it is measured** — `over()` plus the
  WCAG formula predicted every decoded pixel in this run to ±1 and reproduced the ticket's own
  7.42:1, which is what made "which alpha is even possible" a five-second question instead of a
  five-build one. **Arithmetic proposes; the decoded pixel disposes — and a per-tone name is what
  keeps the pixel attributable.**

- 2026-08-18 — **Two ways a scene can be pointed at a surface and still not have it on screen,
  met in one run (#478).** (1) **`scroll:` CENTRES an element taller than the viewport.** The
  2026-08-14 entry below records that it is `scrollIntoViewIfNeeded`, i.e. the minimal scroll;
  the case it does not cover is an element with no minimal scroll — Recent Events is **1027px in
  a 900px viewport**, so scrolling to the table put its header 63px above the fold and both
  selectors reported `outside the viewport`. The target has to be the part being measured
  (`.n-data-table-thead`), not the container it sits in; that is what the new `category overview:
  the events table` scene does, and its comment says why. (2) **The theme toggle CLOSES the detail
  drawer**, and it is not the overlay branch in `setTheme`: this drawer is non-modal, and the
  probe measured `.n-drawer-mask = 0` throughout while `.n-drawer` went 1 → 0 and
  `.n-data-table-tr.row-active` went 1 → 0 with it, i.e. the selection state is dropped. So a
  probe that opens the drawer ONCE and then loops themes measures a page with no drawer in both
  passes. `contrast-check`/`ui-shot` are immune only by accident — they run PREPARE inside the
  theme loop, so they re-open it per theme; a hand-written probe must set the theme FIRST and
  open the drawer per theme. Filed as **GH#480**, because losing the object you were reading to
  an unrelated click is a product defect and not only a rig one. **A state a scene set up before
  the theme loop is not a state the theme loop preserves — assert it is still there.**
- 2026-08-18 — **A probe read a row's colour straight after clicking it and reported the hover
  grey as "the selected row's colour".** Two mechanisms, both of which every scene here shares.
  (1) **A Playwright click leaves the pointer ON the element**, so a sample taken after
  `click:.n-data-table-tbody tr td:nth-child(2)` is a sample of a HOVERED row — measured
  `rgb(247, 247, 250)` where the rule under test paints `color(srgb 0.039 0.478 0.761 / 0.12)`.
  (2) **naive transitions `background-color`**, so a read taken within ~0.3 s of any change
  catches an interpolated `oklab(… / 0.89)` that is neither endpoint and looks like a colour
  nobody wrote. The fix in the probe was to park the pointer off the table and wait the
  transition out; in `contrast-check`'s new `selected row (drawer open)` scene it is a comment,
  because the rule that scene measures deliberately out-specifies naive's hover — if that half
  is ever removed, the scene starts measuring the hover grey under the selection's name.
  And the *hover* claim needed its own control: moving the mouse to a guessed coordinate and
  reporting "unchanged" is exactly what a MISSED hover reports. Asserting `row.matches(':hover')`
  in the same sample, plus hovering an unselected row and watching THAT one change, is what
  separated "selection wins" from "nothing hovered". **Ask the page whether the state you are
  measuring is actually on.**
- 2026-08-17 — **A bare `pty.fork()` reported `kweblens-tui` as producing no frames, and the
  reading was nearly "the app hangs on a terminal that is not the author's".** It was the rig,
  and by ONE variable: `pty.fork()` leaves the pty window size at **0×0** and nothing sets it, so
  JLine reports zero rows, the renderer has zero area, and the app correctly draws nothing.
  Measured on the same jar, same pipe pair, same unanswered DECRQM: **0 bytes of frame at 0×0,
  1 407 bytes and a full table after one `TIOCSWINSZ` to 44×132.** The two supporting
  observations were red herrings, both reproduced and both uninformative — the startup
  `ESC[?2027$p` is answered by *nothing* here (tmux 3.5a does not answer it either) and the app
  does not wait for it, and the empty `kweblens-tui.log` is what a **healthy** run leaves,
  because `logging.level.root` is `warn`. → `scripts/tui-drive.sh` (tmux, real emulator, real
  size), whose `--self-check` asserts the size, the alternate screen, the query/reply channel and
  key delivery before any claim about the app, and whose no-frame path dumps threads instead of
  concluding. **A silent app and a silent rig look identical; only a positive control separates
  them.**
- 2026-08-15 — **`--stop-stale` judged every JVM on the box against the tree that ran it, so it
  stopped other agents' servers.** `instances()` is box-wide on purpose; the staleness predicate
  used RELATIVE paths, i.e. "has *my* source changed since *your* process started". Measured: a
  brand-new worktree's `pom.xml` mtime is the checkout time, which is newer than every running
  instance, so **creating a worktree was enough to condemn all of them** — the user's :8080
  included. → Ownership per pid from `/proc/<pid>/cwd` (`checkout_of`), staleness against **that**
  tree, three-valued (`stale`/`fresh`/`unknown`), and unknown is never stopped. `--stop-all` got
  the same gate. `--list` stays box-wide. **Ask a question about a process against the tree that
  process came from — and when a check goes from reporting to acting, re-derive its scope.**
- 2026-08-15 — **A colour delivered INLINE carries no name in the DOM, so `contrast-check` measured
  whichever tone the row order put first and reported it as "the status pill".** GH#393:
  `StatusBadge` hands its tint to `NTag`'s `:color`, so no tone class existed to match — the
  finest available selector was `.n-tag`, and it sampled the danger tone on this box's cluster
  (5.62/6.56) and the warn tone on the simulator (4.51/8.06). Every tone that got covered was
  covered because the two environments disagreed, and the warn tone's **0.01 of margin** in light
  had been sitting under someone else's name. The `ok` tone had never been measured anywhere, and
  the reason turned out not to be the scene at all: `badgeTone` maps `ok` to no pill (#240), so an
  ok STATUS PILL cannot be rendered in any environment — an entry in the tone map that nothing
  could ask for, i.e. a permanently unmeasurable surface reading as a pass. → Three changes, and a
  trap found by the control. (a) `StatusBadge` carries `tone-*` **beside** the inline colour — a
  LABEL, styled by nothing, so the colour still has exactly one source; scenes now name each tone
  and the `ok` pair is measured where it is actually painted, on the list header's status chips
  (6.92 light / 8.52 dark, its first ratio ever). (b) A tone that is missing must FAIL, not print
  a note, but only where absence is really a defect: `REQUIRED_WHEN` names an ORACLE already on
  the page — the list's own status chip, drawn only for a state some row carries — so a warn chip
  with no measurable warn pill exits 1, while an all-healthy cluster requires nothing. (c) AA is a
  legal minimum, not a margin: `FLOOR_OVERRIDE` holds the tone family to **5.5** (floor + 1.0, the
  headroom the danger tone already had). The trap: the Status column is **not** the only one
  rendering this component — Ready badges `1/2` warn and `0/1` err from the same tokens and comes
  FIRST in DOM order, so the scene measured a Ready pill under the status pill's name and the
  `status:` filter it had just installed had no bearing on the sample. Same shape as GH#389's
  `.n-tag`, one level finer. Selectors are `[data-col-key=status] .status-badge.tone-*` now, and
  the fixture control keeps a tone-err **Ready** pill on the page that the status control must not
  find. **A selector that resolves anywhere on the row is not a selector for the column the scene
  narrowed — and the way to know is a scene where the two answers differ.**

- 2026-08-14 — **`row` counts flex LINES, so it reported the drawer header as fine before AND
  after #379 — a green line over a 14.34px step.** The expand toggle sat at `top=60` and Naive's
  close at `top=74.34`, on one flex line the whole time because `.n-drawer-header` is `nowrap`.
  Nothing else came close either: no overflow, no clipping, no mangled text, correct colours. A
  screenshot shows it only if you already know to look at two glyphs 14px apart, which is how it
  was eventually reported (#392). → A **`line`** report on `ui-measure`: the CONTROLS under a
  selector, clustered into lines by cross-axis overlap, failing when the controls on a line agree
  on none of top / centre / bottom by more than **2px** — unrounded, stated, with a 0.4px control
  that must stay quiet and a 3px one that must fire. Three false positives were found and killed
  while writing it, each of which would have made the check unusable: **(a)** with only overlap
  clustering, `.app` reported a nav category summary against a status chip 500px away in another
  column, because two tall columns' controls overlap on the cross axis all day — lines are now
  runs of controls **consecutive in the DOM**, plus a guard that the branch each takes from their
  common ancestor is part of that line rather than a column crossing it; **(b)** the hit test
  (`elementFromPoint` at each control's centre, folded in because #354 and #379 each wrote it ad
  hoc and threw it away) called three nav leaves "painted over" by the Collapse button — they
  were **scrolled out of `.nav-scroll`**, whose rect the window-only visibility check never
  consulted; **(c)** with the drawer open it reported twenty-two DEFECTs over `.app` — every
  control the drawer covers, which is what an overlay is FOR. A cover only fails when the thing
  on top shares normal flow with the control; an out-of-flow ancestor between them is a layer,
  printed and not failed. And **(d)**, the one that was not a false positive but a false
  POPULATION: 40 of the rail's 41 leaves had real 23px rects while their `<details>` were shut,
  because Chromium hides a closed one's content with `content-visibility`, which skips the
  subtree and leaves its LAST layout behind — so a collapsed nav was contributing forty ghost
  boxes to every reading, and the hit test was right to say something was painted over them.
  `checkVisibility({ contentVisibilityAuto: true, … })` is the filter: 1 of 41 visible collapsed,
  48 of 48 expanded. **`getBoundingClientRect()` is not evidence that anything is on screen.**
  The positive control is `--style`, a new flag that injects CSS into the
  running page: #379's removed declarations re-injected reproduce `top=60` vs `top=74.34` exactly,
  at 1400 and 1900, and the check names those two controls out of 125 on screen while staying
  silent on the same scene as the app serves it. **A check whose verdict does not change between
  the defect and the fix is worse than no check, and the only way to know is to rebuild the
  defect and watch it fire.**

- 2026-08-14 — **GH#394 fixed, and the mechanism was not the one the symptom suggested.** The
  guess was that the build's *rename* pulls the jar out from under the JVM. Measured: a rename is
  **survivable** — the JVM holds two fds on the jar, so it keeps reading the old inode and the
  instance is fine. What kills it is a step earlier: **maven-jar-plugin TRUNCATES the live inode
  in place** and writes the 600 KB thin jar into it, and only then does `spring-boot:repackage`
  rename that inode aside. Proof is in `/proc/<pid>/fd`, which moves from `…/kweblens.jar` to
  `…/kweblens.jar.original` across a build — the victim is left holding a jar with no
  `BOOT-INF/lib`. → `dev-run.sh` starts every instance from a per-port **copy**
  (`kweblens-run-<port>.jar`, `cp --reflink=auto`, 0 exclusive bytes on this box's btrfs), so the
  build cannot reach it; an insulated instance survived the identical build that had just left an
  exposed one with 16 `NoClassDefFoundError`s. `dev-verify.sh` refuses (exit 3, `--force` to
  override) when an instance is reading the file it is about to replace, naming pids and ports.
  Three further things fell out, each of which had been silently wrong: **(a) `/actuator/health`
  is NOT a liveness check for this** — on every wedged instance produced it kept answering `200`
  and `"status":"UP"` while the app served nothing, because its classes all load during startup;
  `--list` asks `/` as well, and only `serving` means serving. **(b) The PORT column was wrong
  whenever the simulator was on** — it took the first `lsof` LISTEN row, which is the sim's
  loopback API server, so `--port 8391` was listed as `37619`; it now reads `PORT=` from
  `/proc/<pid>/environ` and prefers the wildcard bind. **(c) A `JAR REPLACED` note fires before
  any symptom**, which is the case that matters — such an instance answers correctly until it
  needs a class it has not loaded, so it is *latently* dead and looks perfect. `--self-check`
  gained the two-directional control: a socket that accepts and never answers must not read as
  `serving` (a TCP-connect check would have passed it), and a real instance must. And **(d) the
  first `JAR REPLACED` accused a healthy instance**, caught only because that control was run
  against the fix rather than the bug: it asked "is the jar newer than the process", while
  `dev-run.sh` writes the per-port copy **7 ms** before forking the JVM and `ps -o lstart=` has
  one-second resolution — so on a perfectly good launch that comparison is a coin flip. It is now
  identity-based (the inode the JVM holds vs the inode at the path), with the mtime kept only as a
  30 s-grace fallback for an in-place overwrite. **A comparison between two events milliseconds
  apart, read through a one-second clock, is not a check.**
  **A wedge has no single presentation — hang, connection reset, non-200, and health-UP-but-
  nothing-else all appeared from the same cause — so probe the surface you measure, not the
  process.**

- 2026-08-14 — **A watchlist entry named a selector so broad it measured the element BEHIND the
  one it was for, and reported that element's ratio under the wrong name.** GH#389: `.n-tag` was
  on the list as "the drawer's `<NTag type="info">Helm</NTag>`", the component-library colour
  #269 fixed. It matches every Naive tag on the page, and the sampler takes the first match that
  carries text — a row's status pill in the table *behind* the open drawer. Against a live
  cluster that pill was 17px below the fold, so the row said `outside the viewport` and looked
  like a scene problem; **against the simulator it was on screen, so both watchlist rows reported
  the same numbers (8.06:1 / 4.51:1) and both looked like passes.** The green reading is the
  worse of the two: the tag the entry exists for had never once been on screen in either
  environment. Two independent causes, each hiding the other — and the second is a fixture fact
  no amount of scrolling would have fixed: `Managed By` renders off `meta.helm.sh/release-name`,
  which Helm writes on the objects a chart declares and *not* on the Pods a Deployment then
  creates, so on this box's cluster **0 of 93 pods carry it** against 8 of 100 ConfigMaps and 29
  of 66 Services. The scene was pointed at the one kind where the tag cannot exist. → The tag is
  measured as `.n-drawer .kv .n-tag` in a scene of its own, on Config Maps narrowed by the app's
  own label filter to `app.kubernetes.io/managed-by=Helm` (`8 of 100` live, every object on the
  simulator); the status pill gets its own scene too, because the `scroll:` that reaches it takes
  `.content-head` and `.count` off the top with it — two samples needing different scroll
  positions are two scenes. Both now read 5.42:1 dark / 7.68:1 light and 6.56/5.62 (live),
  8.06/4.51 (sim). **A selector that resolves anywhere on the page is not a selector for the
  surface a scene walked to; scope it to the surface, or it will silently measure whatever the
  DOM happens to put first.**
- 2026-08-14 — **Running `dev-verify.sh` broke both instances it was verifying, and `--list` went
  on calling them running.** The build replaces the fat jar under a live JVM, which loads classes
  lazily, so the simulator instance answered **HTTP 500 to every request** while keeping its
  listener and the live one kept its *process* after losing its listener —
  `NoClassDefFoundError: …$MatchComparator` / `…ThrowableProxy`. `dev-run.sh --list` matches on
  the jar path and `comm`, both still true of a JVM whose classloader can no longer find
  anything, so the instance list said `running` throughout. → Filed as **GH#394**. Same family as
  the provenance entries below: *the app answered* is not *my app answered*, and this time the
  build that broke it was mine. **FIXED** — see the entry above.
- 2026-08-14 — **The fix for that shipped a smaller version of the same defect, and the control
  written to prove the fix is what caught it.** `contrast-check` gained `WHY_ABSENT`, a reason
  per selector appended to an empty ratio so "not applicable in this instance" (`.btn` needs
  `--files`) stops reading identically to "the scene never reached the surface". The first
  version appended it to every note, and the control run — the same scene minus its `scroll:`
  step — came back `outside the viewport … needs a pod that is not healthy`: a broken scene
  wearing a configuration excuse, added by the thing built to remove exactly that confusion.
  → The reason is appended to `not present` and nothing else; `outside the viewport` and
  `covered by another layer` mean the element IS there and the scene failed, and may never
  borrow an excuse. **Run the control against your own fix, not only against the bug — a
  diagnostic message is code, and it can be wrong in the direction it was written to prevent.**
- 2026-08-14 — **A stubbed response installed mid-walk was never fetched, so the scene measured
  the LIVE data under the stubbed scene's name.** Verifying #381, `partial` (a `page.route` on
  `GET …/diagnose`) went in and the very next step navigated to the page that reads it — and the
  panel showed the cluster's real 41 findings, because `useAsyncData` keeps a value whose deps
  have not changed: navigating to a page you are already on refetches nothing, and a route only
  affects requests made after it. The readings looked *right* (the badge colours are the same
  either way, and this cluster happens to emit all three severities), which is what made it
  dangerous — the two findings the scene exists for, the ones that say the audit did not see
  everything, were never on screen at all. → Both diagnosis scenes hop through another leaf
  (`leaf:Pods`) before returning, which unmounts the panel and makes the return a real refetch.
  **A stub is only in force for requests that happen after it; when a scene installs one, make
  the component fetch again — and prove it by a detail of the payload that is on screen, not by
  a colour the real data would have produced too.**
- 2026-08-14 — **`scroll:` is `scrollIntoViewIfNeeded`, so it does the MINIMAL scroll and
  measures one card at a time.** The same run pointed at a four-card diagnosis list: scrolling to
  the list scrolled nothing (its top edge was already visible) and only the first card was
  sampled; scrolling to the last card left the first 17px above the fold, and a badge sits at the
  top of its card, so `.dx-sev-critical` reported `outside the viewport` — a failed sample that
  looks like a scene problem. → Two scenes with two scroll targets, chosen from a geometry probe
  rather than guessed. **When several selectors must be measured down one long surface, check
  what the scroll actually left on screen; one `scroll:` verb aligns one element to one edge.**

- 2026-08-14 — **`covered by another layer` was not a scene problem; it was the defect.** #354's
  refused menu item renders two lines (the action, then why the service account cannot use it),
  and `contrast-check` measured the first line at 7.09:1 and refused to measure the second.
  The instinct was to blame the scene — it is what every previous unmeasurable row here has been
  — but a probe said `elementFromPoint` at the middle of the reason returned **the NEXT option's
  label**: Naive pins `.n-dropdown-option`, `.n-dropdown-option-body` and `…__label` to a fixed
  `height: 34px` with a 34px line-height, so a two-line item does not grow the row, it overflows
  and the following item is painted across it. A screenshot of that reads as slightly tight
  spacing, not as two menu entries occupying the same pixels. Two things fell out of fixing it:
  the override needs `height: auto !important`, because Naive injects its component CSS into
  `<head>` at RUNTIME and beats an equal-specificity rule of ours whatever its selector (measured
  — `min-height` applied and `height` did not); and the scene needed a way to produce a verdict no
  cluster on this box will give. → `deny` / `allow` PREPARE verbs in `contrast-check`, stubbing
  `GET …/access` with the refusal shape `AccessEndpointsTest` pins. **`covered by another layer`
  means something IS on top — ask what, before assuming you pointed the tool at the wrong scene.**

- 2026-08-13 — **`state-link-check`'s third number was the rendered WINDOW, not the list, and the
  one category it could not open was the one under test.** Verifying #357, `50 Serving` failed
  with `50 of 66, 19 rows` while `45 Referenced` passed with all 45 — because `ResourceTable`
  turns virtual scrolling on at exactly 50 FILTERED rows, so whether the check's own oracle could
  be trusted depended on how many rows fitted the viewport. The `filtered <= PAGE_SIZE` guard
  written for that was worse than nothing: it let every long list through unchecked *and* still
  failed at the boundary. → `drawnRows()` scrolls the virtual body and counts DISTINCT rows
  (`109 rows drawn (19 in the window)`), and the skip is gone; `CATEGORY_LABEL` gained `cluster`,
  whose Nodes and Namespaces cards have been clickable since #339 and were never once checked.
  Its own first version was a silent no-op: an ordered guess-list named
  `.n-data-table-base-table-body` before `.v-vl`, and that element EXISTS and does not scroll, so
  assigning `scrollTop` did nothing, `more` came back false on iteration one, and a walk that
  never moved reported "nothing further to scroll". **Find the element that overflows; a wrapper
  that accepts `scrollTop` and ignores it fails exactly like a completed walk.**
- 2026-08-13 — **`ui-measure` reported `OVERFLOWS by 1px` on a pill that overflows by nothing.**
  The check compared a rounded box right (`Math.round(x) + Math.round(w)`) against a rounded
  clipper right, and two independent roundings of ONE sub-pixel edge disagree by up to a pixel —
  so every Status pill in a table, whose `.n-ellipsis` wrapper shrink-wraps it exactly, was a
  candidate false positive. The probe written to chase it answered `wanted 99.69px, got 99.69px,
  0 lost`. → `clipOver` is measured unrounded in the page and reported over a 0.5px threshold,
  with a control pair (`.over-clip` genuinely 80px past its clipper, `.flush-clip` sitting on the
  edge at 0.00px). Real loss is still `sliced` and `clipped`, which measure what is missing rather
  than where an edge lands. **Round for printing, never for comparing.**
- 2026-08-13 — **Nothing here could check the claim an entire epic is about: that a number you
  click opens exactly the objects it counted.** #340 wired the Network, Storage and Config cards
  to the `status:` filter, and every existing tool would have passed a card whose link opened the
  wrong set — it is neither a layout, colour, geometry nor timing defect, and no screenshot shows
  it. → `state-link-check.mjs`, which reads **three** numbers per state (the card's `.ov-state-n`,
  the header's `N of M`, and the rows the table actually draws) and fails when any two disagree.
  Two numbers would not have been enough: the header is the list agreeing with itself, so it keeps
  agreeing with a filter that selected the wrong rows. Three traps met writing it, each now in the
  script rather than a caveat: **(a)** the namespace filter is a naive `NSelect`, not a `<select>`,
  so `selectOption('.bar-filter select')` reported "no namespace selector on the top bar" about a
  control that is right there — **and its menu is a VIRTUAL list**, so an option below the fold is
  not in the DOM at all and has to be scrolled to, not waited for; **(b)** the run must FAIL when a
  category has no linked state, because a namespace with no Services measures nothing and looks
  identical to a category whose links are broken; **(c)** rows are only compared when the filtered
  count fits one page, or a correct long list fails for being long. The positive control is the
  server side: unwiring the context in `ObjectApiController` turns 5 of 6 hermetic endpoint tests
  red, and the live run's `1 of 3` readings become `0 of 3`.
- 2026-08-13 — **A "known fixed" instrument bug reappeared, and the fix was simply not on this
  branch.** A `perf-sweep` run for #340 reported the same 37/44 with `Persistent Volume\nClaims`
  and six more at `-1ms OVER BUDGET` that the 2026-08-12 entry below records as fixed — because
  that fix rode #341, which had not merged when this worktree was cut. → Nothing changed here.
  **A Learnings entry describes the fix, not your checkout: confirm the fix is on your branch
  before re-diagnosing, and never fix it a second time in a parallel branch — the merge conflict
  costs more than the run.**
- 2026-08-12 — **A pill was cut in half by its cell, and the only instrument that saw it was a
  300% crop of a screenshot.** #341 made the list's Status column render the server's state, so
  `CrashLoopBackOff` (a 119.38px `NTag`) landed in a 102.83px `.n-ellipsis` that hides its
  overflow: a square-ended red block reading `CrashLoopBackO`, no ellipsis anywhere, because
  `text-overflow` does not apply to a nested inline-block. **Every check passed, and a probe
  written to settle it agreed with them** — it measured `.n-tag__content`, which is exactly as
  wide as its text, so the element being cut was one level up and the numbers said
  `clientWidth === scrollWidth`, "nothing truncated". `clipped` needs a direct text node, and a
  wrapper whose only child is a pill has none; `chip` (#331) asks whether a pill *wrapped*, and
  this one did not. → A `sliced` line: the pill's own border box against the padding box of the
  ancestor that clips it, failing only when that ancestor is unreachable (an `overflow-x: auto`
  with real overflow is a scroller and nothing is lost). Four controls, one of which must fire;
  then run against the LIVE defect before the fix, where it names `16.55px of the pill cut off`,
  and silent after it. **When a measurement contradicts a screenshot, the element you measured
  may not be the element that is broken — walk up before believing "nothing truncated".**
- 2026-08-12 — **`perf-sweep` reported seven pages OVER BUDGET that it had merely failed to
  click.** #332 split a nav leaf's label into a head span and a tail span, so a two-word label's
  innerText now carries a newline — `Persistent Volume\nClaims` — and this script's own private
  leaf walker kept it, anchored a regex against it, matched nothing, and recorded the 4s click
  timeout as `LOAD -1ms … OVER BUDGET`. The shared `openLeaf` in `lib/kw-playwright.mjs` was
  fine the whole time (`--leaf 'Ingress Classes'` opened it), which is the **third** time this
  file's copy of a walker has drifted from the shared one. → Whitespace is collapsed on both
  sides of the match. 37/44 within budget → 44/44, with those seven measured rather than guessed
  at. **A nav change breaks every tool that walks the nav; and "could not measure" must never be
  printed in the same column as "too slow".**

- 2026-08-12 — **Every check here can pass an element that is visibly broken, because none of
  them asked whether it WANTED to be one line.** #331: the list header's items badge measured
  47×42px at `narrow` — "3" over "items", a rounded pill two lines high — and `box`, `overflow`,
  `words`, `clipped`, `twins` and `row` were all silent and all correct. Nothing overflowed (the
  row fits, at min-content), no word was too wide for its box ("items" fits in 47px), nothing was
  clipped (it wrapped instead of truncating). A flex item's automatic minimum is its
  **min-content**, and min-content for a short label is its longest WORD, so the algorithm may
  legally shrink a badge to the width of "items" and turn the space into a line break. The four
  earlier members of this family (#257, #278, #318, #326) all mangled TEXT and so were within
  reach of `words`/`clipped`; this one mangles a SHAPE, and nothing was watching shapes.
  → A `chip` line: a pill (self-painted background, rounded ends, ≤40 characters) that is
  painting more than one line **and** is narrower than its own max-content **and** whose parent
  had room for that max-content. All three conditions are load-bearing; the third is what keeps
  it honest, because a pill in a parent that genuinely cannot hold the label is doing the
  least-bad thing. Scoped to pills so the same header's `h1`, which wraps to three lines beside
  the badge and is fine, stays quiet. Four new `--self-test` controls, one of which must fire —
  and then it was run against a rebuild of the PRE-fix stylesheet, where it names both chips
  (`47.17px for 57.16px`, `67.75px for 102.06px`) before naming neither afterwards. **A check
  written after the fix proves only that it is quiet; rebuild the defect and watch it fire.**
- 2026-08-12 — **The issue's stated reproduction was not the cause, and it took one measurement
  to know.** #331 reported the badge wrapping "when the content column is squeezed — an open
  detail drawer at 1024px is enough". Measured with and without the drawer, `.content` is 729px
  wide either way: Naive's `NDrawer` is an OVERLAY, so it covers the content column and never
  narrows it. The reproduction is the 1024px viewport on its own. **Reproduce the numbers before
  adopting the report's explanation of them — a scene that reproduces a defect is not thereby the
  cause of it.**
- 2026-08-12 — **Not fitting a box is the QUESTION; the check has to name the damage.** #343:
  `words` failed an ordinary run — `"60" needs 11px in a 6px box` — on a page with 140
  `.nav-badge` matches. The badge is `min-width: 18px; padding: 0 6px`, so on its floor it is a
  **6px content box inside an 18px pill**; the digits spill 2.7px each side into padding and
  **no badge on the page exceeds its own pill** (worst case 6.55px inside it, 0 of 140 over the
  pill, 0 broken). Real per the check's own definition, invisible to a reader — and its message
  ("it must break mid-word") was wrong about the consequence, because digits have no break
  opportunity, so they overflow instead. → The population examined is unchanged; only the
  **verdict** moved. A run over its content box now has to be either **broke** (painting on more
  than one line, counted from the run's OWN client rects — the damage itself, not a proxy) or
  **spilled** (past the element's PADDING box, escaping the shape it paints in). Both are
  load-bearing: `word-break: break-all` shreds a run inside a 6px content box that the 18px pill
  would have held, so the padding box alone would open a hole. The absorbed case is still
  **printed**, never failed, because a check that silently drops a population it used to fail is
  indistinguishable from one that stopped looking. Three new controls, two must-fire; then run
  against a rebuild of the PRE-fix #326 stylesheet, where it still names
  `"ValidatingWebhookConfiguration" needs 203.09px in a 190px content box` and adds "the browser
  broke it mid-word", and is silent on the fix (190px/42px tall → 203px/21px). Ordinary run:
  140 matches, exit 1 → 140 matches, exit 0. **Loosening a check is only safe if the historical
  defects are rebuilt and watched to fire; and the standing rule is that a check must fail on
  damage a reader could see, not on a comparison that happens to be true.**

- 2026-08-11 — **Three separate ways to be wrong about one question: "can the reader pull this
  field taller?"** (a) **The property is not on the element you would read it from.** naive-ui
  pins every textarea it renders to `resize: none; height: 100%` and puts `resize: vertical` on
  the WRAPPER (`.n-input--resizable .n-input-wrapper`), so `getComputedStyle(textarea).resize`
  answers `none` for a box that resizes perfectly *and* for one that cannot — the first probe
  written here read exactly that and would have "proved" ClusterEditModal's working kubeconfig
  box broken. (b) **`style.height = …` is not a test of a grabber**: it moved an autosize
  textarea from 243px to 600px with no grip anywhere on screen, so the only honest drag is a
  real `page.mouse` drag on the corner. (c) **The suspected failure was the wrong failure.** The
  hypothesis was "autosize draws a grip and then snaps back"; measured, autosize means there is
  **no grip at all** (naive applies its resizable class only when `resizable && !autosize`), and
  the non-autosize path is durable — `:rows="8"` measured 192px → 312px → still 312px after
  typing a new line. → `scripts/resize-check.mjs`, which drags for real and then TYPES A NEW
  LINE before re-measuring, with four `--self-test` controls, two of which must fire (a
  `resize:none` box, and a box whose height is re-driven on `input` — the snap-back that was
  suspected here and turns out to live elsewhere). Its first run caught its own bug: the raw
  `<textarea>` control timed out waiting for a `textarea` *inside* a textarea. **Build the
  control before believing the hypothesis, and check what the framework does before believing
  the property.**
- 2026-08-11 — **`goto:` sent a signed-in run to ANOTHER agent's server, and the error named a
  button.** `runPrepare`'s `goto:` resolved against the module-level `BASE_URL` (fixed at import
  from `PORT`), while the run had been opened with an explicit `open({ url })` on :8093. A scene
  that navigated mid-way silently landed on :8080 — a different agent's instance, where it was
  not signed in — so the write-gated "Add cluster" button was never rendered and the scene
  reported a 30s click timeout. → `goto:` now resolves against `page.url()`, so a scene cannot
  change origin. **A mid-scene cross-origin hop is never what a scene means, and on this box the
  other origin is somebody else's build.**
- 2026-08-11 — **A recipe in this very file screenshotted a Boot Whitelabel 404 for weeks.**
  `--path /clusters` was documented here, but `SpaController` maps only `/`, `/ui` and `/ui/` —
  the SPA has **no deep links at all**, and the clusters page is reached by clicking the rail's
  `[aria-label="All clusters"]` tile. `--path` does not fail on a 404; it captures it, and a
  white page full of black serif text does not look like a kweblens screenshot only if someone
  opens the image. → The recipe is fixed and the tile is spelled out in `resize-check.mjs`'s
  scene. **A `--path` flag on an app with no routes is a footgun; reach surfaces by clicking.**

- 2026-08-10 — **`ui-measure`'s `words` check could not see a word inside a child element, and
  the surface it was pointed at keeps half its text there.** Fixing #326 (the drawer Overview's
  `.kv dd` shredding `ValidatingWebhookConfigurati`/`on` at `drawer:360`) the check fired
  correctly on the Kind row — a direct text node — while reporting **nothing at all** for the
  `Node`, `Service Account`, `Controlled By` and `Managed By` rows in the same list, whose
  values are rendered inside a `<button class="cell-link">` or an `NTag`. The restriction came
  from the chars-per-line check above it, where "direct text nodes only" is load-bearing (a
  layout container's `textContent` is the whole page as one "line") — but `words` splits into
  runs, and concatenating descendants cannot invent a longer WORD, only a longer line. So a
  clean `words` line on `.kv dd` meant "nothing wrong with the text I happened to be able to
  see", and the identical defect one DOM level down would have needed the child selector named
  by hand to be found at all. → `widestWord` walks every text node under each match and lays it
  out in **its own parent's** font. Two guards keep it conservative: runs are compared with the
  matched element's box even when the text sits in a narrower child (that can only under-report,
  never invent a defect), and a text node is skipped when anything between it and the match
  takes it out of that element's wrapping regime — `white-space: nowrap` (ellipsis, which is
  `clipped`'s job) or an `overflow-x` scroller such as `.mini-scroll`. Four new `--self-test`
  controls, one of which must fire. **When a check is written for one element, ask where that
  surface actually keeps its text before believing the check covers it.**
- 2026-08-10 — **A run measured another agent's build for ten minutes and produced a confident,
  wrong finding.** Verifying #323 on `:8099`, a first pass came back clean and a second, minutes
  later, reported the diagnosis counts as carried over. Both were right about what was on screen
  and wrong about whose screen it was: several agents share this box, `dev-run.sh` STOPS whatever
  is on the port before starting its own, and another worktree's server had taken :8099 in
  between. Nothing in the output says so — an app is an app, and the footer's build stamp is the
  only visible clue. → `cluster-switch-check.mjs` resolves the pid listening on `PORT` and
  compares `/proc/<pid>/cwd` with its own checkout before reading a single number, refusing to
  run otherwise (`EXPECT_CWD=any` to override). **On a shared box, "the app answered" is not
  "my app answered" — establish provenance before the first measurement, not after a surprise.**
- 2026-08-10 — **A new script, `cluster-switch-check.mjs`, for a defect class nothing here
  covered**: whether a value on screen is about the cluster named beside it (#323). Layout,
  colour, overflow and load time were all fine; the numbers belonged to the previous cluster.
  Its three instrument bugs, each of which produced a green or plausible line, are the reusable
  part: (a) **a check whose verdict is gated on a failure gave up before the failure arrived** —
  an API server that is not listening takes the client's full ~20 s timeout, and a 12 s window
  reported "the cluster answered, not scored"; (b) **equality was treated as proof of staleness**
  and failed a working fix on `Charts = 48` / `Repositories = 2`, which are listed from the
  SERVER's configured Helm repositories and so are identical for every cluster, reachable or not
  — now subtracted using a **cold control** (reload, which lands on the target with nothing to
  carry, and discount whatever it shows anyway); (c) **the badge the bug report actually named
  was invisible**, because the probe read `.leaf .nav-badge` while a collapsed category renders
  its own summed `.group > summary .nav-badge` with every leaf hidden. **Read what is on screen,
  not the level of the tree you were thinking in.**
- 2026-08-10 — **The same shared-box trap, met from the other end: `dev-run.sh` said
  "rebuilding", came up, and served the SPA from before the change.** Fixing #327 the nav
  rendered the pre-fix labels after a restart that had just claimed to rebuild, and the served
  stylesheet still carried the original `.leaf-label` rule — so a fixed layout measured as
  unfixed for a full cycle. Either cause produces it (a build that did not reach the jar, or
  the entry above: another checkout's server holding the port), and neither is visible from
  the browser. The mtime check answers *should I build*, which is not the question a
  measurement depends on — *is this process serving what I just wrote*. → `check_served_assets`
  compares the content-hashed asset names in the served `index.html` with
  `kweblens-ui/dist/index.html` after the health probe and says loudly when they differ, which
  catches a foreign instance as well as a stale build. Both halves controlled: quiet on the
  matching instance, firing when `dist` is doctored to name a bundle it is not serving. **"It
  rebuilt" is a claim about the build; the asset hash is a claim about the process you are
  about to measure — and only the second one is evidence.**
- 2026-08-10 — **Nothing here could see two rows rendering the same string, and that is a worse
  defect than any it could see.** #327: the rail truncated `VerticalPodAutoscaler` and
  `VerticalPodAutoscalerCheckpoint` to one `VerticalPodAuto…`, and `Validating Admission
  Policies` / `…Policy Bindings` to one `Validating Admissio…`. `clipped` reported the cut and
  passed it as designed — correctly, per label. But Kubernetes kinds are built by suffixing, so
  a tail-ellipsis removes precisely what distinguishes siblings: unlike #318/#326, where the
  text was mangled but survived, here the information is gone and clicking a row is a guess. It
  was found by reading a screenshot. → A `twins` line: each character's own rect against the
  element's content box, dropped runs replaced by `…`, and any two matches whose different text
  paints alike **fail** the run. Two traps met while writing it, both now controls: only
  TRUNCATED elements may be compared (eight identical `Overview` leaves are not a defect), and
  an element whose neighbour's text continues within 3px of its box edge is a **fragment** — the
  first version reported the two halves of the fixed split label as twins, i.e. invented a
  defect in the code that had just removed one. **A check that compares renderings has to be
  told what the reader reads as one string.**

- 2026-08-10 — **`ui-measure` called a visibly ellipsized label clean, because `scrollWidth`
  is an integer.** Fixing #318 (the drawer's kind eyebrow breaking as `PERSISTENTVO`/`LUME`)
  with a weighted `flex-shrink` left the kind 115.94px wide for a 116.33px word. Both
  `scrollWidth` and `clientWidth` round to 116, so the `content` line stayed silent and the
  run printed `nothing over budget` — while the screenshot taken thirty seconds later read
  `PERSISTENTVOLU…`, because **`text-overflow` does not drop 0.4px of text, it drops whole
  GLYPHS** to make room for the ellipsis. A sub-pixel miss costs two characters. The `words`
  check cannot cover this either, and correctly so: it skips `white-space: nowrap` by design,
  so **adding `nowrap` to stop a mid-word break also switches off the check that was watching
  the element.** → A `clipped` line measured in the browser's own sub-pixel geometry: a Range
  over the element's contents reports the FULL laid-out advance even when the paint is clipped
  (cross-checked against a detached clone at `width:auto` — 116.328125px both ways). A miss of
  tens of pixels is a designed truncation and is printed; a miss under 1px **fails** the run.
  Four new `--self-test` controls, two of which must fire, including a hairline case whose
  width is derived from the text itself (`calc(100% - 0.4px)` inside a shrink-wrapped parent)
  so it is 0.4px short in any font. **The screenshot caught what the measurement missed —
  which is the argument for taking both, and for reading the image even when a number has
  already said the thing is fine.**
- 2026-08-10 — **GH#320 is fixed, and the workaround that hid it has been removed.** Sign out
  now awaits `DELETE /api/v1/auth/session`, and a sign-in that presents credentials has them
  checked rather than riding a cookie, so the `signout` verb no longer calls `clearCookies()`
  — it clicks the app's own Sign out and nothing else. That is deliberate: clearing the cookie
  here would let a regression of the fix pass as a green run, whereas now the rejected sign-in
  scene goes straight back to three `not present` selectors if sign-out stops signing out.
  Verified in both themes on the fix (`.action-notice-*` at 5.16/5.16/8.24 dark, 5.62/5.62/12.61
  light — the light pass being exactly the one that used to measure nothing). **When a product
  bug is fixed, delete the script workaround that stood in for it; a workaround left behind is a
  regression detector switched off.**
- 2026-08-10 — **The exec terminal measured as broken twice while working perfectly, for two
  independent reasons.** (1) The first row of an unfiltered Pods list on this cluster is a
  distroless `cloudflared`: the WebSocket opened, stayed open, reported no error — and produced
  **zero frames**, which reads identically to "exec is broken". (2) After filtering the list to
  a pod that does have a shell, the keystrokes went into the **search box**, because opening the
  dock does not move focus into the terminal; the prompt arrived (1 frame, `$ `) and the command
  never did. → Drive the terminal as: filter to a pod known to have a shell, click
  `.xterm-screen` first, then type, then read `.xterm-rows` (`.dock-bodies` innerText is empty —
  the text lives deeper). The negative control that makes an empty read trustworthy is running
  the same walk **signed out**: no terminal opens at all, so "nothing in the terminal" and "no
  terminal" stop looking alike. **A socket that is open is not a socket that is carrying your
  data, and a surface that is on screen is not a surface that has focus.**

- 2026-08-10 — **`PREPARE`'s `fill:` could not type the app's own filter syntax.** Both
  dispatchers split `<selector>=<text>` on the **last** `=` — right about selectors
  (`input[type=password]` carries one) and wrong about values. The list header now takes a
  Kubernetes label selector, so `fill:.content-head input=app=web` became the selector
  `.content-head input=app` and the value `web`: a step that types the wrong thing into an
  element that does not exist and then fails naming a selector nobody wrote. → One shared
  `splitFill()` in `lib/kw-playwright.mjs`, used by both runners, splits at the first `=` at
  **bracket depth zero**; a five-case positive control covers the new case *and* the
  `input[type=password]=admin` one it must not break. **When a feature makes a character
  meaningful inside a text field, check that the tools which drive that field can still type
  it — a PREPARE verb that cannot express the surface under test silently limits what can be
  measured about it.**

- 2026-08-10 — **A scene whose whole point is a FAILED sign-in passed in the first theme and
  measured nothing in the second — because the failure had quietly become a success.** The new
  `ActionNotice` (roadmap R3) can only be rendered by a write that fails, and the one that costs
  nothing is a refused login, so the scene types a wrong password. In the dark pass, signed out,
  it worked. In the light pass an earlier scene had signed in, and **the app's own Sign out does
  not invalidate the `HttpSession`** — `loginSubmit` decides success by calling
  `verifySession()`, which rides the surviving cookie — so the wrong password returned 200, no
  notice rendered, and all three selectors printed `not present`: a failed measurement wearing
  the same face as a surface the app does not have. → A `signout` PREPARE verb that clicks Sign
  out **and clears cookies and reloads**, plus **GH#320** for the product bug it exposed. (That
  bug is fixed and the `clearCookies()` half is gone — see the entry above.)
  Two follow-on traps, both now in the scene's own comment: `signin:` cannot be reused to test
  the failure it exists to avoid, and the reload broke the *next* scene (four more unmeasured
  selectors, reported as a click timeout naming a leaf), so a scene that reloads goes **last**.
  **When a scene depends on being signed out, force that state rather than assuming the previous
  scene left you in it — and when a negative test can silently turn positive, that is a product
  bug before it is a script bug.**
- 2026-08-09 — **`--stop` reported success on a process that was still running.** `stop_port`
  sent SIGTERM, slept 2 s and printed its message unconditionally; a run left the JVM resident
  at over a gigabyte with the simulator's mock API-server port still bound, and only a later
  `--list` revealed it. The app closes cluster watches and log streams on the way down, so a
  clean shutdown is not instant — but "slow" and "ignored the signal" must not look the same
  from outside. → `terminate()` now signals, polls for exit, escalates to SIGKILL after 20 s
  **saying so**, re-checks, and returns non-zero if anything survives; `--stop`, `--stop-all`
  and `--stop-stale` all propagate that. **The first positive control for this was itself
  broken**: `bash -c 'trap "" TERM; sleep 300'` exec-optimises into `sleep`, which does not
  inherit the trap, so the "ignores SIGTERM" process died on SIGTERM and the escalation path
  was never exercised — the test passed while testing nothing. A trailing `; true` prevents the
  exec, and the control now asserts the pid really does survive a plain SIGTERM *before*
  trusting what the escalation does. Same rule as everywhere else here: **build the control,
  then check the control.**
- 2026-08-07 — **`dev-run.sh --list` reported "(none running)" against a server answering on
  :8080**, and `--stop-stale` / `--stop-all` therefore silently stopped nothing. The guard was
  `pgrep -x java -f "<jar pattern>"`, added after a `--stop-all` SIGTERMed its own shell
  (exit 144). Both readings are traps and they pull opposite ways: **bare `pgrep -f` matches
  the shell** that merely mentions the pattern, while **`-x` with `-f` matches nothing at
  all** — pgrep joins the command line with a trailing separator, so a whole-string anchored
  match cannot succeed however the pattern is written (the exact literal fails too). `-x` was
  not tightening the match, it was disabling it, and an empty instance list is indistinguishable
  from a clean machine. → `instances()` now matches loosely on the jar path and keeps only pids
  whose `comm` is `java`, and `scripts/dev-run.sh --self-check` is a positive control that
  asserts the jar IS found and the calling shell is NOT. **A process-detection guard that can
  only fail silently needs a control that fails loudly; run `--self-check` whenever it is
  touched.**

- 2026-08-07 — **Text painted into a `<canvas>` has no node, so `contrast-check` reports
  nothing at all for it — not a failure, not a pass.** The metrics chart handed
  `var(--muted)` / `var(--border)` to echarts' CanvasRenderer, which cannot resolve a CSS
  custom property: the assignment is rejected outright (`ctx.fillStyle='#ff0000'` then
  `ctx.fillStyle='var(--muted)'` leaves it `#ff0000`), so echarts kept its default black. Both
  axes' labels and the grid lines rendered **pure black on `--panel` #1f242a, 1.34:1**,
  measured as 533 px in the y-gutter and 1 622 px in the x-strip with zero pixels of either
  token. Light mode reads black at 21:1, which is why it survived every review. → No script
  can fix this class — there is nothing to sample — so the guard went into the **gate**
  instead: `kweblens-ui/src/metric-chart-option.test.ts` fails if any `var(--…)` reaches the
  chart option. **When the defect lives in pixels a DOM tool cannot reach, put the invariant
  in the unit test, not in a new Playwright script.**
- 2026-08-07 — Same chart, the half that *was* DOM: the hover tooltip's box is echarts' own
  container, inline-styled by echarts, so the `.chart-tip` wrapper rule in `styles.css`
  matched nothing and the default WHITE box carried near-white `.chart-tip-v` at **1.28:1**
  (timestamp 2.51:1) — worse than #169 and #200. No scene had ever hovered a chart point, so
  the tooltip DOM did not exist during a run. → A `metrics chart tooltip` scene now does
  (`hover:.metric-echart`), and reproduces 1.28:1 / 2.51:1 on the pre-fix code before passing
  at 12.20:1 / 6.24:1 on the fix. **A watchlist is only a watchlist for what the run can see —
  and a surface that only exists under the pointer needs a scene, not a selector.**
- 2026-08-07 — That scene's first run reported `covered by another layer` for both tooltip
  selectors, in both themes. It was not covered: echarts sets `pointer-events: none` on the
  container, and such an element is **painted but can never be returned by
  `elementFromPoint`** — so the occlusion hit test was answering a question it had not been
  asked, and a visibly-failing surface read as unmeasurable. Same shape as #250: the
  instrument, not the code. → `unmeasurable()` now forces `pointer-events: auto` on the
  element and any ancestor that has it off, hit-tests, and restores. A `--self-test` control
  (`.ghost .layer`) pins that a painted `pointer-events: none` layer IS measured, while the
  genuinely-covered control still fires.
- 2026-08-05 — **`perf-sweep`'s LOAD column was never time-to-first-row.** It waited for
  `.n-data-table-tbody tr, .count, .cluster-overview, .empty`, and `.count` is
  `ResourceListView`'s items badge, which has **no `v-if`**: it renders "0 items" the instant
  the shell mounts. So a page whose data was slow resolved on an empty shell — a Pods list at
  simulator `size=200` was recorded `0 rows 111ms` where a strict wait for a row measured
  **917 ms** on the same instance, and at `size=3000` the gap is 132 ms against 12.3 s. The
  understatement is worst exactly where the number matters: the slower the server, the earlier
  the badge wins. 30 of 44 pages in a full sweep were reporting a "load" for a list they never
  waited for. → A row is now the only thing that ends the LOAD measurement; an empty
  collection (a zero badge still zero after `EMPTY_MS`) prints `—` and `(empty — load not
  scored)` instead of a duration, and the summary line says how many pages actually had rows
  to time. **A page with no rows has no load time; printing one is how "0 rows 111ms" got read
  as a fast page.**
  Two things this did *not* invalidate, both checked rather than assumed: **BLOCK** comes from
  a `PerformanceObserver` and never depended on the selector, and **#286**'s threshold decision
  was argued entirely on BLOCK — re-measured at both thresholds against a 90-row simulator, it
  reproduces (Pods 1 519 ms at 150 vs 406 ms at 50, against the merged 1 519/391). **Check
  which column a decision was made on before assuming a broken instrument broke the
  decision.**

- 2026-08-04 — **An optional `?click` on an already-open section CLOSES it**, and then the
  thing you came to measure is `not present` — which reads as "absent from the app". Measuring
  a Secret's Reveal button, `?click:.n-collapse-item__header:has-text("Data")` toggled shut a
  section that opens by default, and the run reported 2 of 4 samples measured. Same shape as
  the nav-expansion bug: **a click toggles.** → Check whether a disclosure is already open
  before clicking it, or set state rather than clicking. The unmeasured count is what says
  something went wrong; without it this looks like a passing run.

- 2026-08-03 — **The drawer has 1040px of widths no script could reach, and the defect lived in
  them.** #278 (relation tables breaking a node FQDN and `Running` mid-word) is a function of
  how narrow the pane is, and `Detail.vue` lets the reader drag the drawer anywhere between
  **360px and 1400px** — but every script here only ever saw 520px (the default) or expanded.
  In the simulator the failure does not reproduce at 520px at all; at the 360px minimum it is
  unmissable, `Runnin`/`g` and a 71px-tall row. → `PREPARE` gained `drawer:<px>` in both
  runners, sharing one `resizeDrawer` in `lib/kw-playwright.mjs`. It drags the handle (the
  width is a component `ref` with no other way in) and **throws** on a width outside the range
  or a drag that did not take, rather than measuring the width it happened to land on.
  **A resizable surface has a RANGE, and a tool that samples two points of it is not measuring
  the surface.**
- 2026-08-03 — The same run's other half: **the simulator had 0 Nodes, no `nodeName` on any pod
  and no pod mounting anything**, so the drawer's whole relation family — "Mounted By" for every
  ConfigMap and Secret — rendered "None." and its Node column had nothing in it. The exact
  markup #278 is about could not be produced without a live cluster. → `SimulatorSeeder` seeds
  three Nodes named as host FQDNs (`node-0.sim.example.test` — a bare `node-1` fits any column
  and could not reproduce anything), schedules pods onto them and mounts the same-index
  ConfigMap and Secret. Same rule as the 2026-08-03 chips entry: **fix the fixture; a simulator
  missing what every real cluster has is not a simulator of the defect.**

- 2026-08-03 — **"Click the row" does not open the drawer, and fails silently.**
  `ResourceTable`'s `rowProps` ignores a click whose target is inside a checkbox, button,
  anchor or dropdown. So a row-CENTRE click lands on the Namespace **link** — which filters
  the list instead of opening anything — and `td` alone is the checkbox column. Either way
  the drawer stays shut, every following `?` step skips because its selector is absent, and
  the run reports `not present` for a surface it never navigated to. → Scenes click
  `td:nth-child(2)`, the Name cell. **A chain of optional steps degrades to "measured
  nothing" with no error; the unmeasured count is the only thing that says so.**
- 2026-08-03 — **No surface behind the admin login had ever been colour-checked.** The YAML
  editor's dialog gates its Form / Warnings / Review tabs on `v-if="!readonly"`, so a
  signed-out run does not fail to measure them — it never renders them, and open-mode makes
  that invisible because everything else loads normally. → `signin:` is a PREPARE verb now,
  idempotent (it looks for the Sign in link and does nothing if absent), plus a signed-in
  scene. It immediately found `.review-recheck` at **2.17:1** in light.
- 2026-08-03 — That 2.17:1 was reusing `.linkbtn`, which hard-codes `#9fb3bf` because it is
  a BRAND BAR control and that bar is dark in both themes — the reason #272 deliberately left
  it alone. Borrowing it for a button on a light modal put light grey on white. → **A class
  being "the link one" is not a licence to use it on a different surface.** Check what a
  shared class was designed to sit on before reusing it.

- 2026-08-03 — **The shared `PREPARE` had no `leaf:` verb, so `click:` walked into the
  collapsed-category trap for the third time.** `contrast-check` grew `leaf:` when #257 hit
  it; `lib/kw-playwright.mjs`'s `runPrepare` — which is what `ui-measure` and `ui-shot` use —
  did not, and the SKILL documented the verb as if it were universal. A
  `PREPARE='click::nth-match(.leaf-label…)'` step to reach the Workloads dashboard resolved
  its element and then spent 30s on "element is not stable" / "element is not visible",
  naming the leaf and never the shut `<details>`. → `leaf:` is in the shared runner now, via
  the same `openLeaf` that already expands the rail. **The 2026-08-02 rule ("fix EVERY
  walker") applies to the runners too, not just the walkers they call.**
- 2026-08-03 — **A nav label is not unique, and `.first()` hid it.** Every category dashboard
  is a leaf called `Overview`, so `--leaf Overview` and `leaf:Overview` silently opened the
  Cluster one and measured the wrong page — a wrong answer with no error anywhere. → Both
  `openLeaf`s take `Category/Leaf` (`leaf:Workloads/Overview`). **When a lookup ends in
  `.first()`, ask what else it matches before believing the number it produced.**
- 2026-08-03 — #236 (three 260px cards using 804px of a 2225px row) was found by the #234
  audit doing arithmetic by hand over two selectors, because **no script measured emptiness**.
  Overflow was covered from the start; its mirror image was not, and a container that is
  wider than everything in it reports a perfectly healthy `box`. → `ui-measure` reports `row`:
  children in flow, clustered into lines, and the smallest trailing gap any line leaves.
  Per-line because a wrapping container's last line is short by design — the naive "width
  minus the widest line" reading calls every wrapped layout a defect. Three new `--self-test`
  controls, including the wrapped one that must NOT fire.
- 2026-08-03 — **Qualification alone did not stop the wrong page being measured; only an
  error did.** #270 taught both `openLeaf`s `Category/Leaf`, which helps the caller who already
  knows the label is ambiguous — but an UNQUALIFIED `leaf:Overview` still took `.first()` and
  still opened the Cluster overview when the Network one was asked for. Both pages render the
  same `.ov-*` classes, so the run returned real, plausible ratios for a page nobody wanted,
  with nothing in the output to say so. → One shared `resolveLeaf` (exported, used by
  `openLeaf` and by contrast-check's own click-through) **throws on ambiguity** and names the
  categories to choose from. It fired immediately: measuring the Network overview for real
  exposed `.ov-notes` at 2.37:1 in light, which the wrong page had been hiding. It also anchors
  on `.cat-label`, not the `<summary>` — a category row's innerText is `▸Network200`, so
  matching `^Network$` against it finds nothing and reads as "no such category".
- 2026-08-03 — **No script here could reach a `:hover` rule, and a hover pad is a whole class
  of one-theme colour literal.** `.btn:hover` hard-coded `#f0f4f7` with no dark override, so in
  the dark theme every button's own `var(--text)` label sat on a near-white pad at **1.16:1** —
  invisible the moment the pointer touched it, app-wide, and unmeasurable. → PREPARE gained
  `hover:<selector>` in both dispatchers. The pointer stays parked, so the state survives into
  the computed-style read *and* the backdrop screenshot.
- 2026-08-03 — **A watchlist is only a watchlist for what the run can SEE.** The colour bugs of
  #260/#264/#265 all lived behind a click, so adding their selectors to `DEFAULT_SELECTORS`
  would have bought a column of `not present` — the "green line with half the run unmeasured"
  the summary itself warns about. Two entries were already exactly that: `.badge` had been
  matching nothing since StatusBadge became a Naive `NTag`, and `.count`/`.acc-count` never
  render on the page the base pass samples. → `contrast-check.mjs` now walks named **scenes**
  (a PREPARE plus its selectors) on a bare run, and returns to the cluster overview between
  themes so both passes sample the same page. Unmeasured fell from 14 to 4 of 60.
- 2026-08-03 — The simulator could not render two of the three surfaces under investigation:
  no object had **annotations** and there were no **Ingresses**, so `.chip.subtle` and `.chip`
  had no markup at all, leaving only "reason about the CSS" or "test against a live cluster".
  → `SimulatorSeeder` seeds annotations on ConfigMaps and Pods and a TLS Ingress per index.
  `.chip` then measured **1.75:1 in dark** — a real failure that had never once been rendered.
  **When a fixture cannot produce the state, fix the fixture: a headless simulator that omits
  what every real cluster has is not a simulator of the defect.**

- 2026-08-02 — **The collapsible nav (#237) broke every script that walks the tree, and each
  one blamed the leaf.** `openLeaf`, `ui-measure --leaf`, and `contrast-check`'s
  `click:.leaf-label…` all resolved the leaf — it is in the DOM even inside a *collapsed*
  `<details>` — then burned the whole timeout on "element is not stable" / "element is not
  visible". Nothing in either message names the shut `<details>` responsible, so it reads as
  a renamed or missing leaf and sends you to `NavCatalog`. The collapsed state is remembered
  in prefs, so it survives reloads and looks like a broken selector. → `openLeaf` and a new
  `openNav` in `contrast-check.mjs` re-open the rail and set `details.group.open = true`
  first — setting `.open` rather than clicking each summary, so nothing already open is
  toggled shut. `discoverLeaves` had the fix all along; the entry points people actually use
  did not. **When a UI feature hides things, fix EVERY walker, not the one that failed
  first.**
- 2026-08-02 — **A PREPARE that opens a modal broke the theme loop in two scripts.** Both
  `ui-shot` and `contrast-check` run PREPARE *inside* the per-theme loop, so
  `PREPARE='press:Control+k;…'` (checking the command palette — the surface already got
  wrong twice in #200) left Naive's `.n-modal-mask` over the shell, and the second theme's
  click on `.theme-toggle` was intercepted. Both died after 30s with a message naming the
  toggle, not the modal actually in the way. → `setTheme` and `contrast-check`'s own toggle
  press Escape first when a mask is visible. **When a script loops over a dimension, every
  step inside the loop has to run from the state the previous iteration left behind.**
- 2026-08-02 — **The armed row moved under a stationary mouse, and only an end-to-end
  click-through caught it.** Typing `sim-pod-7` in the palette and pressing Enter opened
  `sim-pod-77`. The modal is vertically centred, so async results arriving make it grow and
  slide the list *up* under a cursor that has not moved; the browser fires `mouseenter` on
  whatever row lands beneath it, re-arming row 8. Screenshots showed the right list and were
  no help — the defect lives between what is drawn and what Enter does. → Fixed with
  `@mousemove`, which only fires on real pointer movement. **A surface whose content arrives
  asynchronously needs a script that types, waits, presses Enter, and asserts what it
  actually opened.**
- 2026-08-02 — A decoded-pixel check of a chip's background disagreed with
  `getComputedStyle` (`[44,44,50]` vs `rgb(238,241,244)`) and briefly looked like a third
  `contrast-check` bug. The sample was taken at `x+1, y+1` — inside the border-radius
  cut-out, where the panel behind shows through. Sampling the vertical centre of the padding
  agreed exactly. → **A pixel probe needs a sampling point argued for, not a corner.**
- 2026-08-02 — #257 ("headers break mid-word while Message hoards the width") was found by
  eye, reported twice, and **no script would have caught it**: nothing overflowed, no line was
  long, contrast was fine. The cause was Naive's `word-break: break-word` on table cells, which
  makes a column's minimum content width ONE GLYPH, so a squeezed column shreds its own label
  rather than refusing to shrink. → `ui-measure` now reports `words`: the longest *unbreakable*
  run (a browser may break after `/` and `-`) laid out in the element's own font vs its content
  box, over EVERY match rather than the first, and fails the run. `--self-test` pins four
  controls against a fixture, **including one that must fire** — the check was written after
  the app was already fixed, where a clean run proves only that a check is quiet.
- 2026-08-02 — `contrast-check` could not measure a single surface behind the nav or below the
  fold: the app has no deep links, so a list or drawer is only reachable by clicking, and
  PREPARE's `click:` dies after a 30s "element is not visible" retry on a collapsed category.
  The overview's own Warnings table (y≈1220 in a 900px viewport) reported `outside the
  viewport` for every selector. → PREPARE gained `scroll:<sel>` and `leaf:<label>` in both
  runners. With them, `.rel-note.dim` measured **2.56:1 in light** — a real failure in text
  that had been on screen for weeks (fixed here; the rest of the `#94a3b8` family filed as
  #265). **A tool that cannot reach a screen is not passing that screen.**
- 2026-08-02 — `openLeaf`/`discoverLeaves` expanded the nav by clicking every
  `.group > summary`, which TOGGLES: on the normal case (categories already open) it shut all
  of them, and the leaf it was about to click stopped being clickable. `discoverLeaves` never
  noticed because a collapsed category still has its labels in the DOM. → One `expandNav()` used by both and mirrored in
  `contrast-check`, which sets `details.group.open = true` rather than clicking at all: a
  click toggles AND starts the disclosure animation, which is what "element is not stable"
  means when you click through it. **An idempotent-looking helper built on a toggle is not
  idempotent — set the state, do not flip it.**

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
  wrong three times, and hasty is how it got there. **FIXED** — see the next entry.
- 2026-08-02 — #250 fixed: `contrast-check` no longer derives the backdrop from the DOM at
  all. It hides every glyph, screenshots the viewport, and takes the **mode** of the decoded
  pixels under each text run; the ancestor walk survives only as a cross-check whose
  disagreements are printed under the table. `.bar-filter` went 1.21:1 FAIL → 12.16:1 pass,
  and `.mini th` went from an invisible 1.00:1 to the 4.40:1 that had to be hand-measured for
  #247. Three things the rewrite taught, each now pinned by a `--self-test` control:
  **(a) hide the text rather than dodging it** — under `color: transparent` every pixel in the
  run's own rect is backdrop, so there is no sample point left to land on a glyph;
  **(b) a pixel needs the element to be ON SCREEN, which the DOM walk never did** — the first
  run against an open drawer reported `+ Create` at 1.00:1 (it sits *behind* the drawer) and
  sampled row buttons parked at x=1594 in a 1400px window. Both now say `covered by another
  layer` / `outside the viewport`, and are never back-filled from the walk;
  **(c) `textContent` is recursive, so wrappers were measured as if they held their children's
  text** — three `.bar-filter` wrappers sampled the white select box while carrying the top
  bar's inherited colour and read 1.17:1 for text that does not exist. Only elements with a
  direct text node are measured now. **When you replace how a measurement is taken, write the
  positive controls first: five of the seven in `--self-test` exist because they caught
  something inside this one change.**
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
- 2026-08-20 — **An element count is not a statement about what is on screen, and a popover is
  where that bites.** Chasing #500, a probe that counted `.n-select-menu` reported the namespace
  select as orphaned over a closed drawer: naive keeps *that* menu **mounted** and closes it with
  `display: none`, so the count stays 1 forever. Its box is 0×0 after Escape, and a control with
  no drawer reads identically — nothing was ever orphaned. `.n-dropdown-menu` behaves the
  **opposite** way, removed from the DOM on close, so two popovers in the same app disagree about
  what "closed" looks like to a counter. → **Read the box, not the count, and take a control from
  the same popover with nothing under it.** The same run also settled what `NDropdown` declares:
  `role` and `aria-modal` are both **null** — it is not `role="menu"` — which is why an open
  dropdown leaves `[role="dialog"][aria-modal="true"]` counting only the drawer.
- 2026-08-20 — **`click:.n-data-table-tbody tr` does not open the drawer at `narrow`**, only at
  normal and wide, so a `ui-shot --leaf Pods` carrying that PREPARE dies on a 30 s timeout at one
  viewport and reads as "the drawer is broken at narrow". → Target the **name cell**:
  `click:.n-data-table-tbody tr td:nth-child(2)`, which is what `contrast-check`'s own scenes use
  and which works at all three widths. Every scene in this repo that opens the drawer should use
  that form.
- 2026-08-20 — **Byte-comparing two screenshots is the wrong instrument for "nothing moved".**
  The footer prints the jar's build time, so every capture of two builds differs. → Decode and
  **pixel-diff**, then bucket the differing pixels by region: #473's before/after came to
  544–952 px of 0.79–1.9 M (~0.05%), all of it the build stamp, a YAML `creationTimestamp` and
  the live status pill, with **zero** differing pixels inside the element under test. A diff you
  cannot attribute is not evidence; a diff you can is.

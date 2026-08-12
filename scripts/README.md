# scripts/

Development helpers. Each exists because doing the same thing by hand went wrong at least
once — the reason is in the header comment of each script.

| Script | What it does |
|---|---|
| [`dev-verify.sh`](dev-verify.sh) | Format + full-reactor `verify`. **Green here means green on the PR.** |
| [`dev-test.sh`](dev-test.sh) | Targeted `-Dtest` run; last argument is the selector. |
| [`dev-run.sh`](dev-run.sh) | Run the app locally with a known login (`admin`/`admin`). |
| [`ui-shot.mjs`](ui-shot.mjs) | Screenshot the viewport × theme matrix, reproducibly. |
| [`ui-measure.mjs`](ui-measure.mjs) | Geometry: box, overflow vs container, characters per line, sub-pixel clipping, two labels truncating alike, unused row width. |
| [`contrast-check.mjs`](contrast-check.mjs) | Measure WCAG contrast of rendered UI in **both** themes. |
| [`perf-sweep.mjs`](perf-sweep.mjs) | Walk every nav leaf, fail on slow loads or main-thread hangs. |
| [`cluster-switch-check.mjs`](cluster-switch-check.mjs) | Switch cluster and fail if any value from the previous one is still on screen. |
| [`resize-check.mjs`](resize-check.mjs) | Drag a multiline field's corner, then type: fail unless it has a grabber AND the pulled height survives. |
| [`payload-bytes.mjs`](payload-bytes.mjs) | Bytes per object per kind — **the check that a rig is representative**. |
| [`heap-probe.sh`](heap-probe.sh) | What one list request costs the JVM heap — **the axis that bounds the product**. |
| [`alloc-probe.sh`](alloc-probe.sh) | *Which code* spends that heap, by call site and thread. A class histogram cannot say. |
| [`lib/kw-playwright.mjs`](lib/kw-playwright.mjs) | Shared browser helpers — start here when writing a new one. |
| [`pr-watch.sh`](pr-watch.sh) | Wait for a PR's checks; optionally merge when they pass. |
| [`deploy-k8s.sh`](deploy-k8s.sh) | Build/push the image and `helm upgrade --install`. |

## Before committing

```bash
scripts/dev-verify.sh                  # the gate — mirrors CI exactly
scripts/dev-verify.sh -pl kweblens-web -am    # extra args pass through to Maven
scripts/dev-test.sh 'ResourceServiceTest,Cluster*'
```

## Running it locally

```bash
scripts/dev-run.sh                 # build if needed, run on :8080, login admin/admin
scripts/dev-run.sh --build         # force a rebuild first
scripts/dev-run.sh --sim           # no cluster needed — the built-in simulator
scripts/dev-run.sh --ai            # LLM enrichment of /diagnose (needs an Anthropic key)
scripts/dev-run.sh --files         # pod file browser ON, read-write
scripts/dev-run.sh --files=ro      # pod file browser ON, browse-and-download only
scripts/dev-run.sh --files-roots /tmp   # ...and confined to those paths (implies --files)
scripts/dev-run.sh --port 8085     # a second instance alongside the first
scripts/dev-run.sh --stop          # stop whatever is on the port
scripts/dev-run.sh --list          # every instance: pid, port, RSS, age, staleness
scripts/dev-run.sh --stop-stale    # stop only those whose source tree has moved on
scripts/dev-run.sh --stop-all      # stop all of them
scripts/dev-run.sh --self-check    # prove the instance detection still works
```

Starting an instance warns about kweblens instances on **other** ports, with their age and
the command to stop each. A second instance is supported — that is what `--port` is for — so
this warns rather than kills; but a forgotten one is not free. Since #283 and #288 we know
each instance holds API-server watches and log streams open against a real cluster, and two
were found here still running days after the agent that started them had gone, both
predating the fixes for the very leaks they were demonstrating. Age is the tell: a few
minutes is a colleague, a few days is litter.

Instances launch with **`setsid`**, so they outlive the shell — and the editor, agent or
terminal that ran the script. `nohup` alone was not enough: it blocks SIGHUP but not a SIGTERM
to the process *group*, which is how tooling normally tears down what it started, so instances
died whenever the tool that launched them exited. An app you have open in a browser should not
vanish because you restarted your editor.

The trade is that Ctrl-C no longer stops one, which is why `--list`, `--stop`, `--stop-stale`
and `--stop-all` exist. They derive everything from the process table rather than a state file:
a registry that goes stale names a pid that has since been recycled, and killing the wrong
process is worse than failing to kill the right one. The lookup is pinned to the `java`
executable, not just an argv pattern — `pgrep -f` alone matches any shell whose command line
merely mentions the pattern, which made an early `--stop-all` SIGTERM the shell invoking it.

That pinning is done by filtering pids on `comm == java`, **not** by `pgrep -x java -f …`,
which was the first attempt and which matched *nothing*: with `-f`, pgrep matches a joined
command line ending in a trailing separator, so a whole-string anchored pattern can never
match — the exact literal fails too. `--list` then reported "(none running)" against a live
server and `--stop-all` stopped nothing, which looks exactly like a clean machine. Because
both failure modes are silent, `scripts/dev-run.sh --self-check` exists as a positive
control: it asserts the jar IS found and that the calling shell is NOT. Run it after
touching the detection.

**Always start it this way rather than `java -jar`.** With no admin password set,
`SecurityConfig` generates one per run and only writes it to the log — so `admin`/`admin`
silently stops working and the reason is buried in the startup output. `dev-run.sh` always
passes the dev credentials, and *fails loudly* if a password gets generated anyway.

The credentials are passed as environment at run time on purpose. Do not move them into
`application.yml`: that would bake a default password into the repository.

`--files` switches on the pod file browser (`kweblens.files.enabled`), which is off by
default because a container's disk holds mounted Secrets and its service-account token.
`--files=ro` keeps `kweblens.files.writable=false`, which is what to use when only the
refusal paths matter. Without the flag the Files tab correctly reports that the feature is
switched off, which looks like a bug and is not one.

`--files-roots` takes a comma-separated list of absolute paths and sets
`kweblens.files.allowed-roots`, so the confinement can actually be exercised rather than
taken on trust. The interesting case is a **symlink inside a root that points outside it**:
the requested path passes the first check, and the second one — against the path the
container itself resolves with `readlink -f` — is what refuses it. Create one with
`kubectl exec <pod> -- ln -s /etc/hostname /tmp/escape`, then ask for `/tmp/escape` and
expect `403 path-outside-roots` naming the *resolved* path. A path the container cannot
resolve at all (a symlink loop) is refused as `403 unresolvable-path`: the check fails
closed.

`--ai` reads an Anthropic key from `ANTHROPIC_API_KEY`, falling back to
`VANTAGE_ANTHROPIC_API_KEY`. **The key is never written to a file** — the flag refuses to
start without one rather than booting a build whose AI silently does nothing. Only the prose
summary on `GET /api/v1/clusters/{id}/diagnose` depends on it; the findings themselves, the
remediation proposals and the server-side dry run are all deterministic and need no key.

## Checking the UI

Both browser scripts drive a running instance, so start one first. They use the
**account-wide Playwright install** — never `npm i playwright` into this repo:

```bash
scripts/dev-run.sh
export NODE_PATH=$HOME/.local/lib/playwright/node_modules

node scripts/contrast-check.mjs                        # the default watchlist
node scripts/contrast-check.mjs '.leaf.active' '.badge'
PORT=8085 node scripts/contrast-check.mjs
PREPARE='press:Control+k;fill:.palette-input=pod' node scripts/contrast-check.mjs '.palette-row.active'
node scripts/contrast-check.mjs --self-test             # positive controls; no running app

node scripts/perf-sweep.mjs                            # needs a cluster or --sim

# Does a cluster switch leave the PREVIOUS cluster's numbers on screen? (#323)
# TO must be a cluster that cannot answer, or an equal value proves nothing — see the header.
FROM=default TO=kind-jhelm666 node scripts/cluster-switch-check.mjs

# Can the reader pull a multiline field taller, and does the pull SURVIVE typing? Reading the
# stylesheet cannot answer either: naive puts `resize` on the input WRAPPER, never the textarea.
node scripts/resize-check.mjs --self-test              # positive controls; no running app
PORT=8093 node scripts/resize-check.mjs
PORT=8093 node scripts/resize-check.mjs --scene form-tab

node scripts/ui-shot.mjs                               # 3 widths x 2 themes of the shell
node scripts/ui-shot.mjs --leaf Pods --view wide
node scripts/ui-measure.mjs --view wide '.n-drawer' '.drawer-title'
node scripts/ui-measure.mjs --self-test                # positive controls; no running app

# Below the fold, or behind the nav — neither could be measured for colour before #257
PREPARE='scroll:.warn-table' node scripts/contrast-check.mjs '.warn-table .n-data-table-td'
PREPARE='leaf:Deployments;click:.n-data-table-tbody tr' node scripts/contrast-check.mjs '.mini th'

# A :hover rule, unreachable before #265 — `.btn:hover` sat at 1.16:1 in dark mode
PREPARE='leaf:Pods;click:.n-data-table-tbody tr;hover:.btn' node scripts/contrast-check.mjs '.btn'

# Six categories have a leaf called `Overview`; an ambiguous label throws rather than guessing
PREPARE='leaf:Network/Overview' node scripts/contrast-check.mjs '.ov-notes'
```

With **no arguments and no `PREPARE`**, `contrast-check.mjs` walks a short list of *scenes*
(defined at the top of the file) as well as the flat watchlist: the drawer's chips, the
read-only YAML tab, a resource list, the diagnostics modal, a hovered button, a hovered metrics
chart. Everything that matters is behind a click, so a watchlist alone could only ever report
`not present` for it — and the summary's "N of M samples measured" line is the number to read,
not the green verdict.

Two limits worth knowing before trusting a green run. The metrics-chart scene needs a
Prometheus / VictoriaMetrics backend; without one the chart is a placeholder and its two rows
are counted as unmeasured. And **text painted into a `<canvas>` can never be measured here** —
there is no node to sample, which is exactly how a chart's axis labels came to render pure
black at 1.34:1 on the dark theme with every review green. That class is guarded in the gate
instead, by `kweblens-ui/src/metric-chart-option.test.ts`.
Run it against `dev-run.sh --sim --files`; the simulator seeds the annotations, TLS Ingress and
pod-file surface the scenes need — and, since the seeder was made realistic, the unhealthy
pods, Warning events and NotReady node that `.ov-card.danger`, `.ov-card.warn` and the row
status pills need in order to be on screen at all.

`payload-bytes.mjs` answers "how big is an object, really" per kind, against whatever cluster
is registered — and exists because the answer was got badly wrong once, at the cost of a whole
planning pass. It reports the **projected list** bytes per row (what the browser pulls) and the
**full object** bytes sampled across the list (what the cluster stores), with p50/p90/max and
the `managedFields` share, which is the most reliable tell that a generated object is not a real
one. Point it at the simulator and at a live cluster and compare the two columns; that
comparison is what "the rig is representative" means, and `docs/design/scale-measurements.md`
records the last one.

```bash
PORT=8131 CLUSTER=default node scripts/payload-bytes.mjs
PORT=8132 CLUSTER=sim KINDS=pods,secrets SAMPLE=100 node scripts/payload-bytes.mjs
JSON=1 node scripts/payload-bytes.mjs > after.json      # for diffing two runs
```

`heap-probe.sh` is the other half of that question: not how big an object is, but what **one
list request costs the JVM**. It brackets the request with forced collections and samples with
`jstat` from outside the process, so the answer does not depend on where in a GC cycle the
reading landed — which is why the 2026-08-01 pass had to throw its heap numbers away. It exists
because this turned out to be the axis that actually bounds the product: ~241 KB of transient
heap per Secret on a live cluster, against a chart that limits the container to 1 GiB, where
the wire cost of the same list is 498 bytes per row. **Run it against a live cluster when the
number will be quoted** — the simulator's API server shares the JVM, so its serialisation is
inside every reading. Rows marked `>=` had a young GC inside the window and are lower bounds.

```bash
PORT=8085 scripts/heap-probe.sh pods secrets configmaps
CLUSTER=sim REPS=5 scripts/heap-probe.sh secrets
```

`alloc-probe.sh` answers the follow-up that decided #293: not **how much** heap a list request
costs but **which code** spends it. It exists because the obvious instrument gives a confident
wrong answer — a `GC.class_histogram` of a live Secrets list is 66% `byte[]` and 25% `char[]`,
which separates nothing, because since JDK 9 compact strings every `String` is a `byte[]`, so
that one bucket is the output `String`, Jackson's scratch, the response body and every field
value in the model graph at once. JFR allocation samples carry a stack, so the same run splits
them, and the split was 1.4% output `String` against 94% response-and-parse. Two traps are baked
into the script because both produced a wrong answer first: `jfr print` truncates stacks to
**five frames** unless told otherwise, and `settings=profile` samples too coarsely for a
one-second request. Read the **thread** table first — it needs no bucketing rules to believe.

```bash
PORT=8142 CLUSTER=default scripts/alloc-probe.sh secrets pods
REPS=20 scripts/alloc-probe.sh secrets
```

`ui-shot.mjs` defaults to the **matrix**, not one image, because captures here were taken
ad hoc at roughly one width in one theme and that shaped what got found: a 338-character
prose line (#235) survived weeks of screenshots that were all ~1400px, and the
black-on-black stat cards survived because the captures were light-mode. Output goes to
`.playwright/shots/` — **gitignored, and it must stay that way**: these are pictures of a
live cluster and carry its API-server hostname, node names and namespaces.

`ui-measure.mjs` settles size the way `contrast-check.mjs` settles colour — box, overflow
against the nearest clipping ancestor, characters per line, and whether any **word is wider
than the box holding it** — and exits non-zero over budget. An `absent` selector is a
**failed** measurement, not a pass, the same trap as `not present` above.

The `words` line was added after #257, where the overview's Warnings table rendered its
`Reason` header as "Reas / on" while a sibling column sat mostly empty: nothing overflowed,
no line was long, and every existing check passed. It lays the longest **unbreakable** run
out in the element's own font (a browser may break after `/` and `-`, so `Pod/kw251-bad-a`
is three runs) and compares it with the content box, over every match rather than the first.
`--self-test` pins it against a fixture whose answer is arithmetic, including the case where
it must FIRE — a clean run against an already-fixed app proves only that a check is quiet.

It reads **descendant** text, not only an element's own child text nodes (#326). The first
version copied that restriction from the chars-per-line check, where it is load-bearing and
here was a hole: the drawer Overview renders half of every `.kv dd` inside a `<button>` or an
`NTag`, so `Controlled By` and `Node` reported no word at all while the row beside them was
measured — a clean line meaning only "nothing wrong with the text I could see". Splitting
into runs is what makes this safe where chars-per-line is not: concatenating descendants
cannot invent a longer word, only a longer line. Runs are compared with the matched element's
own box even when the text sits in a narrower child, which can only under-report; and a text
node is skipped when something between it and the element takes it out of that element's
wrapping regime (`white-space: nowrap`, which ellipsises, or a scroller of its own).

The `clipped` line answers "what is this ellipsis actually hiding", and it is measured
**sub-pixel** because the integer answer is not good enough (#318). `scrollWidth` and
`clientWidth` are rounded to whole pixels, so the near-miss they most need to catch is the
one they cannot see: the drawer's kind eyebrow was given 115.94px for a 116.33px word, both
properties said 116, the run stayed silent — and the header rendered `PERSISTENTVOLU…`,
because `text-overflow` does not drop 0.4px of text, it drops whole **glyphs** to make room
for the ellipsis. A Range over the element's contents reports the full laid-out advance even
when the paint is clipped, so the comparison is exact. A miss of tens of pixels is a designed
truncation and is only printed; a miss under a pixel **fails** the run, because that is a
layout accident every time. Only elements that cannot wrap and actually clip are asked.

The `twins` line answers the question `clipped` cannot: not "how much of this label is cut"
but "did the cut fall in the same place on two of them". It was added after #327, where the
left rail rendered `VerticalPodAuto…` on two rows in a row and `Validating Admissio…` on two
more — Kubernetes kinds are built by suffixing, so a tail-ellipsis removes exactly the part
that tells siblings apart, and the reader is left choosing between two identical labels. Each
character's own rect is compared with the element's content box and a dropped run replaced by
`…`, so what is compared is what is painted; matches whose different text renders alike
**fail** the run. Only truncated elements are compared (eight `Overview` leaves are identical
and fit, so they are not twins), and an element whose neighbour's text carries on within 3px
of its own box edge is skipped as a **fragment** — for a split label the thing the reader
reads is the wrapper, not either half, and the first version reported the halves as a defect.

The `row` line is the opposite defect, added after #236: how much of a container's width its
own children actually reach. The cluster overview's three stat cards sat in a 2225px row and
used 804px of it, and nothing here could see that — the box line reported a healthy
full-width container and said nothing about the void inside it. It is measured **per line**,
so a wrapping layout's short last line is not mistaken for waste, and reported for every
container with children in flow rather than only when it is bad, so it works as a
before/after. Informational: it does not fail a run.

Both take `--view narrow|normal|wide` (1024/1400/1900), `--theme`, `--leaf`, `--path` and
`PREPARE`. `--view` also accepts a bare pixel width — the escape hatch for "at what width
does this stop working?" (#234's question, whose answer was 847px), not for everyday use:
a finding is reproducible only at a width someone wrote down, which is what the names are
for. `PREPARE`'s `leaf:` verb takes `Category/Leaf` as well as a bare label, because every
category dashboard is a leaf called `Overview` and a bare label always resolved the first.
`PREPARE`'s `drawer:<px>` verb drags the open detail drawer to a width. The drawer is
user-resizable between **360px and 1400px**, and until #278 every script could only see it at
its 520px default or expanded — so most of the widths a reader can put it at were
unmeasurable, which is where a squeezed table column shredding its own values lives. A width
outside that range, or a drag that does not take, throws rather than measuring a width nobody
asked for. Shared plumbing lives in [`lib/kw-playwright.mjs`](lib/kw-playwright.mjs);
`contrast-check.mjs` and `perf-sweep.mjs` predate it and deliberately still carry their own
copies — they are the instruments that caught real defects, and rewriting a working
measuring tool for tidiness is how you end up with one you cannot trust.

`contrast-check.mjs` exists because eyeballing colour has failed here repeatedly — the
StatusBadge tag shipped at 1.93:1 (#169), and the command palette's first two stylings
measured 3.02:1 and 3.80:1 while looking perfectly fine (#200). It reads the active theme
from the DOM rather than assuming a toggle order, and **exits non-zero** when anything is
under its floor, so it can gate a change.

**The backdrop is decoded from the rendered image, not derived from the DOM.** It hides
every glyph, screenshots the viewport, and takes the modal pixel under each text run — so
the answer is ground truth by construction, and a sample cannot land on a letter and report
the ink as the background. Earlier versions walked `parentElement` upward instead, which is
right about the cascade and wrong about paint order: Naive UI paints a select's white box as
a *sibling* of the input, so the walk climbed past it to the dark top bar and called a real
12.16:1 a **1.21:1 FAIL** (#250). The ancestor walk is still run as a **cross-check** and any
disagreement is printed under the table — that disagreement is what surfaced #250.

Run `node scripts/contrast-check.mjs --self-test` after touching any of that. It measures a
built-in fixture whose answers are arithmetic — an opaque element, a tint over a panel, a
three-layer stack (the case the compositing fix was verified against: computed `rgb(41,63,80)`
vs the browser's `rgb(40,63,79)`), the sibling-paint shape from #250, dense glyphs, and the
two unmeasurable cases below. No running app needed.

Nothing measured is reported as such, never as a pass: `not present`, `present, but no text
of its own` (a wrapper whose text belongs to a child), `covered by another layer` (an element
behind the open drawer) and `outside the viewport`. Treat a screenful of those as a failed
run — use `PREPARE`, a wider `--view`, or close the drawer, and measure again.

**`signin:<password>` is a `contrast-check.mjs` verb only, like `close`.** It is one
idempotent step for the admin login, and it matters because a signed-out run never renders the
surfaces gated on it at all — the YAML editor's Form / Warnings / Review tabs are
`v-if="!readonly"`, so they are simply absent rather than visibly locked, and open-mode makes
that easy to miss. `contrast-check.mjs` carries its own copy of the PREPARE runner (see
above), and **the shared runner in `lib/kw-playwright.mjs` — the one `ui-shot.mjs` and
`ui-measure.mjs` use — has neither `signin` nor `close`, and throws
`unknown PREPARE verb: signin` rather than skipping it.** To reach a signed-in surface from
those two, spell the login out with the `?click` / `?fill` steps shown in the example below;
those work in both runners.

Verbs both runners understand: `press:` / `click:` / `hover:<sel>` / `scroll:<sel>` /
`fill:<sel>=<text>` / `upload:<sel>=<path>` / `wait:<ms>` / `leaf:<label>` / `drawer:<px>`.
`goto:<path>` is shared-runner only; `signin:` and `close` are `contrast-check` only. A step
prefixed with `?` is skipped when its selector is not on screen.
The `?` matters for anything behind the login: `PREPARE` runs once per theme, so a sign-in
that only applies to the first pass would otherwise stall the second one until it times
out. `upload:` reaches UI that only exists once a file has been picked. Measuring the pod
file browser's save confirmation, for instance, means signing in and walking to a file:

```bash
PREPARE='?click:.bar-btn:has-text("Sign in");?fill:.n-modal input[type=password]=admin;…' \
  node scripts/contrast-check.mjs '.files-saved'
```

Measuring the panel is still usually the better call — its text-bearing children are
sampled along with it, so one selector covers the row.

## Watching CI

```bash
scripts/pr-watch.sh 200            # print each check as it lands, exit 1 on any failure
scripts/pr-watch.sh                # the PR for the current branch
scripts/pr-watch.sh 200 --merge    # squash-merge, but only if everything passed
```

## Deploying

See [`docs/deployment.md`](../docs/deployment.md). `deploy-k8s.sh` is argument-driven and
carries no environment defaults; the lab-specific values live in the private deploy
overlay, never here.

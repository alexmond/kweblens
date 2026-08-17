# CLAUDE.md

Guidance for Claude Code when working in this repository.

Bullets here are **rules**. Where one was learned from a specific incident, the evidence lives
in the linked doc rather than here — follow the link before re-litigating a rule, and when you
learn a new one, put the rule here and the measurements there.

## Project Overview

kweblens is a **web-based Kubernetes IDE** — Freelens/Lens reimagined as a self-hosted Spring
Boot web app instead of an Electron desktop app: browse resources, edit and apply YAML, stream
logs, view events and metrics, exec into pods, manage Helm releases, all from a browser. It also
exposes the same read-only cluster view to AI assistants over **MCP**.

**Spring Boot 4.0.6 / Java 21**, multi-module Maven (`org.alexmond:kweblens-parent`,
`0.1.0-SNAPSHOT`). Cluster access is the **fabric8 Kubernetes client**; the UI is a **Vue 3 +
Vite + TypeScript SPA** (Naive UI, dark theme) in `kweblens-ui`, built into the jar and served at
`/`. The Thymeleaf/htmx "classic" UI was **deleted** (#125) — a reference to it is stale.

**Read before planning work:**
- [`docs/design/adr-001-identity-model.md`](docs/design/adr-001-identity-model.md) — **ACCEPTED**:
  a **single trusted operator**. Multi-tenancy is not a goal; impersonation is the sanctioned
  mechanism if identity ever arrives.
- [`docs/design/roadmap.md`](docs/design/roadmap.md) — the current answer to "what next".
  Shipping is fast: treat any planning doc older than a week as suspect and check the code.

## Build, Test & Verify

```bash
scripts/dev-verify.sh                 # format + whole-reactor verify — green here = green PR
scripts/dev-test.sh <selector>        # targeted -Dtest run, e.g. 'ResourceServiceTest,Cluster*'
./mvnw spring-javaformat:apply        # auto-format (before committing)

scripts/dev-run.sh                    # run on :8080, admin/admin (--sim, --files, --port, --stop)
scripts/dev-run.sh --list             # every instance on this box; --self-check proves detection
scripts/pr-watch.sh <pr> [--merge]    # wait on CI; exit 1 on any failure

export NODE_PATH=$HOME/.local/lib/playwright/node_modules   # UI checks drive a RUNNING instance
node scripts/ui-shot.mjs              # screenshots: viewport × theme MATRIX
node scripts/ui-measure.mjs '.sel'    # box / overflow / chars-per-line; exits 1 over budget
node scripts/contrast-check.mjs       # WCAG contrast, BOTH themes; exits 1 on failure
node scripts/resize-check.mjs         # multiline fields: is there a grabber, does the pull HOLD
node scripts/perf-sweep.mjs           # on-demand hang/long-load sweep

scripts/tui-drive.sh --self-check     # kweblens-tui on a REAL terminal (tmux); prove the rig first
scripts/tui-drive.sh --context <ctx> --keys ':,svc,Enter'
```

Descriptions: [`scripts/README.md`](scripts/README.md). CI (`.github/workflows/ci.yml`) runs
`./mvnw -B verify` on JDK 21 — the same gates as `dev-verify`.

- **Before anything that needs to SEE or MEASURE the running UI, load the `playwright` skill**
  ([`.claude/skills/playwright/SKILL.md`](.claude/skills/playwright/SKILL.md)). It covers every
  script, the traps that make a run lie to you, and a dated Learnings log. It is self-improving
  **by rule**: when a run misleads you, fix the *script*, record why in its header, log it.
- **Start the app with `scripts/dev-run.sh`, never `java -jar`.** Without an admin password
  `SecurityConfig` generates one per run and only logs it, so `admin`/`admin` silently stops
  working. **Never move those credentials into `application.yml`.**
- **A free-text field that can hold multiline content is a resizable `<textarea>`, never an
  input** — a ConfigMap `data` value holds a PEM block, an annotation holds a whole JSON
  manifest. Start it at `initialRows(value, min, max)` (real lines, never a guess at wrapped
  ones) and let the reader pull. **Never `autosize`**: naive-ui applies its resizable class only
  when `resizable && !autosize`, so an autosized box has no corner grip at all — and reading
  `resize` off the `<textarea>` cannot tell you, because naive pins every textarea to
  `resize: none` and puts the grip on the WRAPPER. Prove both halves with `resize-check.mjs`.
  **Secret values stay masked and single-line until the reader reveals them**; masked and
  multiline are mutually exclusive and `-webkit-text-security` fails open.
- **Colour and size are measured, not eyeballed.** Run `contrast-check.mjs` against any
  `styles.css` change. A selector that is not on screen reports `not present` / `absent` — that
  is a **failed measurement, not a pass**; bring it on screen with `PREPARE` and measure again.
  **A component that paints several colours needs a per-tone hook, or coverage is luck**: the
  status pill's tint arrives inline, so until #393 the check could only match `.n-tag` and
  measured whichever tone sorted first. The hook is a `tone-*` class **styled by nothing** — the
  inline `:color` stays the one source of the colour — and a tone the page says is there and the
  run could not sample is a **failure** (`REQUIRED_WHEN`), not a note.
- **Per-cluster state is emptied by how it is DECLARED, not by a list of resets.** `clusterScoped`
  (`kweblens-ui/src/composables/clusterScoped.ts`) is the shell's mechanism; `useAsyncData`'s
  `deps` are the **identity** of what is loaded, so changing them discards the value while
  `reload()` keeps it. **Never put a refresh nonce in `deps`** — that is a `reload()`. A number
  about the cluster you just left is worse than no number, because it is plausible (#323).
- **Several agents share this box, and `dev-run.sh` takes a port from whoever had it.** Two
  measurements of "the app" can be two different builds. `cluster-switch-check.mjs` proves the
  port's owner is this checkout before reading anything; do the same in any new script.
- **Capture the matrix, not one image.** Defaults are three widths × both themes because ad-hoc
  single captures are how a 338-char prose line and black-on-black cards survived for weeks.
  Output lands in `.playwright/`, **gitignored and it must stay that way** — the images carry the
  cluster's API-server hostname, node names and namespaces.
- **Suspect the instrument before the code.** Every wrong UI conclusion here came from a broken
  tool, not broken reasoning. **Build a positive control — a case whose answer you already know —
  before believing a surprising result.** Same rule for process detection and heap probes.
- **A live list buffers and flushes on a period — never repaints per event — and BOTH surfaces
  are gated on it.** In the SPA the period is an animation frame and the gate is
  `useResourceData.test.ts`, which fires **157** `ADDED` events and asserts ≤1 `objects` update
  per frame. In the terminal the period is a **tick** and the gate is
  `kweblens-tui`'s `ScreenLoopTest` + `WatchCoalescerTest`, which fire the same 157 and assert
  one repaint. **When you add a live-updated list, add its watch to that batching pattern** —
  buffer keyed by `namespace/name`, flush once per period, and the flush is where the *one*
  `ObjectStates.forList` call happens. In a terminal this is not a frame-rate question: a redraw
  posted per event is a TamboUI `UiRunnable`, which `pollEvent` treats as **FIFO with
  keystrokes**, so per-event repainting **starves the keyboard** and the app will not quit
  (`ScreenLoopTest.aRedrawPostedPerEventIsFifoWithKeystrokes` is the standing control).
- **Performance has two more layers.** (1) *In the gate:* the batching tests above.
  (2) *On demand:* `perf-sweep.mjs` walks every nav leaf and fails on `LOAD_MS`/`BLOCK_MS`. Run
  it against the simulator with `KWEBLENS_SIMULATOR_ENABLED=true KWEBLENS_LOAD_KUBECONFIG=false
  KWEBLENS_SIMULATOR_SIZE=200`.
- **A rig whose objects are unrepresentative measures the rig.** `web/sim/` generates objects
  sized and shaped like measured live ones, including a deterministic minority of unhealthy
  ones. **Check any new kind you seed with `scripts/payload-bytes.mjs` against a live cluster**
  before quoting a number from it. The simulator **cannot validate paging** (the CRUD mock
  ignores `limit`); KWOK is the answer for that and for anything larger.
- **Heap is the axis that bounds this product, and `peak - base` does not measure it.**
  "Does this bound the heap" is answered ONLY by the **smallest `-Xmx` in which the request still
  completes** — a squeezed heap collects what it can, so only live bytes push it over.
  `heap-probe.sh`'s `transient` measures allocation *churn* and can rank a correct fix as worse.
  **Seed a heap rig with `kubectl create`, never `apply`** (`apply` stores a copy of each
  manifest in `last-applied-configuration`, which `ListProjection` does not strip, so the rig
  measures its own seeding). `heap-probe.sh` says *how much*; `alloc-probe.sh` says *which code*
  — reach for the second whenever the first surprises you, because a class histogram cannot
  separate an output `String` from the model graph (compact strings put both in `byte[]`). Run
  both against a **live** cluster; the simulator's API server is inside every reading. With
  **jvmlens** (skill: `jvmlens-perf`) also pass `-a io.fabric8 -a com.fasterxml.jackson
  -a io.vertx` — this app's list cost is incurred inside the client library, and an app-only
  scope reports it as ~1%. Numbers and cases: [`docs/design/scale-measurements.md`](docs/design/scale-measurements.md).
- **Tests are hermetic** — no live cluster. `@EnableKubernetesMockClient(crud = true)` serves an
  in-JVM API server; web tests set `kweblens.load-kubeconfig=false` so the registry starts empty
  and the test seeds its own client. **The CRUD mock ignores `limit` and `dryRun`**, so assert on
  the **exact outgoing query string** (`ResourceChunkedListTest`, `ResourceCountTest`) — a test
  that seeds objects and counts them passes whether or not the flag was ever sent.
- **Every runnable module has a test that boots its Spring context**, and **no application class
  is excluded from a coverage check**. `kweblens-cli` shipped a fat jar that died on its first
  line for as long as the module existed: its only test called `new CommandLine(new
  KweblensCommand())` directly, so nothing ever instantiated the application class, an injected
  bean that no dependency supplied was never noticed, and a 0.60 JaCoCo gate passed on a context
  that had never started (#363). An `<exclude>` on an application class is the sentence "nothing
  tests this" written into the build — `KweblensCliApplicationTest` replaces it. A unit test of
  the command is fine; it just must not be the *only* test, or "the command parses arguments"
  stands in for "the application starts".

## Architecture (modules)

- **`kweblens-core`** — cluster access, no web concerns: `cluster/` (`ClusterRegistry`, one
  fabric8 client per cluster id), `resource/` (`ResourceService` → kind-agnostic
  `ResourceSummary`; `RelationService` for drawer relations), `health/` (deterministic checks
  shared by dashboard, `/diagnose` and MCP), `event/`, `log/`, `exec/`, `metric/`,
  `portforward/`, `schema/`, `config/` (`KweblensProperties`).
- **`kweblens-web`** — the runnable app, one `web/<area>/` slice per surface: `api/` (JSON +
  `ProblemDetail`), `ui/` (`SpaController`), `security/` (`SecurityConfig`, `AuditService`),
  `mcp/`, `nav/` (`NavCatalog`: 39 built-in kinds / 8 static categories + discovered CRDs;
  `ClusterNavService` promotes a Gateway category when those CRDs exist and adds the cluster's
  VPA kinds to the declared **Autoscaling** category when theirs are (#428)), `helm/`, `exec/`,
  `files/`, `search/` (global search), `diag/`, `ai/`, `sim/`, `config/`.
  `/actuator/{health,info,metrics,prometheus}` — note `metrics` and `prometheus` are **not** in
  `SecurityConfig`'s permit list, so they are public in open-mode and authenticated in closed.
- **`kweblens-cli`** — dependency-light inspector (picocli + `picocli-spring-boot-starter`, which
  is what supplies the `CommandLine.IFactory` bean the app injects); runnable fat jar is the
  `exec` classifier. It resolves a kubeconfig and never builds a client, so it depends on fabric8's
  **`kubernetes-client-api`, not `kweblens-core`** — core dragged 25 unused jars into the fat jar.
  A subcommand that does talk to a cluster must go back through `ClusterRegistry` (#372).
- **`kweblens-tui`** — a terminal cluster browser (#362) that talks to the cluster **directly
  through `kweblens-core`, never to a running kweblens server**, on the operator's own kubeconfig
  and therefore their own RBAC. Boots `WebApplicationType.NONE`; `TuiDependencyTest` fails the
  build if `kweblens-web` or any servlet class reaches the classpath. All cluster access goes
  through **one `ClusterDataSource` port** (kinds / list / watch / get / logs / exec) with **exactly one
  adapter**, `CoreClusterDataSource` — the port exists so an HTTP adapter is possible later, and
  a second implementation is a deliberate decision, not a side effect. **v1 is read-only and the
  header says so** (`TuiPosture`, k9s's `[R]`/`[RW]`); the port has no write method at all, which
  is the enforcement. Lists go through `listRawChunked`, verdicts through `ObjectStates.forList`
  — **one status context per page, never per row**. Terminal stack settled by the #361 spike:
  TamboUI 0.4.0 + JLine 3.30.16. The module is in the `default` profile, **not** published.
  - **Curation and discovery are both wanted, and they are different things** (#365). `NavCatalog`
    orders the web menu; the TUI's `:` command line makes everything *addressable*, from
    `ApiDiscoveryService` — every group/version the API server publishes, with its plural,
    singular, kind and **server-declared** short names. Measured on `k3stest`: **404 aliases,
    all 39 `NavCatalog` kinds, plus CRDs no catalog lists** (`hc` → HelmChart, `gtw` → Gateway).
    **Never add a hand-written alias table**; `TuiKinds` was exactly that and is deleted.
    Discovery is ~30 round trips (7.5 s on `k3stest`), so `KindCatalog` memoises it per cluster —
    a CRD installed while the screen is up is not addressable until restart, which is the
    accepted trade.
  - **The on-screen key list is DERIVED from `KeyMap.BINDINGS`, and `KeyMapTest` fails both ways**
    — a bound visible key missing from the bar, or a word in the bar no key produces. This is
    k9s's `HydrateMenu(Hints())` and it is copied on purpose: a hand-written help screen always
    eventually lies. Adding a key is a row in that table; there is no second place to edit.
  - **Drill-down is a visible, editable filter, never a hidden join.** Enter on a Deployment
    opens `pods(kube-system)[1] </k8s-app=kube-dns>` — a query in **this** product's grammar
    (#366's port of `objectFilter.ts`), so it can be read, checked and widened. Where no query
    can express the relationship (a Node's pods need a field selector) `DrillDown` **declines in
    words**; it never approximates. `esc` clears a filter *before* it pops a level, and that
    applies to a drill-down's filter too.
  - **A counter a test waits on must be bumped when the work FINISHES** (#423). `ticksHandled`
    was incremented on entry to the tick handler, so `ScreenHarness.tickAndSettle` released the
    test mid-tick; a test that then moved `MovableClock` 60 s was mutating state a live
    `WatchSupervisor.tick()` was reading, and the retry it saw depended on the machine. Green
    here, red on CI, three commits. `ScreenTickBarrierTest` is the standing control.
  - **A test waits for a STATE, never for a number of iterations** (#427). An iteration count is
    a timeout with no unit: `tickUntil`'s 500 supervisor ticks measured **5.0 ms** of wall clock
    on an idle 24-core box (126 of them were actually needed), so the reconnect thread was given
    5 ms and a loaded runner gave it less — reproduced locally, 2 of 3 runs red on one saturated
    core, with CI's exact message. **Raising the bound is not a fix.** `Eventually` (test sources,
    `tui/screen`) is now the single waiter everywhere in this module — a wall-clock bound, an
    optional per-pass nudge, and a failure naming what never happened; it spins briefly and then
    **parks**, because a test thread that only spins competes with the thread it is waiting for.
    Better still, wait for nothing: where the ordering is knowable, dispatch the exact ticks and
    wait only on a state the other thread publishes (`recoveryPending()`, a latch).
  Three things about the screen (#364) that a change will get wrong otherwise:
  - **Build widget rows for the visible window only.** Table build+render at 132×44, warmed:
    2 206 rows cost **0.69 ms** windowed vs **27.7 ms** naive (40×); 10 000 rows cost **0.68 ms**
    vs **120.8 ms** (178×). Windowed is *flat* in list size and naive is linear — that, not the
    ratio, is the property. TamboUI's `Table` *will* scroll the whole list and is correct when it
    does; it just charges a `Row` per object per frame, forever, on a tick.
  - **A resize is observed from `Frame.area()` in the renderer, never from a `ResizeEvent`.**
    `TuiRunner.run` consumes `ResizeEvent` itself and never reaches the `EventHandler` —
    measured 0 deliveries across three real SIGWINCHes while the layout redrew correctly.
    `ScreenLoopTest` asserts that count stays **zero**, because a handler that never fires looks
    identical to one that works if the layout is right either way.
  - **A live table must be able to say it has stopped being live.** `ClusterDataSource.watch`
    takes an `onEnd` and has **no overload without one** (#413) — fabric8 reconnects by itself,
    so the callback fires only where it has given up (410, reconnect limit, a bad event), and a
    dropped signal leaves the terminal drawing a photograph with a row count that reads as
    current. `WatchSupervisor` puts `NOT LIVE …` at the **front** of the header (everything after
    it is a claim about a moment that has passed), tells a clean end from a failure **in words
    only** — the posture and the response are the same, because nothing arrives either way — and
    reconnects with re-subscribe + **re-list**, since the TUI tracks no `resourceVersion`. The
    reconnect runs on its own thread and hands **rows** back for the render thread to
    `replaceAll`: the model is single-threaded by design, and a re-list has to be a *replacement*
    or the row deleted while the screen was blind never leaves. **A close the TUI asked for must
    never be reported** — fabric8 routes it through the same no-arg `onClose()`, so `CoreWatch`
    suppresses it; without that, every reconnect reads its own close of the old handle as a fresh
    failure and reconnects forever.
  - **The buffer belongs to ONE subscription, and which one is captured when the watch is
    opened — never read when an event arrives.** The recovery's `replaceAll` is followed by the
    tick's flush, so an event the dying watch left in the buffer landed on top of the row the
    fresh list had just corrected: one row older than the table around it, on a screen that had
    just said it was live again (#417). `WatchSupervisor.lease()` mints the generation once and
    hands it to both halves — `WatchCoalescer.sink(generation)` for the events and the `onEnd`
    listener for the ending — and `ScreenSession.open` calls `coalescer.rebase(generation)`
    **before** `cluster.watch`, which discards what the old watch buffered and refuses what it
    delivers afterwards. **Reading the shared counter at `offer` time is the bug, not the fix**:
    the counter names the newest subscription, and it is bumped on the render thread while the
    dying watch is still open, so an arrival-stamped event is stamped with its successor's
    identity. The cost is one `long` compare inside the lock `offer` already takes — #364's tick
    cannot afford a per-event comparison that walks anything. The identity-less `offer` is
    package-private so only the in-package coalescing tests can reach it.
  - **And a subscription's events are applied only after its OWN list has been installed.**
    Refusing the dead watch was half of it; the live one is delivering from the moment it is
    opened, while its re-list is still on the network, and a tick in that interval used to flush
    those events onto the rows the list was about to replace — so the `replaceAll` that installed
    it **wiped an update newer than the list**, unbounded in exactly the way #417 was (#420).
    So `rebase` does not only name the new subscription, it **holds** its events: they are staged,
    not buffered, until `WatchCoalescer.installed(generation)` says that subscription's rows are
    in the model, and then they are flushed **on top of** them. Every `rebase` therefore owes an
    `installed` — `ScreenSession.load` for the two paths that list on the calling thread,
    `ScreenSession.install` (via `RecoveryInstall`, which is why `WatchSupervisor` no longer holds
    the model) for the reconnect, which does not. **`replaceAll` and the release are one call**,
    because splitting them is the defect. A `subscribe()` with no `load()` is a table that never
    moves. The cost is one field read choosing between two maps, in the lock `offer` already
    takes. **Promoting all of it is deliberate**: an event that is somehow older than the list
    can only be one whose successor is already in flight, so it self-corrects within a watch
    latency — where a lost event self-corrects never.
  - **And a recovery the operator navigated past installs nothing, and decides nothing.**
    `switchTo` (`:svc`, a drill-down, a namespace favourite) runs on the render thread while
    the recovery thread is still inside `reconnect`, reading a query that has since been
    retargeted — so the rows it hands back are a list of a kind or a scope nobody is showing,
    and installing them put one kind's rows under another kind's title and then cleared NOT
    LIVE (#431). The generation already travels with the rows, so `WatchSupervisor` **discards
    an outcome whose generation is below the current one** (counted as `superseded()`, because
    a guard that never fires looks like one that is not there) — the lease a switch takes is
    what moves the counter past it. **The header is answered from the other side**:
    `watching(generation)` is called from `ScreenSession.load`, which is exactly the set of
    paths that subscribe and list on the calling thread, so the loss ends when the screen has a
    subscription of its own *and* that subscription's rows — and not at all if the list threw.
    Both halves are load-bearing and each is caught by one assertion of
    `ScreenSwitchDuringRecoveryTest` and by **nothing else in the module**.
  - **A navigation reports what it could not do; it never throws it** (#434). `Navigation.show`
    returns a sentence, empty when the view was filled, because `switchTo` subscribes and lists
    on the render thread and either call can be refused. **An exception out of an `EventHandler`
    does not kill TamboUI and does not print** — measured on 0.4.0 and pinned by
    `TuiRunnerEscapedExceptionTest`: the runner catches it, the default `RenderErrorHandler` is
    `displayAndQuit`, `inErrorState` is set and **never cleared**, so the table is replaced by a
    stack trace the session never comes back from, with nothing in the log. So both failure
    points are caught, and the state left behind is **the kind the operator asked for, empty,
    NOT LIVE, with the reason in the footer** — not a rollback, because the view stack was
    pushed before the session was asked for anything and rolling it back needs the same
    subscribe-and-list pair that was just refused. The loss goes to `WatchSupervisor` through
    the switch's own lease, which is what puts the retry loop behind the new kind; nothing is
    kept from a list that did not finish, because a partial page count reads as a collection's
    size. `ScreenSwitchFailureTest` drives both points.
  - **Nothing but the renderer may write to stdout**, and it takes two halves:
    `kweblens-tui/src/main/resources/logback.xml` (file appender, **no** console appender, and
    plain `logback.xml` not `logback-spring.xml` so it is in force before Spring exists — Boot 4
    applies its logging defaults programmatically and ships no `defaults.xml` to `<include>`)
    **plus** `TerminalOutputGuard`, which swaps `System.out`/`System.err` for the same file while
    the screen is up and catches everything that never goes through logback.
  - **The last inch is a REAL terminal, and it is `scripts/tui-drive.sh`** (#426) — the shipped
    exec jar in a `tmux` pane, keys sent, the frame read back as text. `FakeBackend` is the right
    rig for the loop and covers none of JLine's detection, raw mode, the alternate screen or the
    redirect. **On demand, never a gate**, and `--self-check` runs first: a rig that cannot see a
    frame and an app that draws none are the same silence. What a bare `pty.fork()` gets wrong is
    the **window size** — it leaves the pty at 0×0, JLine reports zero rows, and the app correctly
    draws nothing (measured: 0 bytes at 0×0, 1 407 and a full table after one `TIOCSWINSZ`). The
    startup `ESC[?2027$p` is answered by nothing here, tmux included, and is not waited on; an
    **empty `kweblens-tui.log` is what a healthy run leaves**, because root is `warn`. Captures
    land in `.tui/`, gitignored for the same reason as `.playwright/`.
- **`kweblens-it`** — the module is in the `default` profile and **is** compiled every
  build; what is excluded are its `it`-**tagged tests**, via surefire `excludedGroups`.

Config is env-var driven (`kweblens-web/src/main/resources/application.yml`): `PORT`,
`KWEBLENS_LOAD_KUBECONFIG`, `kweblens.clusters[*]`. Settings class: `KweblensProperties`.

**Both env-var spellings of a dashed property bind**, measured against Boot 4.0.6's
`SystemEnvironmentPropertyMapper`: `kweblens.security.open-mode` accepts
`KWEBLENS_SECURITY_OPENMODE` *and* `KWEBLENS_SECURITY_OPEN_MODE`. This matters — the Helm chart
uses the second form, so "dashes are removed" as a lone rule reads as though the chart is
broken. It is not; do not "fix" it.

- **`ClusterBootstrap` registers one cluster per kubeconfig context, id = the context name.**
  `default` is only the fallback when there is no readable kubeconfig (in-cluster SA), no
  contexts, or it fails to parse. **Never assume `default` exists** — resolve ids from
  `GET /api/v1/clusters` in code, tests and examples.
- **The pod file browser is off by default** (`kweblens.files.enabled`). The Files tab takes both
  gates plus the write cap from `/api/v1/about`, so Edit and Upload appear only where a write can
  succeed. **Anything piped into a container must bound its own read of stdin** — the exec API
  has no end-of-input signal, so a script reading to EOF hangs for the whole `command-timeout`
  and then lands anyway, i.e. reports failure for a write that happened.
- **A `GET` never calls a model.** `DiagnoseService.diagnose()` returns deterministic findings
  plus only what `DiagnosisSummaryCache` already holds *for exactly those findings* (SHA-256 of
  the finding list is the key, so a changed cluster is a miss, not a stale verdict). `analyse()`
  (`POST …/diagnose/summary`, auth-gated and audited) is the only caller of the LLM. Do not
  reintroduce on-read or on-stale auto-analysis.

## Code Style & Quality (all fail the build at `validate`)

- **spring-javaformat** 0.0.47 (tabs) · **Checkstyle** 3.6.0 + Spring checks (`FileLength` 800,
  `MethodLength` 80) · **PMD** 3.28.0 (`pmd-ruleset.xml`) · **JaCoCo** 0.8.15 line gates: core
  0.70, web 0.50, cli 0.60. Raise these as real coverage grows.
- **Lombok** `@RequiredArgsConstructor` for injection; `@ConfigurationProperties` for config.
- **File size** — target under ~500 lines; split fat controllers into per-page `*PageService`
  helpers. Guideline, not a gate.
- **The front end has its own gate**: `npm run check` (prettier + `vue-tsc -b` + eslint + knip +
  vitest), run from `kweblens-ui/pom.xml` so `dev-verify` and CI include it. Keep **logic in
  `.ts`, rendering in `.vue`**, so behaviour is testable without a DOM.
- **`strictTemplates` is on** (`tsconfig.app.json`) and is what makes vue-tsc check the
  **component boundary**: unresolved tag, undeclared prop, undeclared event, unknown directive.
  Without it those are silently `any` — an `<ErrorNotice>` used without its import shipped and
  stayed shipped for 62 green CI runs. It does **not** check slot names (measured), and it cannot
  help when the *type itself* is wrong: a field typed non-null that the server sends as null
  passes here and throws at runtime. Global HTML attributes on components live in
  `kweblens-ui/vue-attrs.d.ts` — add only attributes valid on **every** element, so a
  component-specific prop typo stays an error.
- Lint rules that bite: **SpringLambda** wants `(e) -> …` (`:apply` does **not** add these, so it
  fails at `validate` on the next build); **SpringTernary** wants `(a != b) ? x : y`;
  **InnerTypeLast**; **UseUnderscoresInNumericLiterals** (`86_400`); **AppendCharacterWithChar**.
- **A control character in source is written as an ESCAPE, never as a raw byte** — and this one
  gate is at `test`, not `validate`. `grep` classifies a file containing a NUL as binary and
  prints **nothing** for every search over it: no match, no warning, no exit status you can tell
  apart from "no match" — which is how a review of #407 nearly reported a function that had just
  been added as missing. A raw `U+001F` greps but makes `file` say `data`; only the escape leaves
  the file greppable **and** text. `DiagnosisSummaryCache` has always joined its fingerprint
  fields that way — the convention predates #408, and the two files it fixed were the deviation.
  `TrackedSourcesStayGreppableTest` (in **`kweblens-core`**, the module `-Prelease` cannot drop)
  scans **every path `git ls-files` lists**, reads the working tree, and names file, byte offset
  and line. Tab, LF and CR are the only raw control bytes allowed; the sole exclusion is `.png`,
  and an exclusion that stops matching a real file is **itself a failure**. Two traps it also
  pins: a Java package named `build`/`target`/`dist` is **invisible to git here** (`.gitignore`
  line 5 is `build/`), so the gate checks that its own source is tracked; and a brand-new file is
  scanned only once `git add`ed, because tracked is what `git ls-files` means.

## Gotchas (load-bearing)

- **Building a fabric8 client does not connect.** Only the first API call reaches the cluster —
  which is why bootstrap and tests need no live cluster, and why a bad kubeconfig fails *lazily*.
- **The `ClusterRegistry` owns client lifecycles.** Never `new` a `KubernetesClient` in a
  controller or service — ask the registry (`require(id)` / `client(id)`); re-registering an id
  closes the previous client. Two consequences, each already a bug: (1) a client built *before*
  it is handed over is owned by **nobody** until `register()` returns, so every path between must
  close it (`adopt()` is that guard); (2) **closing the client does not release everything the
  cluster held** — a port-forward's socket is bound by kweblens, a cached OpenAPI document
  describes an API server the id may no longer point at. Holders of cluster-scoped state register
  a `ClusterClientListener` and release on removal *and* on re-point. `PortForwardService` and
  `SchemaService` do; anything new that caches or binds per cluster must.
- **Tests must not hit a real cluster** — see the hermetic-tests rule above.
- **Spring Boot 4 moved packages.** Actuator health is `org.springframework.boot.health.*`;
  autoconfigure is split per-module. Verify imports against the jars, not Boot 3 memory.
- **Security: reads are open, writes are authenticated.** In **`open-mode` (default)** every
  `GET` is public and **every non-`GET`** needs the admin login; with `open-mode=false` all but
  health and login need auth. There is **one in-memory admin**; if no password is set one is
  generated at startup and logged — **never bake a default password into the source**. The
  authenticated context is persisted to the `HttpSession` as well as the request, because the
  SPA's one-shot Basic login must establish a `JSESSIONID` for the exec WebSocket. Pod exec, Helm
  values and the whole pod-file family are authenticated **even in open-mode**, because what they
  return is itself a secret.
- **That session is a credential, so both ends of it are server-side.** **Sign out is a request**
  (`DELETE /api/v1/auth/session`, the `LogoutFilter`, awaited before the client clears anything) —
  clearing the in-memory Basic creds alone left a cookie that still authorised writes and still
  opened a shell. **Sign in is judged by the credentials it presents**: `BasicAuthenticationFilter`
  *skips* validating a Basic header whose username matches the context already loaded from the
  session, so a stale cookie made every password correct (#320). `PresentedCredentialsFilter` drops
  that context when a sign-in request carries credentials; a request carrying none is untouched,
  because that is the reloaded tab restoring its session. **Never decide "are these credentials
  good" from a response a cookie could have produced.**
- **One shared identity is the accepted design, not a TODO.** No OIDC, so audit entries name an
  action but no person. Per ADR-001 that is **decided** — do not open work to "fix" it or
  describe it as a gap, but do keep saying it plainly in user-facing text.
- **The access affordance is a tri-state that FAILS OPEN, and it is never a gate.**
  `AccessReviewService` (core `access/`) batches `SelfSubjectAccessReview` for `create`/`patch`/
  `delete`; `web/access` serves one `GET …/access` per surface, so a list of 200 rows costs
  **three** reviews, not 200. The result is `allowed` / `denied` / `unknown` — **two states
  would collapse "we could not tell" into one of the real answers**, which is the whole defect.
  `unknown` renders as **enabled**: a control greyed out by a failed *probe* is a lie about the
  cluster. Only `denied` disables anything, and it must say why in words that name the **service
  account**, never "you" — there is no user to name. A cluster-wide `denied` about a *namespaced*
  kind is weakened to `unknown` (`AccessPageService.narrow`): "not in every namespace" is not
  "not here". `AccessResultIsNotAGateTest` fails the build if a verdict is referenced outside the
  presentation slice; `permissions.ts` is the single client-side decision. Server-side
  authorization (`SecurityConfig` + the cluster's RBAC) is unchanged and is the only real gate.
  **A new action either names a verb this report covers or names none** — a guessed verb disables
  a control that works. Contrast for the refused state is measured with `contrast-check.mjs`'s
  `deny` PREPARE verb, which stubs the one response no cluster on this box will produce.
- **"Suggest → preview → confirm → apply" — know which link is real, per surface.**
  - **Helm** — a genuine jhelm `dryRun` on **install / upgrade / rollback**, and the Apply
    button stays disabled until it returns. **`uninstall` has none** — `HelmService.uninstall`
    takes no `dryRun` and returns `void`, so the UI goes from confirm straight to the delete.
    History, values library and repo refresh have no dry-run concept at all. "Each with a real
    dryRun" was wrong wherever it was written.
  - **Remediation** — `scale-up` / `rollout-restart` take a real server-side `dryRun=All`;
    `restart-pod` and `rollback` **cannot** (a DELETE and a revision lookup are not patches), so
    `preview` returns `notChecked` naming the reason. **A new action must pick one of those two
    and never narrate a third.**
  - **The YAML editor** shows two diffs: the edit against what was loaded, and live against what
    `dryRun=All` says the cluster would store. `dryRunApply` shares `apply`'s normalisation via
    `forApply` — a preview made of different bytes describes a different request. A refusal is
    rendered as a **result**, not an error.
  - **Still not covered:** the `apply` that writes is unchanged — `forceConflicts()
    .serverSideApply()` with no `dryRun`. Nothing forces the operator to look at the preview.
- **Audit survives a restart.** Every entry goes to the `kweblens.audit` logger *as well as* the
  in-memory 500-entry ring, so eviction no longer loses the record. Values are quoted, escaped
  and stripped of control characters, because a target can carry attacker-influenceable text and
  a newline could forge a second, fake audit line. The category is pinned to INFO so
  `logging.level.root=WARN` cannot silently switch the trail off.
- **A list row is not the whole object.** `web/api/ListProjection` strips `managedFields` and
  ships ConfigMap/Secret `data`/`stringData`/`binaryData` as **keys with `null` values**. It sits
  at the **web boundary** — like `ToolRedaction` does for MCP — and deliberately **not** in
  `ResourceService.listRaw`, which the health checks, overviews, `RelationService` and the MCP
  tools share and which needs those values. Both the `objects` endpoint **and** the
  `objects/watch` stream go through it: separate code paths, same table. The drawer refetches via
  `GET …/object` when `needsFullObject(row)` (`kube.ts`) sees a `null` — **that predicate reads
  the row, not a list of kinds**, because a hard-coded kind list would be a second copy of the
  server's rule that goes stale silently. A `null` renders as `—`, never an empty string: "we did
  not send it" and "it is empty" are different claims. This is **not** redaction.
- **An SSE stream that never writes never notices a disconnect.** `SseEmitter` learns its client
  is gone only from a *failed write*, so a data-driven stream holds its cluster-side resource open
  on a departed subscriber for as long as the cluster stays quiet. `SseKeepAlive` writes a
  `:keepalive` every 15 s, which fails, completes the emitter and runs the `onCompletion` hook
  that closes the watch. **Every SSE endpoint must attach it**; `SseEndpointKeepAliveTest` fails
  the build otherwise, checking for an `invokestatic` of `SseKeepAlive.attach` **in the bytecode**
  — a name search passed a class that never attached anything. **exec-over-WebSocket and
  port-forward deliberately have no heartbeat.** Full audit:
  [`docs/design/watch-fanout.md`](docs/design/watch-fanout.md).
- **`LogWatch.close()` does not stop a log follow — close the stream.** fabric8 implements it as
  `asyncBody.thenAccept(AsyncBody::cancel)`, and the `watchLog()` flavour this project uses never
  completes that future, so the API-server connection stays open and the reader stays parked.
  Always release through **`LogService.release(watch)`**, which closes `getOutput()` first. Only
  a **quiet** pod exposes this, and only against a live cluster.
- **A count is not a list.** `/counts` uses one `limit=1` request per kind plus
  `metadata.remainingItemCount` (`ResourceService.count`), not `listRaw().size()` — that was
  22.1 MB per call, re-fetched on every namespace switch. `remainingItemCount` is best-effort, so
  its absence is branched on explicitly: no continue token means the page is the whole collection;
  truncated-without-the-field falls back to a full list.
- **fabric8 is BOM-pinned.** `kubernetes-client-bom` aligns client + model + mock-server; bump the
  one property, never individual fabric8 artifacts.
- **JLine is pinned on the UBER artifact, and pinning the split ones overrides nothing.**
  `tamboui-jline3-backend` depends on `org.jline:jline` — the uber jar — which bundles
  `org/jline/terminal/**`, `org/jline/reader/**` and `org/jline/builtins/**` under the same
  package names as `jline-terminal` / `jline-reader` / `jline-builtins`. Managing the *split*
  artifacts forward therefore puts a **second copy** of every class beside the stale uber and jar
  order picks the winner: measured, `org.jline.builtins.ScreenTerminal` — the exec pane's whole VT
  emulator — still loaded from 3.25.1, silently, because duplicate classes are not an error.
  Manage `org.jline:jline` to `${jline.version}` and **do not name the split artifacts**.
  `JLineSingleProviderTest` asserts the outcome (one classpath URL per class, all from one jar, at
  the pinned version) rather than the intent — "it works today" is what a duplicated classpath
  looks like right up until it does not.
- **kubeconfigs are secrets.** `.gitignore` blocks `*.kubeconfig`, `kubeconfig`, `.kube/` — never
  commit one; mount it or point `KUBECONFIG` at it.

## Deployment

- **Image**: Cloud Native Buildpacks via the `docker` profile (`./mvnw -Pdocker -pl kweblens-web
  -am package -Ddocker.image.name=… -Ddocker.publish=true`). Only `kweblens-web` declares the
  Spring Boot plugin, so only it produces an image.
- **In-cluster vs out**: with a service account mounted the fabric8 client auto-detects
  in-cluster config, otherwise it uses the mounted kubeconfig. Give the pod a (read-only, to
  start) RBAC role scoped to what the dashboard lists.

## Release (Maven Central)

- **Nothing has ever been released** — no tags, no GitHub releases, version still
  `0.1.0-SNAPSHOT`, both library artifacts 404 on Central. Do not describe any module as
  "published"; it is roadmap item **R2**. The *machinery* is no longer the gap:
  `.github/workflows/image.yml` (#311) builds `kweblens-web`, smoke-tests that the image reaches
  a healthy actuator, and pushes to GHCR on a `v*` tag or on a `workflow_dispatch` where
  `publish` is explicitly true (it defaults to **false**). That tag exists only because a human
  ran `maven_release.yml`.
- **Only the libraries publish**: `kweblens-core`, `kweblens-cli` (+ parent). `kweblens-web` and
  `kweblens-tui` are not in the top-level `<modules>` — they live in an `activeByDefault`
  `default` profile, so `-Prelease` drops them and the `docker` profile re-adds only the web app.
  **Any new `-P` profile that needs the app must also list `<module>kweblens-web</module>`.**
  The applications stay off the publishing path on purpose: their public surface is a screen, not
  an API anyone compiles against, and a published coordinate owes callers a jar that starts —
  which `kweblens-cli`, the one application that does publish, failed for its entire life (#363).
- **Cut a release** via the `Maven release` workflow (`versions:set` → `verify` → tag → `deploy
  -Prelease` → next SNAPSHOT). Publishing is **irreversible — never trigger without explicit
  go-ahead.** Versions are numeric `MAJOR.MINOR.PATCH`; `-SNAPSHOT` only on dev.
- Secrets: `OSSRH_USERNAME`, `OSSRH_PASSWORD`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`.

## MCP server

`kweblens-web` runs an in-jar MCP server exposing **17 read-only tools**, registered together by
`McpConfig`'s `MethodToolCallbackProvider`: `ClusterTools` (orientation, 4), `DiagnosticTools`
(evidence, 4), `HealthTools` (verdicts, 9). A new tool is one `@Tool` method on a bean wired into
that provider.

**The count is a gate, not a habit** (#383). `McpToolSurfaceTest` asserts two things and both are
load-bearing. **The set of `@Tool` methods in `web/mcp` must EQUAL what the provider registers** —
a count would pass the day someone adds a tool to a bean `McpConfig` does not name, which is a
method nobody can call. And **every live document must state the registered total**: the number is
on the README's feature table, in `docs/deployment.md`, and on three `docs/modules/ROOT/pages/`
pages, it is the first thing a reader checks, and it went stale. Dated snapshots (the roadmap's
history, `CHANGELOG.adoc`, audits, research notes) are deliberately **not** in that list — theirs
is the number that was true when they were written. A listed file that *stops* stating the count
is also a failure, so the list cannot quietly claim coverage it no longer has.

**A tool must not be able to buy an inference.** `HealthTools.diagnose` serves
`DiagnoseService.diagnose()`'s deterministic findings, and the tool bean injects
**`DeterministicDiagnosis`** — a port with no `analyse` method, the same enforcement `kweblens-tui`
uses to be read-only. An interface only constrains the field someone declared, so
`McpToolsNeverCallAModelTest` fails the build if any shipped class in `web/mcp` so much as
*mentions* `DiagnoseService` or `org.springframework.ai.chat`, and if the port ever declares a
second method. **`ToolRedaction` does not apply to it and that is not an omission**: it takes a
`GenericKubernetesResource` and strips Secret `data`/`stringData` and
`last-applied-configuration`, while a `DiagnoseResult` holds no object at all — only strings,
booleans and an `Instant`, none copied from a `spec`, a `data` map or an annotation. The standing
rule is about tools returning **raw objects**; `checkSecurity` and `getEvents` are already
projections on the same footing. What it does not claim: a finding's `detail` can be an event
message, i.e. cluster-controlled text, and `ToolRedaction` would not have caught a credential
there either — it recognises Secret *fields*, not secret-shaped strings.

**A tool that shares a UI structure must walk it, not sample its top level.**
`list_resource_kinds` mapped `NavCategory.items()` only, so on every cluster it answered "no
custom kinds" — the nav's CRDs hang off `subgroups()`, one per API group, and ~110 kinds were
invisible to the one tool that exists so an assistant need not guess an id (#436). It now walks
the tree and emits each sub-group as its own category labelled `Custom Resources / <api group>`:
the category name is the only context a tool gives, and the reader is picking a `resourceId`, not
rendering a menu. A **promoted** kind stays at exactly one appearance for free, because
`ClusterNavService` removes it from the sub-groups it builds — a union of the two levels would
double it. The invariant is testable and is the gate: **walk the output, resolve every
`resourceId` through `ClusterNavService.find`, and assert nothing the nav knows is missing**
(`ClusterToolsKindsTest`). A kind count would pass the day someone adds a kind.

**Transport: SSE over WebMVC.** `GET /sse` holds the stream open and emits `event:endpoint`;
messages POST to `/mcp/message`. **There is no `POST /mcp`** — it 404s. This was probed against a
running server, so don't "correct" it from `application.yml`. It is also why the CSRF exemption
`ignoringRequestMatchers("/api/**", "/mcp/**")` matters — it is what makes MCP callable at all.
It is a **CSRF** exemption, not an authentication one: `GET /sse` rides open-mode's public read
path, but the JSON-RPC messages are `POST /mcp/message`, so `anyRequest().authenticated()` catches
them and an unauthenticated call measures **401 in open-mode too**. **An MCP client always needs
the admin credential**, and a client given none handshakes and then reports a flat "failed to
connect". Attach commands, per client, verified against a running instance:
[`docs/modules/ROOT/pages/attach-an-agent.adoc`](docs/modules/ROOT/pages/attach-an-agent.adoc).

**Tool output is redacted at the boundary** by `ToolRedaction` (Secret values and
`last-applied-configuration` replaced, keys kept; `managedFields` dropped). The asymmetry with
the dashboard is deliberate: tool output leaves the machine and lands in inference logs. **Any
new tool returning raw objects must go through it.**

## Standing rules

"What next" lives in [`docs/design/roadmap.md`](docs/design/roadmap.md). These outlive any plan:

- **Helm goes through jhelm, never the `helm` binary.** The declared artifacts are
  `org.alexmond:jhelm-kube` and `jhelm-rest-starter` (1.5.0) — `jhelm-core` is only transitive,
  so pin and bump those two. kweblens is a **dogfood** of jhelm. Shipped as `web/helm/HelmService`.
  Do **not** add a `helm` CLI dependency. **Keep `jhelm.version` a RELEASED version — never a
  `-SNAPSHOT`**: jhelm publishes no snapshots, so a snapshot pin resolves only from the local
  `~/.m2` and CI goes red everywhere else. Central's *search* index does not return
  `org.alexmond:jhelm-*` — check `repo1.maven.org/maven2/org/alexmond/<artifact>/` directly
  before concluding a version isn't published.
- **Remediation is suggest → approve → apply — never autonomous, always audited, all writes
  behind auth.** `RemediationService` proposes four actions, each gated on a precondition that
  says when it cannot work, each applied only with `confirm=true`.
- **Each surface is a `web/<area>/` slice over a `kweblens-core` access service** — never cluster
  access reimplemented in a controller. That rule produced every Freelens-parity surface.
- **The left menu is a declarative nav registry** (category → kind → list-route) in `NavCatalog`,
  and **Custom Resources is dynamic**, generated from the cluster's CRDs grouped by API group.
  **What a cluster changes is which CRD-delivered kinds are in it, and nothing else.** Gateway is
  synthesised when its CRDs exist; Autoscaling is declared and gains the cluster's VPA kinds when
  theirs are. Both decisions live in `ClusterNavService`, both are presentation only (`find`
  resolves every catalog id on every cluster), and the case that must be tested is the one
  **without** the CRD. **A built-in kind is in the menu on every cluster regardless of how many
  objects it has** — an empty list is a correct answer, and #428 nearly shipped HPA as the single
  exception, which would have been a new principle with nothing on screen to explain it. So the
  nav asks the API server **nothing** about object counts: it is a hot path (every `/nav`, and
  `/counts` builds on it) and a probe there buys a rule that should not exist.
  **A CRD leaf's LABEL is derived, never tabulated** (`KindLabel`, #433). It re-spaces the kind
  at its camel-case humps with acronym runs kept whole (`HTTPRoute` → `HTTP Route`, never
  `H T T P Route`) and takes the plural from the CRD's own `spec.names.plural` — so the
  invariant `KindLabelTest` pins is that de-spacing and lower-casing a label gives back the
  resource path the API server serves. **Never add a kind→label table** (a second catalog of CRD
  names that goes stale silently) and **never hand-roll an inflector**: `Policy`/`Policies`,
  `Ingress`/`Ingresses` and the already-plural `VLogs` are not a suffix rule, and the cluster
  has already declared the answer. Labels are display only — `id` and `kind` stay raw, which is
  why `find()` and the MCP `list_resource_kinds` output are unmoved.
- **Design references**: `docs/references/freelens-ia.md` (full IA map) and
  `freelens-reference-deck.md`. The deck records its headless `xvfb` capture as blocked on this
  box — **that note is disputed**; re-test before relying on it either way.

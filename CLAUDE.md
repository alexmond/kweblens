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
- **Performance has two layers.** (1) *In the gate:* `useResourceData.test.ts` asserts the
  resource-list watch coalesces a burst into ≤1 `objects` update per animation frame. **When you
  add a live-updated list, add its watch to that batching pattern** (buffer + flush per rAF).
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
  `mcp/`, `nav/` (`NavCatalog`: 39 built-in kinds / 7 static categories + discovered CRDs;
  `ClusterNavService` promotes a Gateway category when those CRDs exist), `helm/`, `exec/`,
  `files/`, `search/` (global search), `diag/`, `ai/`, `sim/`, `config/`.
  `/actuator/{health,info,metrics,prometheus}` — note `metrics` and `prometheus` are **not** in
  `SecurityConfig`'s permit list, so they are public in open-mode and authenticated in closed.
- **`kweblens-cli`** — dependency-light inspector (picocli + `picocli-spring-boot-starter`, which
  is what supplies the `CommandLine.IFactory` bean the app injects); runnable fat jar is the
  `exec` classifier. It resolves a kubeconfig and never builds a client, so it depends on fabric8's
  **`kubernetes-client-api`, not `kweblens-core`** — core dragged 25 unused jars into the fat jar.
  A subcommand that does talk to a cluster must go back through `ClusterRegistry` (#372). **`kweblens-it`** — the module is in the `default` profile and **is** compiled every
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
- **Only the libraries publish**: `kweblens-core`, `kweblens-cli` (+ parent). `kweblens-web` is
  not in the top-level `<modules>` — it lives in an `activeByDefault` `default` profile, so
  `-Prelease` drops it and the `docker` profile re-adds it. **Any new `-P` profile that needs the
  app must also list `<module>kweblens-web</module>`.**
- **Cut a release** via the `Maven release` workflow (`versions:set` → `verify` → tag → `deploy
  -Prelease` → next SNAPSHOT). Publishing is **irreversible — never trigger without explicit
  go-ahead.** Versions are numeric `MAJOR.MINOR.PATCH`; `-SNAPSHOT` only on dev.
- Secrets: `OSSRH_USERNAME`, `OSSRH_PASSWORD`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`.

## MCP server

`kweblens-web` runs an in-jar MCP server exposing **16 read-only tools**, registered together by
`McpConfig`'s `MethodToolCallbackProvider`: `ClusterTools` (orientation, 4), `DiagnosticTools`
(evidence, 4), `HealthTools` (verdicts, 8). A new tool is one `@Tool` method on a bean wired into
that provider — **keep the count correct here and in the README**, it is the number people check.

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
- **Design references**: `docs/references/freelens-ia.md` (full IA map) and
  `freelens-reference-deck.md`. The deck records its headless `xvfb` capture as blocked on this
  box — **that note is disputed**; re-test before relying on it either way.

# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project Overview

kweblens is a **web-based Kubernetes IDE** — Freelens/Lens reimagined as a self-hosted
Spring Boot web app instead of an Electron desktop app. It connects to one or more clusters
(via kubeconfig), browses their resources, edits and applies YAML, streams pod logs, shows
events and metrics, execs into pods and manages Helm releases — all from a browser. It also
exposes the same read-only cluster view to AI assistants over **MCP**.

Built with **Spring Boot 4.0.6 / Java 21**, a multi-module Maven build
(`org.alexmond:kweblens-parent`, version `0.1.0-SNAPSHOT`). Cluster access is via the
**fabric8 Kubernetes client**; the UI is a **Vue 3 + Vite + TypeScript SPA** (Naive UI, dark
theme) in `kweblens-ui`, built into the jar and served at `/`. The old Thymeleaf/htmx "classic"
UI was **deleted** (PR #124) — if you find a reference to it, it's stale.

**Two documents settle questions this file only summarises. Read them before planning work:**

- [`docs/design/adr-001-identity-model.md`](docs/design/adr-001-identity-model.md) — **ACCEPTED**.
  kweblens targets a **single trusted operator**: multi-tenancy is not a goal, identity sources
  are deferred, impersonation is the sanctioned mechanism if identity ever arrives. So "no
  per-user identity" is a **position, not a gap** — but the product must keep saying out loud
  that it runs as one shared credential.
- [`docs/design/roadmap.md`](docs/design/roadmap.md) — the thesis, the re-ranked gap list and
  the build order, verified against the code rather than against older docs. It is the current
  answer to "what next". Shipping has been fast; treat any planning doc older than a week as
  suspect and check the code.

## Build, Test & Verify

```bash
scripts/dev-verify.sh                 # format + whole-reactor verify — green here = green PR
scripts/dev-test.sh <selector>        # targeted -Dtest run (last arg = selector); e.g.
scripts/dev-test.sh 'ResourceServiceTest,Cluster*'
./mvnw spring-javaformat:apply        # auto-format (run before committing)

scripts/dev-run.sh                    # run locally on :8080, login admin/admin (--sim, --files, --port, --stop)
scripts/pr-watch.sh <pr> [--merge]    # wait on CI; exit 1 on any failure

# UI checks — all drive a RUNNING instance (see "Performance testing" below)
export NODE_PATH=$HOME/.local/lib/playwright/node_modules
node scripts/ui-shot.mjs              # screenshots: viewport x theme MATRIX, not one image
node scripts/ui-measure.mjs '.sel'    # box / overflow / chars-per-line; exits 1 over budget
node scripts/contrast-check.mjs       # WCAG contrast of rendered UI, BOTH themes; exits 1 on failure
node scripts/perf-sweep.mjs           # on-demand hang/long-load sweep
```

Full descriptions: [`scripts/README.md`](scripts/README.md). **Before doing anything that needs
to see or measure the running UI, load the `playwright` skill**
([`.claude/skills/playwright/SKILL.md`](.claude/skills/playwright/SKILL.md)) — it covers every
script here, the traps that make a run lie to you, and a Learnings log of the times one did.
It is self-improving by rule: when a run misleads you, fix the *script*, record why in its own
header, and log it.

- `dev-verify.sh` / `dev-test.sh` are the intended entry points (allowlist them in
  `.claude/settings.json` to run without prompts).
- **Start the app with `scripts/dev-run.sh`, not `java -jar`.** With no admin password set,
  `SecurityConfig` generates one per run and only logs it — so `admin`/`admin` silently stops
  working. The script always passes the dev credentials and fails loudly if one is generated
  anyway. Never move those credentials into `application.yml`.
- **Colour is measured, not eyeballed.** `contrast-check.mjs` exists because this has been got
  wrong repeatedly (a 1.93:1 badge in #169; the command palette's first two stylings at 3.02:1
  and 3.80:1 in #200, both of which looked fine). Run it against any change to `styles.css`. A
  selector that is not on screen reports `not present` rather than passing — use `PREPARE` to
  open it, and treat a screenful of `not present` as a failed run.
- **Size is measured too.** `ui-measure.mjs` is the companion to the above: box, overflow against
  the nearest clipping ancestor, characters per line. An `absent` selector is a failed
  measurement, not a pass.
- **Capture the matrix, not one image.** `ui-shot.mjs` defaults to three widths × both themes,
  because captures taken ad hoc at ~1400px in one theme are why a 338-character prose line
  (#235) and black-on-black cards both survived weeks of screenshots. Output lands in
  `.playwright/` — **gitignored, and it must stay that way**: those images carry the cluster's
  API-server hostname, node names and namespaces.
- **Suspect the instrument before the code.** Every wrong UI conclusion here came from a broken
  measuring tool, not broken reasoning: a sample point on a glyph, PNG *bytes* compared instead
  of decoded pixels, a `0.5em` glyph guess 20% out, `color(srgb …)` read as 0-255 (#245), a
  backdrop walk that climbs past sibling-painted backgrounds (#250). Build a positive control —
  a case whose answer you already know — before believing a surprising result.
- CI (`.github/workflows/ci.yml`) runs `./mvnw -B verify` on JDK 21 — same gates as `dev-verify`.
- **Performance testing (two layers).** (1) *Regression guard, in the gate:* a Vitest test
  (`kweblens-ui/src/composables/useResourceData.test.ts`) asserts the resource-list watch
  coalesces a burst of N events into ≤1 `objects` update per animation frame — the invariant
  that prevents the "large-list watch flood" freeze from returning (runs in `npm run check` /
  `dev-verify`, no browser/cluster). (2) *Site-wide sweep, on-demand:* `scripts/perf-sweep.mjs`
  (Playwright) walks every nav leaf against a running instance and fails if any page's
  time-to-first-row or max main-thread block (a hang) exceeds budget (`LOAD_MS`/`BLOCK_MS`).
  Run it against the **built-in simulator** for a headless, cluster-free run at configurable
  scale — start the app with `KWEBLENS_SIMULATOR_ENABLED=true KWEBLENS_LOAD_KUBECONFIG=false
  KWEBLENS_SIMULATOR_SIZE=200` (registers a generated `sim` cluster; see `web/sim/`), then
  `node scripts/perf-sweep.mjs`. On-demand, not a per-commit gate. **When you add a
  live-updated list, add its watch to the batching pattern** (buffer + flush per rAF) so it
  can't flood.
- **A rig whose objects are unrepresentative measures the rig.** The simulator's objects were
  once a 739-byte pod against a real cluster's 7.8 KB, which is how a scale pass concluded "the
  API is comfortable, ~88 bytes/row" and was wrong by 50–500×. `web/sim/` now generates objects
  sized, shaped and distributed like measured live ones (managedFields, real `data` with a long
  tail, statuses, conditions) and seeds a realistic minority of **unhealthy** ones —
  crash-looping, unschedulable, evicted pods, Services with no Endpoints, a NotReady node,
  Warning events — all deterministic in the object's index, so runs are comparable and every
  state appears within the first 100. Two standing consequences: **check any new kind you seed
  with `scripts/payload-bytes.mjs` against a live cluster** before quoting a number from it; and
  the simulator still **cannot validate paging** (the CRUD mock ignores `limit`) and costs 7
  minutes to seed 3 000/kind, so KWOK remains the answer for paging and for anything larger.
  Measurements and the verdict: `docs/design/scale-measurements.md`.
- Tests are **hermetic**: no live cluster. The fabric8 `kubernetes-server-mock`
  (`@EnableKubernetesMockClient(crud = true)`) serves an in-JVM API server; web tests set
  `kweblens.load-kubeconfig=false` so the registry starts empty and the test seeds its own client.

## Architecture (modules)

- **`kweblens-core`** — the cluster access layer, no web concerns. `cluster/` (the
  `ClusterRegistry` that owns one fabric8 `KubernetesClient` per cluster id + the `ClusterInfo`
  view), `resource/` (`ResourceService` projects Kubernetes objects into kind-agnostic
  `ResourceSummary` rows; `RelationService` resolves the detail drawer's relation sections),
  `health/` (the deterministic workload/network/storage/config checks, shared by the dashboard,
  `/diagnose` and the MCP tools), `event/`, `log/`, `exec/`, `metric/`, `portforward/`,
  `schema/`, `config/` (`KweblensProperties`). **Published** to Maven Central.
- **`kweblens-web`** — the runnable Spring Boot app. Slices: `web/api/` (JSON API +
  `ProblemDetail` error mapping), `web/ui/` (`SpaController` — serves the built Vue SPA),
  `web/security/` (`SecurityConfig` + `AuditService` — see the security gotcha), `web/mcp/`
  (three `@Tool` beans — `ClusterTools` / `DiagnosticTools` / `HealthTools` — wired into
  `McpConfig`'s provider, plus `ToolRedaction`), `web/nav/` (`NavCatalog` — the categories→kinds
  registry, 39 built-in kinds across 7 static categories + discovered CRDs; `ClusterNavService`
  promotes a Gateway category at runtime when the Gateway API CRDs exist), `web/helm/`
  (jhelm-backed release surface), `web/exec/` (exec-over-WebSocket), `web/files/` (pod file
  browser over one-shot exec — **off by default**, `kweblens.files.enabled`; the UI is the pod
  detail drawer's **Files** tab, which takes both gates plus the write cap from `/api/v1/about`
  (`podFiles.enabled` / `.writable` / `.maxWriteBytes`), so Edit and Upload are offered only where
  a write can succeed, and still learns from the first listing against an older server.
  **Anything piped into a container must bound its own read of stdin** — the exec API has no
  end-of-input signal, so a script that reads to EOF hangs for the whole `command-timeout` and
  then lands anyway once the connection drops, i.e. reports failure for a write that happened), `web/diag/` (the diagnostics panel's capability report), `web/ai/`
  (`DiagnoseService` — LLM enrichment inert unless `kweblens.ai.enabled` and a key are set —
  plus `RemediationService`, which is **not** AI-gated), `web/sim/` (the in-JVM cluster
  simulator), `web/config/` (`ClusterBootstrap` seeds the ambient kubeconfig as cluster
  `default` on startup; `ClusterConfigApiController` in `web/api/` adds/edits/removes clusters
  at runtime). `/actuator/{health,info,metrics,prometheus}` exposed.
  **A `GET` never calls a model (#251).** `DiagnoseService.diagnose()` returns the deterministic
  findings plus only what `DiagnosisSummaryCache` already holds *for exactly those findings* —
  a SHA-256 of the finding list is the key, so a cluster that changed is a miss rather than a
  stale verdict, and the cache is in-memory/per-process like `AuditService`. `analyse()`
  (`POST /api/v1/clusters/{id}/diagnose/summary`, hence auth-gated in both security modes, and
  audited) is the only caller of the LLM. Do not reintroduce an on-read or on-stale
  auto-analysis.
  **Not published** — ships as a container image.
- **`kweblens-cli`** — a dependency-light cluster inspector (picocli). Prints the cluster the
  ambient kubeconfig points at. **Published**; runnable fat jar is the `exec` classifier.
- **`kweblens-it`** — on-demand operational tasks (connectivity/health) tagged `it`, excluded
  from the default build. Not published.

Config is env-var driven (`kweblens-web/src/main/resources/application.yml`): `PORT`,
`KWEBLENS_LOAD_KUBECONFIG`, plus `kweblens.clusters[*]` for statically-configured clusters.
Settings class: `KweblensProperties` (`kweblens.*`).

## Code Style & Quality (all fail the build at `validate`)

- **spring-javaformat** 0.0.47 — tabs, Spring conventions. Run `:apply` before committing.
- **Checkstyle** 3.6.0 (+ Spring checks) — `checkstyle.xml` / `checkstyle-suppressions.xml`;
  hard-fails `FileLength` at 800, `MethodLength` at 80.
- **PMD** 3.28.0 — `pmd-ruleset.xml`.
- **JaCoCo** 0.8.15 line gates: `kweblens-core` 0.70, `kweblens-web` 0.50, `kweblens-cli` 0.60
  (entry-point classes excluded). Raise these as real coverage grows.
- **Lombok** `@RequiredArgsConstructor` for constructor injection; `@ConfigurationProperties`
  for config.
- **File size** — target source files **under ~500 lines**; split fat controllers into per-page
  `*PageService` helpers. Guideline, not a gate (Checkstyle hard-fails at 800).

Recurring lint rules that bite: **SpringLambda** requires parentheses around a single lambda arg
(`(e) -> …`, never `e -> …`) — `spring-javaformat:apply` does **not** add these, so it fails at
`validate` on the next build; **SpringTernary** wants `(a != b) ? x : y` (parenthesized,
prefer `!=`); **InnerTypeLast** (nested types after methods — see `ClusterRegistry.Entry`);
**UseUnderscoresInNumericLiterals** (`86_400`); **AppendCharacterWithChar** (`sb.append('m')`).

## Gotchas (load-bearing)

- **Building a fabric8 client does not connect.** `new KubernetesClientBuilder().build()` only
  resolves config; the first API call is what reaches the cluster. This is why `ClusterBootstrap`
  can seed `default` at startup and why tests need no live cluster — but it also means a bad
  kubeconfig fails *lazily* on first use, not at boot.
- **The `ClusterRegistry` owns client lifecycles.** Never `new` a `KubernetesClient` in a
  controller/service — ask the registry (`require(id)` / `client(id)`). Re-registering an id
  closes the previous client. Everything is addressed by cluster **id**, never by a raw client.
- **Tests must not hit a real cluster.** Use `@EnableKubernetesMockClient(crud = true)` and set
  `kweblens.load-kubeconfig=false` so `ClusterBootstrap` skips ambient discovery; then register
  the mock `client` into the autowired `ClusterRegistry` in `@BeforeEach`.
- **Spring Boot 4 moved packages.** Actuator health is `org.springframework.boot.health.*`;
  autoconfigure is split per-module. Verify imports against the jars, not Boot 3 memory.
- **Security: reads are open, writes are authenticated.** `SecurityConfig` has two modes.
  In **`open-mode` (the default)** `GET` endpoints are public so the dashboard and CI work out of
  the box, while **every non-`GET`** (apply, patch, exec, port-forward, Helm) requires the admin
  login; with `kweblens.security.open-mode=false` everything but health and the login page needs
  auth. There is **one in-memory admin** (`kweblens.security.admin-username`/`admin-password`); if
  no password is set one is **generated at startup and logged** — never bake a default password
  into the source. The authenticated context is persisted to the `HttpSession` as well as the
  request, because the SPA's one-shot Basic login must establish a `JSESSIONID` for the exec
  WebSocket (a browser cannot attach Basic auth to a WS handshake). A few path families are
  authenticated **even in open-mode**, because what they return is itself a secret: pod exec,
  Helm release/library values, and the whole pod-file-browser family.
- **One shared identity is the accepted design, not a TODO.** There is no OIDC / per-user
  identity and no RBAC-awareness (no `SelfSubjectAccessReview` anywhere), so the UI can offer
  actions that then 403, and audit entries name an action but no person. Per ADR-001 that is
  **decided** — do not open work to "fix" it, and do not describe it as a gap. Do keep saying it
  plainly in user-facing text; the ADR requires that honesty. SSAR is sanctioned only as a UI
  affordance (grey out what won't work), never as an authorization gate — it fails open.
- **"Suggest → preview → confirm → apply" — know which link is real, per surface.** This used
  to be uniformly weaker than it sounded; two thirds of it has since been fixed, so the
  *specific* remaining gap is what matters:
  - **Helm** — a genuine jhelm `dryRun`. Always was.
  - **Remediation** — `scale-up` and `rollout-restart` take a real server-side `dryRun=All`
    via `ResourceService.dryRunPatch` (#209). `restart-pod` and `rollback` **cannot** — a
    DELETE and a revision lookup are not patches — so `RemediationService.preview` returns
    `notChecked` naming the reason instead of prose that reads like a server answer. Saying
    "we did not check" is the design, not an omission.
  - **The YAML editor** now shows two diffs (#274): the edit against what was loaded, and
    live against what `dryRun=All` says the cluster would store — so defaulting, another
    manager's fields and admission all surface before the write. `dryRunApply` shares
    `apply`'s normalisation via `forApply`; a preview made of different bytes describes a
    different request. A refusal is rendered as a **result**, not an error.
  - **What is still not covered:** the `apply` that actually writes is unchanged — it is
    still `forceConflicts().serverSideApply()` with no `dryRun`, because that is the write.
    The preview is a separate request the operator can choose to run; nothing forces them to
    look at it before pressing Apply.
  - **Audit survives a restart** (#210/#212): every entry is written to a dedicated
    `kweblens.audit` logger *as well as* the in-memory 500-entry ring, so the ring is only the
    live view behind `/audit` and eviction no longer loses the record. Values are quoted and
    escaped and control characters stripped, because a target can carry attacker-influenceable
    text (a pod file path, a Helm release name) and a newline could otherwise forge a second,
    fake audit line. The category is pinned to INFO so `logging.level.root=WARN` cannot
    silently switch the trail off.
- **A list row is not the whole object (#276).** `web/api/ListProjection` strips `managedFields`
  from every list payload and ships ConfigMap/Secret `data`/`stringData`/`binaryData` as **keys
  with `null` values** — 9.85 MB → 1.28 MB across six kinds on the real cluster, 98.8% of it on
  Secrets alone. It sits at the **web boundary**, like `ToolRedaction` does for MCP, and
  deliberately *not* in `ResourceService.listRaw`, which the health checks, overviews,
  `RelationService` and the MCP tools share and which legitimately needs those values. Both the
  `objects` endpoint **and** the `objects/watch` SSE stream go through it — separate code paths,
  same table. So: the drawer refetches the full object via `GET …/object` when
  `needsFullObject(row)` (`kube.ts`) sees a `null` value, which is why that predicate reads the
  row rather than a list of kinds — a hard-coded kind list here would be a second copy of the
  server's rule and the copy that goes stale silently. A `null` value renders as `—`, never as
  an empty string: "we did not send it" and "it is empty" are different claims. This is **not**
  redaction — the drawer still shows every value, per ADR-001.
- **An SSE stream that never writes never notices a disconnect.** `SseEmitter` learns its
  client is gone only from a *failed write*; nothing polls the socket. The resource-list watch
  writes only when the watched kind produces an event, so on a quiet kind a departed subscriber
  was never noticed and its API-server watch stayed open — measured at **5 min+** for `pods`,
  and **22 open watches for one operator** who walked twenty kinds in one tab. `SseKeepAlive`
  (`web/api`) writes a `:keepalive` comment every 15 s, which fails, completes the emitter and
  runs the `onCompletion` hook that closes the watch. **Any new `SseEmitter` whose writes are
  data-driven needs it** (the log streams still don't have it). The fan-out decision this
  produced — accept one watch per open list view, don't share — is
  `docs/design/watch-fanout.md`.
- **A count is not a list.** `/counts` computes its 118 badge numbers with one `limit=1`
  request per kind plus `metadata.remainingItemCount` (`ResourceService.count`), not
  `listRaw().size()` — that was 22.1 MB of API-server traffic per call on the real cluster, and
  it is re-fetched on every namespace switch. `remainingItemCount` is **best-effort**, so its
  absence is branched on explicitly: no continue token means the page is the whole collection
  (exact), truncated-without-the-field falls back to a full list. The fabric8 CRUD mock
  **ignores `limit`**, so `ResourceCountTest` asserts on the exact outgoing query string — a
  test that seeded objects and counted them would pass whether or not the flag was sent.
- **fabric8 version is BOM-pinned.** `kubernetes-client-bom` (`${fabric8.version}`) aligns
  client + model + mock-server; bump the one property, never individual fabric8 artifacts.
- **kubeconfigs are secrets.** `.gitignore` blocks `*.kubeconfig`, `kubeconfig`, `.kube/` — never
  commit one. Mount it into the container / point `KUBECONFIG` at it instead.

## Deployment

- **Image**: built by Cloud Native Buildpacks via the `docker` Maven profile
  (`./mvnw -Pdocker -pl kweblens-web -am package -Ddocker.image.name=… -Ddocker.publish=true`).
  Only `kweblens-web` declares the Spring Boot plugin, so only it produces an image.
- **In-cluster vs out-of-cluster**: with a service account mounted, the fabric8 client
  auto-detects in-cluster config; otherwise it uses the mounted kubeconfig. Give the pod a
  (read-only, to start) RBAC role scoped to what the dashboard lists.

## Release (Maven Central)

- **Only the libraries publish**: `kweblens-core`, `kweblens-cli` (+ parent pom). The app
  `kweblens-web` is **not** in the top-level `<modules>` — it lives in an `activeByDefault`
  `default` profile. So `-Prelease` deactivates `default` and drops web from the reactor (apps
  don't go to Central); the `docker` profile **re-adds** web. **Gotcha:** any new `-P` profile
  that needs the app must also list `<module>kweblens-web</module>`.
- **Cut a release** via the `Maven release` workflow (`workflow_dispatch`, inputs
  `releaseVersion` / `nextVersion`): `versions:set` → `verify` → tag `v<version>` →
  `deploy -Prelease` (GPG sign + `central-publishing-maven-plugin`) → bump to next SNAPSHOT.
  Publishing is **irreversible** — never trigger without explicit go-ahead.
- Versions are numeric `MAJOR.MINOR.PATCH` (no `-RC`/`-M` qualifiers); `-SNAPSHOT` only on dev.
- Secrets (repo-level, from infra's `gh-release-secrets.sh`): `OSSRH_USERNAME`, `OSSRH_PASSWORD`,
  `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`.

## MCP server

`kweblens-web` runs an in-jar MCP server exposing **15 read-only tools** to AI assistants,
across three beans registered together by `McpConfig`'s `MethodToolCallbackProvider`:

- `ClusterTools` (orientation, 4): `listClusters`, `listNamespaces`, `listPods`,
  `listResourceKinds`
- `DiagnosticTools` (evidence, 4): `describeResource`, `listResources`, `getEvents`,
  `getPodLogs`
- `HealthTools` (verdicts, 7): `checkWorkloadHealth`, `checkNetworkHealth`,
  `checkStorageHealth`, `checkConfigUsage`, `getPodUsage`, `getNodeUsage`, `listHelmReleases`

A new tool is one `@Tool`-annotated method on a bean wired into that provider. **Keep the count
in this file and in the README correct when you add one** — it is the number people check.

**Transport: SSE over WebMVC** (`spring-ai-starter-mcp-server-webmvc`). `GET /sse` holds the
stream open and emits `event:endpoint` / `data:/mcp/message?sessionId=…`; messages POST to
`/mcp/message`. There is no `POST /mcp` — it 404s. This was probed against a running server, so
don't "correct" it from `application.yml` (which sets no `spring.ai.mcp.server.protocol`).
It is also why the CSRF exemption matters: `ignoringRequestMatchers("/api/**", "/mcp/**")`
covers `/mcp/message`, which is what makes MCP callable at all.

**Tool output is redacted at the boundary** by `ToolRedaction`: Secret `data`/`stringData`
values and the `last-applied-configuration` annotation are replaced (keys kept), `managedFields`
dropped. The asymmetry with the dashboard is deliberate — tool output leaves the machine and
lands in inference logs. Any new tool returning raw objects must go through it.

## Standing rules and roadmap

**Where "what next" lives:** [`docs/design/roadmap.md`](docs/design/roadmap.md), re-derived
against the code and against ADR-001. The items below are the *standing constraints* that
outlive any one plan, plus references. Several bullets that used to describe future work are
now shipped and are kept only for the rule they carry.

- **Helm — use jhelm, not shelling out to the `helm` binary.** Freelens has a Helm-releases view;
  kweblens's Helm slice MUST be built on the sibling **jhelm** library
  (`org.alexmond:jhelm-core`, currently `1.5.0`) — kweblens is also a **dogfood** of
  jhelm. `jhelm-core` is Spring-Boot-autoconfigured (`JhelmCoreAutoConfiguration`) and exposes an
  `action/` API (`StatusAction`, `HistoryAction`, `ListAction`, `CreateAction`/upgrade, etc.).
  **Shipped** as `web/helm/HelmService` (install / upgrade / rollback / uninstall / history /
  values library / repo refresh, each with a real `dryRun`) — note it lives in `kweblens-web`,
  not core. Pin the version via a `jhelm.version` property (BOM-align with the Boot line). Do
  **not** add a `helm` CLI dependency.
  **Keep `jhelm.version` a RELEASED version — never a `-SNAPSHOT`.** jhelm publishes releases to
  Maven Central but **no snapshots**, so a `-SNAPSHOT` pin resolves only from whatever happens to
  be in the local `~/.m2` and CI goes red on every build (this bit us: `1.3.1-SNAPSHOT` was green
  locally and unresolvable everywhere else). Note Maven Central's *search* index does not return
  the `org.alexmond:jhelm-*` releases — check `repo1.maven.org/maven2/org/alexmond/<artifact>/`
  directly before concluding a version isn't published.
- **AI troubleshooting agent** (issues #10/#11): **partly shipped.** `DiagnoseService` runs a
  deterministic pass over the `health/` checks and optionally enriches it with a Spring AI
  `ChatClient` (default Anthropic Claude, inert without a key). `RemediationService` proposes
  four actions — `restart-pod`, `rollout-restart`, `rollback`, `scale-up` — each gated on a
  precondition that says when it *cannot* work, and each applied only with `confirm=true` and
  audited. **The standing rule: remediation is suggest→approve→apply — never autonomous, always
  audited, all writes behind auth.** Note the preview is currently prose, not a server dry-run
  (see the security gotcha); roadmap T1 is the fix, and no new remediation action should be
  added expecting a real dry-run until it lands. Helm fixes go through jhelm; manifest fixes
  through the YAML apply path.
- **Freelens-parity surfaces** — pod logs, YAML view/edit + apply, events, live metrics,
  exec-into-pod, port-forward, Helm, overviews, detail drawer with relation sections, command
  palette, runtime cluster add/edit/remove: **all shipped.** The rule that produced them stands —
  each surface is a `web/<area>/` slice over a `kweblens-core` access service, never cluster
  access reimplemented in a controller.
- **Dashboard shell + left-nav IA** (issue #12): **shipped** — the Freelens-style shell with a
  cluster rail, collapsible category nav, one reusable resource-list component and a dockable
  terminal. The rule that stands: the left menu is a **declarative nav registry**
  (category → kind → list-route) in `NavCatalog`, and the **Custom Resources** section is
  **dynamic**, generated from the cluster's CRDs grouped by API group. Full IA + screenshots:
  `docs/references/freelens-ia.md`.
- **Design references**: `docs/references/freelens-reference-deck.md` — captioned Freelens
  walkthrough mapping each surface to kweblens; `freelens-ia.md` is the full IA map. The deck
  documents a headless `xvfb` capture procedure and records it as blocked on this box (no
  `xdotool`/compositor → black frames); **that note is disputed** — re-test before relying on
  it either way. The deck's own images are real captures.

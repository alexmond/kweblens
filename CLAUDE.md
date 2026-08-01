# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project Overview

kweblens is a **web-based Kubernetes IDE** — Freelens/Lens reimagined as a self-hosted
Spring Boot web app instead of an Electron desktop app. It connects to one or more clusters
(via kubeconfig), browses their resources, and (over time) will edit YAML, stream pod logs,
show events and metrics, and exec into pods — all from a browser. It also exposes the same
read-only cluster view to AI assistants over **MCP**.

Built with **Spring Boot 4.0.6 / Java 21**, a multi-module Maven build
(`org.alexmond:kweblens-parent`, version `0.1.0-SNAPSHOT`). Cluster access is via the
**fabric8 Kubernetes client**; the UI is a **Vue 3 + Vite + TypeScript SPA** (Naive UI, dark
theme) in `kweblens-ui`, built into the jar and served at `/`. The old Thymeleaf/htmx "classic"
UI was **deleted** (PR #124) — if you find a reference to it, it's stale.

## Build, Test & Verify

```bash
scripts/dev-verify.sh                 # format + whole-reactor verify — green here = green PR
scripts/dev-test.sh <selector>        # targeted -Dtest run (last arg = selector); e.g.
scripts/dev-test.sh 'ResourceServiceTest,Cluster*'
./mvnw spring-javaformat:apply        # auto-format (run before committing)

scripts/dev-run.sh                    # run locally on :8080, login admin/admin (--sim, --port, --stop)
scripts/pr-watch.sh <pr> [--merge]    # wait on CI; exit 1 on any failure

# UI checks — both drive a RUNNING instance (see "Performance testing" below)
export NODE_PATH=$HOME/.local/lib/playwright/node_modules
node scripts/perf-sweep.mjs           # on-demand hang/long-load sweep
node scripts/contrast-check.mjs       # WCAG contrast of rendered UI, BOTH themes; exits 1 on failure
```

Full descriptions: [`scripts/README.md`](scripts/README.md).

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
- Tests are **hermetic**: no live cluster. The fabric8 `kubernetes-server-mock`
  (`@EnableKubernetesMockClient(crud = true)`) serves an in-JVM API server; web tests set
  `kweblens.load-kubeconfig=false` so the registry starts empty and the test seeds its own client.

## Architecture (modules)

- **`kweblens-core`** — the cluster access layer, no web concerns. `cluster/` (the
  `ClusterRegistry` that owns one fabric8 `KubernetesClient` per cluster id + the `ClusterInfo`
  view), `resource/` (`ResourceService` projects Kubernetes objects into kind-agnostic
  `ResourceSummary` rows), `config/` (`KweblensProperties`). **Published** to Maven Central.
- **`kweblens-web`** — the runnable Spring Boot app. Slices: `web/api/` (JSON API +
  `ProblemDetail` error mapping), `web/ui/` (`SpaController` — serves the built Vue SPA),
  `web/security/` (`SecurityConfig` + `AuditService` — see the security gotcha), `web/mcp/`
  (`ClusterTools` `@Tool` methods + `McpConfig` provider), `web/nav/` (`NavCatalog` — the
  categories→kinds registry, 39 built-in kinds + discovered CRDs), `web/helm/` (jhelm-backed
  release surface), `web/exec/` (exec-over-WebSocket), `web/files/` (pod file browser over
  one-shot exec — **off by default**, `kweblens.files.enabled`), `web/ai/` (`DiagnoseService` +
  `RemediationService`, inert unless `kweblens.ai.enabled` and a key are set), `web/sim/` (the
  in-JVM cluster simulator), `web/config/` (`ClusterBootstrap` seeds the ambient kubeconfig as
  cluster `default` on startup). `/actuator/{health,info,metrics,prometheus}` exposed.
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
  WebSocket (a browser cannot attach Basic auth to a WS handshake). Still **missing**: OIDC /
  per-user identity, and RBAC-awareness (no `SelfSubjectAccessReview`), so the UI can offer
  actions that then 403.
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

`kweblens-web` runs an in-jar MCP server (SSE over WebMVC) exposing the read-only `ClusterTools`
(`listClusters`, `listNamespaces`, `listPods`) to AI assistants. A new tool is one `@Tool`-annotated
method on a bean wired into `McpConfig`'s `MethodToolCallbackProvider`.

## Planned integrations / roadmap

- **Helm — use jhelm, not shelling out to the `helm` binary.** Freelens has a Helm-releases view;
  kweblens's Helm slice MUST be built on the sibling **jhelm** library
  (`org.alexmond:jhelm-core`, currently `1.5.0`) — kweblens is also a **dogfood** of
  jhelm. `jhelm-core` is Spring-Boot-autoconfigured (`JhelmCoreAutoConfiguration`) and exposes an
  `action/` API (`StatusAction`, `HistoryAction`, `ListAction`, `CreateAction`/upgrade, etc.);
  wrap those in a `HelmService` in core and a `web/helm/` slice. Pin the version via a
  `jhelm.version` property (BOM-align with the Boot line). Do **not** add a `helm` CLI dependency.
  **Keep `jhelm.version` a RELEASED version — never a `-SNAPSHOT`.** jhelm publishes releases to
  Maven Central but **no snapshots**, so a `-SNAPSHOT` pin resolves only from whatever happens to
  be in the local `~/.m2` and CI goes red on every build (this bit us: `1.3.1-SNAPSHOT` was green
  locally and unresolvable everywhere else). Note Maven Central's *search* index does not return
  the `org.alexmond:jhelm-*` releases — check `repo1.maven.org/maven2/org/alexmond/<artifact>/`
  directly before concluding a version isn't published.
- **AI troubleshooting agent** (issues #10/#11): a Spring AI `ChatClient` that **tool-calls the
  existing cluster access layer** (`ClusterTools` + read tools) to validate/diagnose, then
  proposes guarded fixes. Model-configurable (default Anthropic Claude, inert without a key),
  mirroring the unitrack `AiAnalyzer`. **Remediation is suggest→approve→apply with a dry-run/diff
  by default — never autonomous, always audited, all writes behind auth.** Helm fixes go through
  jhelm; manifest fixes through the YAML apply path.
- **Later Freelens-parity surfaces**: pod logs (SSE), YAML view/edit + apply, events, live
  metrics, exec-into-pod. Each is a new `web/<area>/` slice over `kweblens-core` access services.
- **Dashboard shell + left-nav IA** (issue #12): grow past the scaffold's trivial table into the
  Freelens-style shell — cluster rail, collapsible category nav, per-category tab bar, one reusable
  resource-list component, dockable terminal. Model the left menu as a **declarative nav registry**
  (category → kind → list-route); the **Custom Resources** section is **dynamic**, generated from
  the cluster's CRDs grouped by API group. Full IA + screenshots: `docs/references/freelens-ia.md`.
- **Design references**: `docs/references/freelens-reference-deck.md` — captioned Freelens
  walkthrough mapping each surface to kweblens; `freelens-ia.md` is the full IA map. Headless
  `xvfb` capture is documented there but blocked on this box (no `xdotool`/compositor → black
  frames); the deck uses real captures.

# kweblens

A **web-based Kubernetes IDE** — [Freelens](https://freelens.app)/Lens reimagined as a
self-hosted Spring Boot server plus a Vue SPA, instead of an Electron desktop app. Point it at
your clusters, browse and edit their resources from any browser, stream logs, exec into pods,
manage Helm releases — and expose the same cluster view to AI assistants over MCP.

Because it runs as a server rather than on your laptop, one deployment serves a whole team, and
the browser needs no kubeconfig, no `kubectl`, and no local install.

> **Status: 0.1.0-SNAPSHOT — working, not yet production-ready.** The surfaces below are
> implemented and used daily against real clusters. The gap that matters is **identity**: there
> is a single admin account and no OIDC or per-user RBAC awareness yet. See
> [Security](#security) before deploying this anywhere shared.

## What works today

| Area | Detail |
|---|---|
| **Resource browsing** | 39 built-in kinds across 7 categories (Cluster, Workloads, Config, Network, Storage, Access Control), plus **Custom Resources discovered from the cluster's CRDs**, rendered with the CRD's own printer columns |
| **Live updates** | Kubernetes watches streamed to the browser over SSE, batched per animation frame so a large-list event burst can't freeze the tab |
| **YAML editing** | CodeMirror 6 with completion and validation driven by **the cluster's own OpenAPI v3 schema**, plus Form, Warnings and Review-Changes (diff) tabs, then apply or JSON-merge patch |
| **Logs** | Per-pod/container streaming (SSE), following from connection time |
| **Terminal** | Exec into a container over WebSocket, in a dockable terminal pane |
| **Port-forward** | Started and managed from the browser |
| **Helm** | Releases, history, values, rendered resources, upgrade, rollback and uninstall — via [jhelm](https://github.com/alexmond/jhelm), no `helm` binary |
| **Metrics** | Node and pod CPU/memory from metrics-server, with optional Prometheus/VictoriaMetrics for node CPU, memory and disk graphs |
| **Diagnostics** | A deterministic `diagnose` pass over cluster state, optionally enriched by an LLM (off unless configured) |
| **Node detail** | Overview, the pods scheduled on the node, and its metrics |
| **MCP server** | In-jar MCP (SSE over WebMVC) exposing read-only cluster tools to AI assistants |
| **Audit** | Mutating actions recorded and queryable at `/api/v1/audit` |

Write actions are **suggest → confirm → apply**: a diff or dry-run first, an explicit
confirmation, then an audit entry. Nothing mutates the cluster silently.

## Stack

- **Spring Boot 4.0.6 / Java 21**, multi-module Maven (`org.alexmond:kweblens-parent`)
- **fabric8 Kubernetes client** for all cluster access (BOM-pinned)
- **Vue 3 + Vite + TypeScript** SPA (Naive UI, dark theme), CodeMirror 6, ECharts, xterm.js —
  built into the jar and served at `/`
- **Spring AI MCP server** (SSE over WebMVC); optional Anthropic chat client for diagnostics
- Actuator + Micrometer/Prometheus; spring-javaformat / Checkstyle / PMD / JaCoCo gates

## Modules

| Module | What it is | Published |
|---|---|---|
| `kweblens-core` | Cluster registry, kubeconfig loading, resource/log/exec/metrics/schema access | ✅ Maven Central |
| `kweblens-cli`  | Dependency-light cluster inspector (picocli) | ✅ Maven Central |
| `kweblens-ui`   | The Vue SPA (bundled into `kweblens-web`) | ❌ |
| `kweblens-web`  | The runnable app (REST API + SPA + MCP) | ❌ container image |
| `kweblens-it`   | On-demand operational/connectivity tasks (tag `it`) | ❌ |

## Build & run

```bash
scripts/dev-verify.sh                        # format + full reactor verify (CI parity)
./mvnw -pl kweblens-web -am spring-boot:run  # then open http://localhost:8080
```

kweblens seeds your **ambient kubeconfig** (`KUBECONFIG` / `~/.kube/config`) as cluster
`default` on startup. Set `KWEBLENS_LOAD_KUBECONFIG=false` to start with none, or configure
clusters explicitly under `kweblens.clusters[*]`.

### No cluster? Use the built-in simulator

A generated in-JVM cluster, handy for development, CI and performance work:

```bash
KWEBLENS_SIMULATOR_ENABLED=true KWEBLENS_LOAD_KUBECONFIG=false \
KWEBLENS_SIMULATOR_SIZE=200 ./mvnw -pl kweblens-web -am spring-boot:run
```

## API

Read endpoints are `GET /api/v1/clusters/...`; the shapes are stable enough to script against
but not yet versioned beyond `v1`.

| Path | Returns |
|---|---|
| `/api/v1/clusters` | connected clusters |
| `/api/v1/clusters/{id}/nav` | the navigation catalog (categories → kinds), including discovered CRDs |
| `/api/v1/clusters/{id}/resources/{resourceId}/objects` | full objects of a kind |
| `/api/v1/clusters/{id}/resources/{resourceId}/objects/watch` | the same as a live SSE stream |
| `/api/v1/clusters/{id}/resources/{resourceId}/columns` | CRD-declared printer columns |
| `/api/v1/clusters/{id}/pods/{ns}/{pod}/log/stream` | pod log stream (SSE) |
| `/api/v1/clusters/{id}/schema` | OpenAPI v3 schema for a kind (drives editor validation) |
| `/api/v1/clusters/{id}/yaml` · `/apply` · `/patch` | read / apply / merge-patch an object |
| `/api/v1/clusters/{id}/diagnose` | diagnostics findings |
| `/api/v1/audit` | audit log of mutating actions |

Helm, metrics, port-forward and node endpoints live under the same `/api/v1` prefix.
`/actuator/{health,info,metrics,prometheus}` is exposed.

## CLI

```bash
java -jar kweblens-cli/target/kweblens-cli-exec.jar            # show current kubeconfig target
java -jar kweblens-cli/target/kweblens-cli-exec.jar -c staging # select a context
```

## Container image

```bash
./mvnw -Pdocker -pl kweblens-web -am package \
  -Ddocker.image.name=ghcr.io/alexmond/kweblens:0.1.0 -Ddocker.publish=true
```

With a service account mounted, the fabric8 client auto-detects in-cluster config; otherwise it
uses the mounted kubeconfig. Give the pod a least-privilege RBAC role scoped to what you list.

## Security

**Read this before deploying kweblens anywhere other people can reach.**

There is **one admin account**, held in memory. `kweblens.security.admin-username` and
`admin-password` configure it; if no password is set, a random one is **generated at startup and
logged**. No password is baked into the source.

Two modes:

- **`open-mode` (the default)** — read (`GET`) endpoints are public so the dashboard works out
  of the box, while **every write** (YAML apply, patch, exec, port-forward, Helm actions,
  anything non-`GET`) requires the admin login.
- **`kweblens.security.open-mode=false`** — everything except health and the login page requires
  authentication.

Known gaps, stated plainly:

- **No OIDC / no per-user identity.** One shared admin is not a multi-user auth model.
- **Not RBAC-aware.** kweblens acts with its own service-account or kubeconfig credentials, not
  yours, and does not yet run `SelfSubjectAccessReview` — so the UI can offer an action that the
  underlying credentials are allowed to perform even if *you* personally should not be, and can
  offer actions that then fail with 403.
- **No server-side pagination**, so very large clusters will be slow to list.

For anything shared or production-facing: set `open-mode=false`, put a real identity provider
and TLS in front, and scope the RBAC role tightly. Treat the current model as
single-trusted-operator.

## License

Libraries (`kweblens-core`, `kweblens-cli`) are Apache-2.0; the server (`kweblens-web`) is
AGPL-3.0. See `LICENSE-APACHE-2.0.txt` / `LICENSE-AGPL-3.0.txt`.

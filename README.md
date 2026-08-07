# kweblens

A **web-based Kubernetes IDE** — [Freelens](https://freelens.app)/Lens reimagined as a
self-hosted Spring Boot server plus a Vue SPA, instead of an Electron desktop app. Point it at
your clusters, browse and edit their resources from any browser, stream logs, exec into pods,
manage Helm releases — and expose the same cluster view to AI assistants over MCP.

Because it runs as a server rather than on your laptop, the browser needs no kubeconfig, no
`kubectl` and no local install.

> **Status: 0.1.0-SNAPSHOT — working, not yet production-ready.** The surfaces below are
> implemented and used daily against real clusters.
>
> **kweblens is built for one trusted operator, not for a team.** There is a single shared
> admin account, no OIDC or per-user identity, and no RBAC-awareness — kweblens acts with its
> own credentials, not yours. That is a deliberate, signed-off position
> ([ADR-001](docs/design/adr-001-identity-model.md), accepted 2026-07-30), not a gap awaiting a
> fix, and it has real consequences: actions cannot be attributed to a person, and anyone who
> can log in can do everything the underlying credentials can. Read [Security](#security)
> before deploying this anywhere other people can reach.

## What works today

| Area | Detail |
|---|---|
| **Resource browsing** | 39 built-in kinds across 7 static categories (Cluster, Workloads, Config, Network, Storage, Access Control, Custom Resources), plus **Custom Resources discovered from the cluster's CRDs**, rendered with the CRD's own printer columns; a **Gateway** category is promoted at runtime when the Gateway API CRDs are present |
| **Live updates** | Kubernetes watches streamed to the browser over SSE, batched per animation frame so a large-list event burst can't freeze the tab |
| **Overviews** | A cluster overview plus Workloads / Network / Storage / Config category overviews — stat cards, the objects needing attention named rather than only counted, click-through to the filtered list, namespace-scoped |
| **YAML editing** | CodeMirror 6 with completion and validation driven by **the cluster's own OpenAPI v3 schema**, plus Form (generated from the schema), Warnings and Review-Changes (diff) tabs, then apply or JSON-merge patch |
| **Detail drawer** | Per-kind detail from a server-side endpoint, plus **twelve relation joins** resolved server-side and shown as sections where they apply: what owns an object and what it owns (`ownedBy`, a Deployment's ReplicaSets), a Service's endpoints, the pods a Service selects and the Ingresses that route to it, which pods mount a ConfigMap or Secret, a PVC↔PV binding both ways, a pod's ServiceAccount and the RoleBindings that grant it, and the HPAs and PodDisruptionBudgets covering a workload. Three of them (endpoints, selected pods, mounted-by) get kind-specific columns; the rest render as name/kind/namespace tables |
| **Logs** | Multi-source streaming over SSE: every container of a pod, or every pod behind a workload, in one stream — new pods created by a rollout join a stream already in flight |
| **Terminal** | Exec into a container over WebSocket, in a multi-tab dockable pane that can be popped out to a floating window |
| **Port-forward** | Started and managed from the browser |
| **Helm** | Releases, history, values, rendered resources, install, upgrade, rollback and uninstall — via [jhelm](https://github.com/alexmond/jhelm), no `helm` binary; mutating actions take a real `dryRun` |
| **Metrics** | Node and pod CPU/memory from metrics-server, with optional Prometheus/VictoriaMetrics (discovered, or configured explicitly) for node CPU, memory and disk graphs |
| **Health & diagnostics** | Deterministic workload / network / storage / config health checks shared by the dashboard, the `diagnose` pass and the MCP tools. An LLM summary can be added on top, but only when you press *Analyse* — reading the diagnosis never calls a model, and a summary is cached (in memory, per process) against the exact findings it was written about |
| **Remediation** | Four proposed fixes — `restart-pod`, `rollout-restart`, `rollback`, `scale-up` — each offered only when a precondition says it can actually work, and applied only with explicit confirmation |
| **Cluster management** | Add, edit and remove clusters at runtime from a dedicated Clusters page, not just from config |
| **Command palette** | `Ctrl`/`⌘`-`K` to switch cluster or jump to a kind |
| **Node detail** | Overview, the pods scheduled on the node, and its metrics |
| **MCP server** | In-jar MCP (SSE over WebMVC) exposing **15 read-only tools** to AI assistants — see [MCP server](#mcp-server) |
| **Audit** | Mutating actions recorded and queryable at `/api/v1/audit` |

Write actions are **suggest → confirm → apply**: something to look at first, an explicit
confirmation, then an audit entry. Nothing mutates the cluster silently. Be precise about what
"something to look at first" means today, because it differs by surface:

- **Helm** install/upgrade/rollback take a genuine `dryRun` — jhelm renders the release without
  persisting it.
- **YAML apply** shows *two* diffs in its Review-Changes tab: your edit against what was loaded,
  and — from a real `POST …/apply/dry-run`, which the server issues as
  `dryRun().forceConflicts().serverSideApply()` — the live object against what the cluster says
  it would actually store. So defaulting, another manager's fields and an admission-webhook
  refusal all surface *before* the write. A refusal is rendered as a result, not an error.
- **Remediation** previews are a real server round-trip for the two patch-shaped actions:
  `scale-up` and `rollout-restart` go through `dryRun=All`. `restart-pod` and `rollback`
  **cannot** — a DELETE and a revision lookup are not patches — so they return an explicit
  "not checked" naming that reason rather than prose that reads like a server answer.
- **Audit is durable, and the API view is not.** Every entry is written to a dedicated
  `kweblens.audit` logger (route it to a file or shipper like any other log category) *as well
  as* an in-memory ring of the newest 500, which is only the live view behind
  `/api/v1/audit` and is what resets on restart. Entries record the action, not a person —
  there is no per-user identity to record.

What none of that covers: the `apply` that actually writes is still
`forceConflicts().serverSideApply()` with no `dryRun`, because that *is* the write. The preview
is a separate request an operator can choose to run; nothing forces them to look at it first.
See [the roadmap](docs/design/roadmap.md) for what is next.

## Stack

- **Spring Boot 4.0.6 / Java 21**, multi-module Maven (`org.alexmond:kweblens-parent`)
- **fabric8 Kubernetes client** for all cluster access (BOM-pinned)
- **Vue 3 + Vite + TypeScript** SPA (Naive UI, dark theme), CodeMirror 6, ECharts, xterm.js —
  built into the jar and served at `/`
- **Spring AI MCP server** (SSE over WebMVC); optional Anthropic chat client for diagnostics
- Actuator + Micrometer/Prometheus; spring-javaformat / Checkstyle / PMD / JaCoCo gates

## Modules

| Module | What it is | Publishes to |
|---|---|---|
| `kweblens-core` | Cluster registry, kubeconfig loading, resource/log/exec/metrics/schema access | Maven Central |
| `kweblens-cli`  | Dependency-light cluster inspector (picocli) | Maven Central |
| `kweblens-ui`   | The Vue SPA (bundled into `kweblens-web`) | — |
| `kweblens-web`  | The runnable app (REST API + SPA + MCP) | container image |
| `kweblens-it`   | On-demand operational/connectivity tasks (tag `it`) | — |

> **No release has been cut yet.** Nothing is on Maven Central and no image is published, so
> today the only way to get kweblens is to build it — see [Build & run](#build--run) and
> [Container image](#container-image). The column above is where each module *will* publish.

## Build & run

```bash
scripts/dev-verify.sh    # format + full reactor verify (CI parity)
scripts/dev-run.sh       # run on http://localhost:8080 with a known login (admin/admin)
```

Start it with `dev-run.sh` rather than `java -jar` — with no admin password configured a
random one is generated per run and only written to the log. See
[`scripts/README.md`](scripts/README.md) for the rest.

kweblens seeds your **ambient kubeconfig** (`KUBECONFIG` / `~/.kube/config`) on startup, and
**every context in it becomes its own cluster, with the context name as the cluster id** — so a
laptop kubeconfig with six contexts registers six clusters. The id `default` is the *fallback*:
it is used when there is no readable kubeconfig at all (an in-cluster ServiceAccount, most
obviously), when the file declares no contexts, or when it cannot be parsed. Building a fabric8
client does not connect, so extra contexts cost a map entry and a row in the cluster rail, not
a connection. `GET /api/v1/clusters` is the authority on which ids exist.
Set `KWEBLENS_LOAD_KUBECONFIG=false` to start with none, or configure
clusters explicitly under `kweblens.clusters[*]`. Clusters can also be added, edited and
removed **at runtime** (`POST/PUT/DELETE /api/v1/clusters`, admin login required); the
kubeconfig is kept in a Kubernetes Secret in-cluster and in a data directory otherwise —
see [docs/deployment.md](docs/deployment.md#adding-clusters-at-runtime).

### No cluster? Use the built-in simulator

A generated in-JVM cluster, handy for development, CI and performance work:

```bash
scripts/dev-run.sh --sim   # or, by hand:
KWEBLENS_SIMULATOR_ENABLED=true KWEBLENS_LOAD_KUBECONFIG=false \
KWEBLENS_SIMULATOR_SIZE=200 ./mvnw -pl kweblens-web -am spring-boot:run
```

## API

Read endpoints are `GET /api/v1/clusters/...`; the shapes are stable enough to script against
but not yet versioned beyond `v1`.

| Path | Returns |
|---|---|
| `/api/v1/clusters` | connected clusters; `POST` / `PUT /{id}` / `DELETE /{id}` add, edit and remove one (admin login required) |
| `/api/v1/clusters/{id}/nav` | the navigation catalog (categories → kinds), including discovered CRDs |
| `/api/v1/clusters/{id}/resources/{resourceId}/objects` | full objects of a kind |
| `/api/v1/clusters/{id}/resources/{resourceId}/objects/watch` | the same as a live SSE stream |
| `/api/v1/clusters/{id}/resources/{resourceId}/columns` | CRD-declared printer columns |
| `/api/v1/clusters/{id}/counts` | object counts per kind (drives the nav badges and overviews) |
| `/api/v1/clusters/{id}/overview/{category}` | an overview page's stats and findings |
| `/api/v1/clusters/{id}/detail/{resourceId}/{ns}/{name}` | per-kind detail plus relation sections |
| `/api/v1/clusters/{id}/events` | Kubernetes events, newest first |
| `/api/v1/clusters/{id}/pods/{ns}/{pod}/log/stream` | pod log stream (SSE) |
| `/api/v1/clusters/{id}/schema` | OpenAPI v3 schema for a kind (drives editor validation) |
| `/api/v1/clusters/{id}/yaml` · `/apply` · `/patch` | read / apply / merge-patch an object |
| `/api/v1/clusters/{id}/diagnose` | diagnostics findings, plus any cached LLM summary — never calls a model |
| `/api/v1/clusters/{id}/diagnose/summary` (POST) | runs the LLM analysis on demand and caches it (in-memory, per-process) |
| `/api/v1/clusters/{id}/remediations` · `/apply` | proposed fixes, and applying an approved one |
| `/api/v1/audit` | live view of the audit trail (in-memory, newest 500); the durable copy is the `kweblens.audit` log category |

Helm, metrics, multi-source logs, port-forward, node and pod-file endpoints live under the same
`/api/v1` prefix. `/actuator/{health,info,metrics,prometheus}` is exposed.

## MCP server

`kweblens-web` runs an MCP server in the same jar — SSE over WebMVC, so `GET /sse` opens the
stream and messages post back to the endpoint it hands out. It exposes **15 read-only tools**,
in three groups:

| Group | Tools |
|---|---|
| Orientation (`ClusterTools`) | `listClusters`, `listNamespaces`, `listPods`, `listResourceKinds` |
| Evidence (`DiagnosticTools`) | `describeResource`, `listResources`, `getEvents`, `getPodLogs` |
| Verdicts (`HealthTools`) | `checkWorkloadHealth`, `checkNetworkHealth`, `checkStorageHealth`, `checkConfigUsage`, `getPodUsage`, `getNodeUsage`, `listHelmReleases` |

Nothing on the tool surface writes. Output is redacted at the tool boundary: Secret `data` /
`stringData` values and the `last-applied-configuration` annotation are replaced (keys kept),
and `managedFields` is dropped — because tool output goes to a model and off the machine, which
is a different rule from what the dashboard shows a logged-in operator.

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

Some path families are **always authenticated, even in open-mode**, because what they return is
itself a secret: pod exec, Helm release/library values, and the pod file browser.

### Pod file browser — off by default

`kweblens.files.enabled` (default **false**) turns on browsing, viewing, editing and deleting
files inside a container, over the same Kubernetes exec API the terminal uses. It ships off
deliberately: it can read a projected Secret volume or the service-account token straight off
disk, which no other kweblens view does, so an operator has to choose it. See
[ADR-001](docs/design/adr-001-identity-model.md).

The UI is the pod detail drawer's **Files** tab, over the HTTP slice
(`/api/v1/clusters/{id}/pods/{ns}/{pod}/files`). Files can be dragged onto the pane to upload
one; a dropped folder, or several files at once, is refused with an explanation and nothing is
sent.

When it is on:

- **every endpoint requires the admin login**, reads included — a file read is never a public GET;
- every content read, download, write and delete is **audited with its path**;
- `kweblens.files.writable=false` makes it browse-and-download only;
- `kweblens.files.allowed-roots` confines it to given path prefixes. The check runs twice: on
  the requested path, and again on the path the container itself resolves (`readlink -f`), so a
  symlink **inside** a root that points outside it is refused (`403 path-outside-roots`) — and a
  path the container cannot resolve at all is refused too (`403 unresolvable-path`), i.e. it
  fails closed. Both are verified against a live container, not only against mocks. `/about`
  reports the roots so the Files tab opens inside one instead of on `/`;
- reads and writes are capped (`max-read-bytes` / `max-write-bytes`, 1 MiB each) and oversized
  files are **refused** rather than silently truncated;
- containers without a shell (distroless, scratch) report that plainly instead of an empty tree.

### The identity model, and what follows from it

**kweblens runs as one shared identity, on purpose.** [ADR-001](docs/design/adr-001-identity-model.md)
was accepted on 2026-07-30: multi-tenancy is not a goal, authentication exists to stop drive-by
writes rather than to separate people, and no OIDC or header-trust integration is being built
while that holds. When identity does eventually arrive, Kubernetes impersonation is the
sanctioned mechanism.

Being a decided position does not make the consequences go away, so state them:

- **No per-user identity.** One shared admin is not a multi-user auth model. Everyone who logs
  in is the same principal.
- **Not RBAC-aware.** kweblens acts with its own service-account or kubeconfig credentials, not
  yours, and does not run `SelfSubjectAccessReview` — so it will happily offer an action that
  the underlying credentials are allowed to perform even if *you* personally should not, and
  offer actions those credentials cannot perform, which then fail with 403.
- **Audit entries name an action, not a person.** The record itself is durable — it is written
  to the `kweblens.audit` log category — but it can never be attributed to a human. The
  `/api/v1/audit` view is the newest 500 held in memory and resets with the process.
- **No server-side pagination**, so very large clusters will be slow to list.

The ADR's own revisit triggers: a second person needing their own view of a cluster, exposure
beyond a trusted network, or a need to attribute an action to a human. Until then, for anything
shared or production-facing: set `open-mode=false`, put a real identity provider and TLS in
front, and scope the RBAC role tightly.

## License

Libraries (`kweblens-core`, `kweblens-cli`) are Apache-2.0; the server (`kweblens-web`) is
AGPL-3.0. See `LICENSE-APACHE-2.0.txt` / `LICENSE-AGPL-3.0.txt`.

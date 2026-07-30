# Detail-drawer sections and visual-editing scope — audit (#24)

Kind-by-kind audit of the detail drawer's Overview tab and the Form (visual) editor: what
each should show, in what order, and what the Form tab should be allowed to edit.

Status: **Part 1 largely implemented** (see "Implemented" below). **Part 2 is a decision, not
yet built.**

## The architectural finding that shapes everything else

`OverviewSection.body` has the signature `(o: KubeObject) => OvBody`. Every section is a
**pure function of one object**. That is not an incidental detail — it partitions the
ticket's wish-list into two groups with very different costs:

**Group A — self-contained.** Computable from the object alone. Cheap: one registry entry,
no new plumbing, no new requests. Everything implemented below is Group A.

**Group B — relational.** Needs a *second* object, so it cannot be expressed in the registry
at all today:

| Wanted | Needs |
|---|---|
| Service → Endpoints/EndpointSlice, ready vs not-ready backends | EndpointSlice list for the service |
| Selector → matching pods (workloads, Service) | pod list + selector match |
| "Which workloads mount this Secret/ConfigMap" | pod list scan over volumes + envFrom |
| HPA targeting this workload | HPA list filtered by scaleTargetRef |
| Ingress TLS secret **expiry** | the Secret, plus certificate parsing |
| Requests/limits vs **actual** usage | metrics-server (already have `/metrics/pods`) |
| "Who references this object" back-links | a reverse index over several kinds |
| Rollout **history** (revisions) | ReplicaSet list owned by the Deployment |

Group B needs one of:

1. **An async section capability** — sections gain an optional `load(o) => Promise<extra>` and
   the drawer renders a per-section loading/error state. Smallest change; keeps everything
   client-side; means N extra requests per drawer open.
2. **A server-side per-kind detail endpoint** — `GET /…/detail/{kind}/{ns}/{name}` returning
   the object *plus* its resolved relations. One request, server does the joins, and the same
   payload serves the TUI (#32) and the agent (#31). This is where #35's server-side direction
   actually pays for itself.

**Recommendation: (2), and treat it as the thing that motivates #35** rather than doing #35
as an abstract refactor. Do not build Group B client-side first — it would be written twice.
Until then the drawer should not pretend to answer relational questions.

## Ordering and collapse policy (applied)

Sections render in registry order. The rule adopted: **most-diagnostic first, identity last.**

1. **What is it doing** — Containers, Container Status
2. **Why** — Resources, Probes (open by default: the usual answer to an OOMKill, a throttle,
   or Running-but-not-Ready)
3. **Kind-specific shape** — Rollout, Schedule & Runs, Storage, RBAC Rules, Subjects, Ports,
   Selector, Rules, Data
4. **Bulk / reference** — Environment, Volume Mounts, Volumes, Node Selector, Tolerations
   (collapsed: long, and usually consulted deliberately rather than scanned)
5. **Identity** — Labels, Annotations (collapsed), Conditions

Collapsed-by-default is for sections that are *long and situational*, never for sections that
are *diagnostic*. Annotations in particular are collapsed because a Helm-managed object's
annotations can be enormous.

## Implemented (this pass)

**The pod-template fix — the single biggest gap found.** Every container/volume/scheduling
section read `spec.*` directly, which is only correct for a **Pod**. A Deployment's containers
live at `spec.template.spec.containers`, so those sections silently matched nothing: the
drawer was effectively **Pod-and-Node-only**, and Deployments, StatefulSets, DaemonSets,
ReplicaSets, Jobs and CronJobs showed almost nothing. `ovPodSpec()` now resolves the pod
template (CronJob nests one level deeper via `jobTemplate`), so ~8 existing sections light up
across 6 more kinds without duplicating any of them.

New Group-A sections and fields:

| Section / field | Kinds | Why it earns its place |
|---|---|---|
| **Resources** | anything with a pod spec | Requests vs limits side by side — the gap is the point. First check for an OOMKill (limit too low), CPU throttling, or a Pending pod (requests unschedulable) |
| **Probes** | anything with a pod spec | Target *and* thresholds. A failing readiness probe is why a pod is Running but not Ready; an over-aggressive liveness probe is a restart loop that looks like an app crash |
| **Volume Mounts** | anything with a pod spec | Mounts joined to the volume they resolve to — turns `/etc/config` into "the app-config ConfigMap" |
| **Rollout** | Deployment, StatefulSet, DaemonSet, ReplicaSet | Strategy + replica breakdown: "is this rollout finished, and if not, why". DaemonSet's own vocabulary included |
| **Schedule & Runs** | CronJob, Job | Schedule, suspension, concurrency, last/next run, completions, failures |
| **Storage** | PVC, PV | Phase, class, access modes, requested vs actual capacity, and the binding in both directions |
| **RBAC Rules** | Role, ClusterRole | `rules` is **top-level**, not under `spec` — which is why the existing Ingress-shaped "Rules" section never matched RBAC and that detail was blank |
| **Subjects** | RoleBinding, ClusterRoleBinding | Leads with the role being granted, then who it is granted to |
| *fields:* External, Session Affinity | Service | Where a LoadBalancer actually answers (empty while provisioning — itself the answer to "why can't I reach it") |
| *fields:* Restart Policy, Service Account, Priority Class | pod-spec kinds | Service Account links through to the object |

## Remaining Part 1 (not implemented)

Group B, per the table above — plus these Group-A leftovers, deliberately deferred as
lower-value:

- **Pod**: image pull policy + pull secrets as their own rows (image is already in
  Containers); scheduling detail beyond nodeSelector (affinity/anti-affinity/topology spread
  — verbose and rarely the first question).
- **Ingress**: rules render today; **TLS secret expiry** is Group B.
- **Secret**: per-key reveal exists; "which workloads mount it" is Group B.
- **Custom resources**: fall back to the CRD's printer columns / schema. Worth doing, but it
  is really "generate a detail view from a JSON Schema", which is the same machinery Part 2
  wants — so they should be designed together.

## Part 2 — visual editing scope (decision, not yet built)

Today `FormFields.vue` edits exactly three things: `metadata.labels`,
`metadata.annotations`, and ConfigMap/Secret `data`. Everything else is raw YAML.

**Recommendation: do NOT hand-write a form per field. Generate the form from the JSON
Schema we already serve** (`/api/v1/clusters/{id}/schema`, already powering editor
completion and validation). Hand-written forms mean a per-kind UI to maintain, drifting from
the cluster's real schema — exactly the duplication #35 exists to prevent. A generated form
is one implementation that covers CRDs for free, which hand-written forms never will.

Scope the *generated* form by an explicit allowlist of paths, because "editable" is a safety
judgement the schema cannot make:

**Safe as a form** — small, bounded, low blast radius, easy to reason about:
`spec.replicas` · container `image` · container `resources.requests/limits` · `env` literals ·
CronJob `schedule`/`suspend` · Service `type`/`ports` · `nodeSelector` · probe thresholds
(not probe *shape*).

**YAML-only, deliberately** — structural or dangerous to half-edit: affinity /
topologySpreadConstraints (nested boolean logic a form flattens into a lie) · volumes and
volumeMounts (a rename must change both, so a form that edits one is a footgun) ·
initContainers ordering · `spec.selector` on a Deployment (**immutable** after create — a
form offering it is a promise the API server will refuse) · anything under `status`.

**Never editable via the form**: `env` values sourced from a Secret. The Environment section
deliberately shows them as a *reference*, never the value; a form field would have to fetch
and display the secret to round-trip it, which would undo that decision.

Two mechanics to settle before building:

- **Round-trip.** `FormFields` writes back only the section it touched. A generated form must
  patch **only the paths the user actually changed** (a JSON-merge patch of the diff, which
  the `/patch` endpoint already speaks), never re-serialise the whole object — otherwise it
  will silently drop fields it does not know about, including other controllers' additions.
- **Immutability.** Fields that are immutable after creation should render read-only with the
  reason, rather than being offered and then rejected.

## Follow-ups worth their own tickets

1. **Per-kind detail endpoint** (Group B enabler) — the concrete, motivated version of #35.
2. **Schema-generated Form tab** with the allowlist above — Part 2's implementation.
3. **CR detail from CRD schema/printer columns** — bundle with (2); same machinery.

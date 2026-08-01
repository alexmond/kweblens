# Roadmap: thesis, ranked gaps, and the cut plan

Issue: GH#147. Date: 2026-07-31. Verified against the code at `8668b44`, not against the
older docs.

This is the synthesis GH#147 asked for. It does **not** restate the research — read
[`competitor-analysis.md`](../competitive-review/competitor-analysis.md) for the landscape,
[`adr-001-identity-model.md`](adr-001-identity-model.md) for the identity decision, and the
per-area design notes for the detail. This document does three things the research
deliberately did not: it says what kweblens is *for*, it **re-ranks** the research's gap list
against a decision taken after it was written, and it cuts a sequence with explicit
non-goals.

---

## 1. The thesis

**kweblens is the Kubernetes IDE for one trusted operator who wants it in a browser *and* in
their coding agent.** One JVM process holds one cluster-access layer and puts two front-ends
on it: a Freelens-grade web UI for the human, and an MCP tool surface for the agent the
operator already runs. It is self-hosted, needs no kubeconfig on the client, no desktop
install and no account, and it treats writes as suggest → preview → confirm → audit. It is
explicitly **not** a fleet platform and **not** multi-tenant: ADR-001 settled that
authentication exists to stop drive-by writes, not to separate people.

Who it is for: the platform engineer or homelab-to-small-team operator who administers a
handful of clusters, especially in a JVM shop where Actuator/Micrometer/`application.yml`/
Spring Security are the operating vocabulary. Who it is **not** for, and we should say so:
a team that needs per-user RBAC (Headlamp), a fleet operator (Rancher), or anyone buying
SaaS observability (Komodor/Datadog).

The defensible ground, per the review and unchanged by it: **IDE depth** — schema-driven
YAML from the cluster's own OpenAPI, a schema-generated form editor, diff-before-apply, the
tabbed exec/log dock, in-browser port-forward, jhelm — plus **one access layer, two
surfaces**. Not MCP (table stakes since H1 2026) and not the apiserver-proxy metrics trick
(Lens has always done it).

## 2. What ADR-001 changed, and why the research's ranking cannot be inherited

The competitive review ranked the disqualifiers: **identity → RBAC-awareness → pagination →
server-side dry-run → per-kind depth → diff quality → extensibility → keyboard**.

ADR-001 was signed off *after* that review with three answers: multi-tenancy is **not** a
goal, identity sources are **deferred**, impersonation is sanctioned **when** identity
arrives. That deletes #1 from the plan and demotes #2:

- **#1 identity is not a gap; it is a position.** The README still calls it "the gap that
  matters", which now contradicts an accepted ADR. Fix the words, not the code.
- **#2 RBAC-awareness collapses from an enforcement problem to a small UX one.** With one
  shared credential there is no user whose permissions we could reflect. `SelfSubjectAccess
  Review` would only answer "can *kweblens's own service account* do this" — genuinely useful
  when the deployment is given a read-only role and the UI still offers Delete, and worth a
  ticket eventually, but it is polish, not the second-biggest thing wrong with the product.

Everything below is therefore a **re-derived** ranking for a single-operator product, not the
review's ranking with two rows struck out.

## 3. Current state, verified

Verified by reading the code at `8668b44`. The recent shipping rate is high enough that any
plan built on a document older than a week is wrong.

**Shipped since the competitive review was written (do not re-propose these):**

| Review said we lacked | Actually now | Evidence |
|---|---|---|
| MCP tool breadth: "3 read tools vs Radar's 28" | **15 read tools**, secret-redacted at the tool boundary | `web/mcp/{ClusterTools,DiagnosticTools,HealthTools}.java`, `ToolRedaction.java` |
| "Keyboard: `Escape` only" | Command palette on **Ctrl/⌘-K** (switch cluster, jump to kind) | `kweblens-ui/src/commandPalette.ts` |
| "AI analyzer coverage: pods + events only" | Workload / network / storage / config health, each reason-carrying, **shared with the dashboard** | `kweblens-core/.../health/` |
| Remediation "has one action (`restart-pod`)" | 4, each gated on a precondition that says when it *cannot* work | `web/ai/RemediationService.java` |
| "No relationship view … owner-ref child rows and nothing else" | Server-side detail endpoint with relation sections (endpoints / selected pods / mounted-by) | `web/api/DetailApiController.java`, `resource/RelationService.java` |
| "Overview / home screens" thin | Cluster + **Workloads / Network / Storage / Config** overviews, click-through, namespace-scoped, truncation stated | `components/CategoryOverview.vue`, `overviewCategories.ts` |
| Cluster management is config-file only | Add / edit / remove clusters at runtime, plus a Clusters page | `web/api/ClusterConfigApiController.java`, `components/ClustersPage.vue` |
| Form editor | Schema-driven from the cluster's JSON Schema, over a curated path allowlist (9 kinds) | `kweblens-ui/src/schemaForm.ts` |
| Table UX | Per-kind columns (**28 kinds**, 84 renderers), column-visibility toggle persisted per kind, natural sort | `columns.ts`, `ResourceListView.vue`, `prefs.ts` |
| Nav coverage | 39 built-in kinds / 7 static categories, **plus** a Gateway API category promoted at runtime when the CRDs exist | `web/nav/NavCatalog.java`, `ClusterNavService.java` |
| Metrics | metrics-server + Prometheus-compatible via apiserver proxy, **explicit config overriding discovery** | `metric/PrometheusMetricService.java`, `MetricsProperties.java` |
| Helm | install / upgrade / rollback / uninstall / history / values library / repo refresh, each with a real `dryRun` | `web/helm/HelmService.java`, `web/api/HelmActionApiController.java` |
| Logs | multi-source (container / pod / **workload**), colour-gutter legend, rAF-batched | `composables/useMultiLogs.ts` |
| Dock | multi-tab, resizable, **pop-out to a floating window without dropping the socket** | `DockArea.vue`, `FloatingFrame.vue` |

**Still exactly as the review described:**

- No server-side pagination anywhere. `ResourceService.list*` is
  `inAnyNamespace().list().getItems()` — no `ListOptions`, no `limit`, no continue token.
- No `SelfSubjectAccessReview` / `SelfSubjectRulesReview` anywhere (Java, TS or Vue).
- No plugin API, no topology graph, no per-user identity.

**Two things the docs get wrong about the code — worth correcting before anything is built
on them:**

1. ~~The MCP transport is streamable HTTP at `/mcp`, not SSE.~~ **Retracted — the docs are
   right and this claim was wrong.** It was inferred from `application.yml` setting no
   `spring.ai.mcp.server.protocol`; probing the running server settles it instead. `GET /sse`
   holds a stream open and emits the classic SSE handshake:

   ```
   event:endpoint
   data:/mcp/message?sessionId=9eeda7e5-…
   ```

   So the transport is SSE over WebMVC at `/sse`, messages POST to `/mcp/message`, and the
   dependency is `spring-ai-starter-mcp-server-webmvc`. `POST /mcp` 404s. This also explains
   why the CSRF exemption matters: `ignoringRequestMatchers("/api/**", "/mcp/**")` covers
   `/mcp/message`, which is what makes MCP callable at all.
2. **The remediation "dry-run preview" is a hand-written English sentence, not a dry-run.**
   `RemediationService` builds strings like `"dry-run: pod 'x' would be deleted…"` and the
   record's javadoc calls the field "a dry-run preview of the change". Nothing is sent to the
   API server. The competitive review banks this as a verified advantage over Headlamp
   ("propose → dry-run preview → explicit confirm → audited"); two of those four links are
   weaker than advertised.
3. **The audit log does not survive a restart.** `AuditService` is a bounded 500-entry
   in-memory deque. "Always audited" is true only within one process lifetime.

## 4. The remaining gaps, ranked

Ranked by *severity for a single operator*, which is not the same as the order to build them
(§5). Split into table stakes we lack and differentiators we could win on.

### Table stakes we lack

**T1 — The write path cannot be previewed against the cluster, and its audit is volatile.**
`apply` is server-side apply with `forceConflicts()` — a deliberate choice, but it means a
field owned by another manager is silently overwritten, and there is no way to ask the API
server what the object *would* become. Consequently the Review Changes tab diffs "my edit vs
what I loaded", never "live vs what the cluster would accept": it cannot show defaulting, it
cannot show another controller's fields, and it cannot catch an admission-webhook rejection
before the write lands. `dryRun=All` on SSA returns exactly the merged object needed to close
all three, and fabric8 supports it. This is ranked first because it is the one place where a
stated differentiator is partly narrative, because it is small, and because everything
write-shaped downstream inherits it.

**T2 — Nothing in the list path is bounded.** Every list is a full LIST into the JVM heap and
then to the browser; `/counts` is `listRaw().size()`. This is k9s
[#4109](https://github.com/derailed/k9s/issues/4109) waiting to happen, and its failure mode
is a spinner that never resolves rather than an error. Compounding it: watches are opened
**per SSE connection**, so N browser tabs × N open list views = N apiserver watches with no
sharing. Ranked second rather than first only because its urgency is a function of cluster
size, which we have not measured (§7).

**T3 — Finding a specific object is weak.** Search is a plain substring over name / namespace
/ kind (`shell.ts:filterObjects`); there is no label selector, no field filter, no regex, and
the palette indexes clusters and nav leaves but **not objects**. For an operator the most
frequent action in the product is "find the thing", and k9s sets the bar with `/regex`,
`/!regex`, `/-l selector`.

**T4 — Error and empty states are inconsistent.** `ErrorNotice` (message + Retry) is used in
6 files, all Helm/Clusters; 11 other sites render a bare `<div class="error">` with no retry,
and there are 10 ad-hoc empty divs with no shared component. This is the difference between a
product and a scaffold, and Octant's convention is the fix: make the empty-state string a
required constructor argument so no list can ship without one.

**T5 — RBAC-awareness, in its reduced form.** Not "reflect the user's permissions" (there is
no user) but "stop offering actions this deployment's service account cannot perform".
Relevant precisely because the deployment guidance is a read-only role to start.

### Differentiators we could win on

**D1 — Relations breadth, which is IDE depth *and* agent quality at once.** `RelationService`
resolves three relations. The [detail-sections audit](detail-sections-audit.md) lists roughly
eight more that the drawer wants and cannot express client-side: rollout history via owned
ReplicaSets, HPA targeting a workload, Ingress → TLS secret and its expiry, workload → PVC,
requests vs actual usage, reverse "who references this". Each one lands in the SPA drawer,
`/diagnose` and `describeResource` from one implementation — this is what "one access layer,
N front-ends" is supposed to buy, and it is the only item on this list where a day's work
improves three surfaces.

**D2 — The agent-attach story.** The review's sharpest strategic observation is that the
category moved from "chatbot in the sidebar" to "attach the coding agent the user already
has" (Lens Ask AI, Freelens's Claude extension, K8Studio). kweblens is unusually well placed:
its MCP server is free, in-process, always-on and reachable over HTTP, where Headlamp's is
client-only/desktop-only/stdio-only and Lens's is $25/user/month and needs the desktop app
running. What is missing is almost entirely presentation: a documented copy-paste attach
flow, correct transport documentation, and a generated cluster-context surface. Cheapest
differentiator on the list.

**D3 — Analyzer breadth over the shared health services.** The health checks span workloads,
network, storage and config; k8sgpt also covers security/RBAC, and Kubevious's genuinely
original idea — **cross-manifest** assertions (validate a Deployment against the Service,
ConfigMap and ServiceAccount it references) — is exactly what `RelationService` now makes
cheap. Ranked below D1 because it depends on D1's joins.

**D4 — Guarded MCP write tools.** Every 2026 entrant ships them; Radar's scoping
(destructive-annotated, no delete tool, no shell) is the precedent. **Gated on T1** — see §5.

## 5. The cut plan

Severity (§4) is not build order. Build order is leverage: do the cheap thing that unblocks
an epic before the expensive thing that unblocks a module.

### First: T1 — write-path integrity

One slice: `dryRun=All` on `apply` and `patch`; the same for the remediation actions, so the
`preview` field carries a real server response instead of a sentence; surface it as the
Review Changes tab's second diff (**live → would-be**, alongside today's edited-vs-loaded);
and make the audit log durable.

Why first: it is days, not weeks. It repairs a claim already made in the README and in the
competitive review, which is a different kind of debt from a missing feature. And it is the
**blocker for D4** — shipping write-capable agent tools on a guardrail whose dry-run is prose
and whose audit dies with the pod is how a safety story becomes an advisory. Also unblocks
any further remediation action, since each one inherits the same chain.

**Blocks:** GH#142's write half, all future remediation actions.

### Second: T2 — bounded lists, designed with filtering and with the watch topology

`limit` + continue tokens in `ResourceService`, a cap-with-load-more fallback in the table, a
genuinely cheap `/counts` via `limit=1` + `metadata.remainingItemCount`, and a decision on
watch fan-out (share one watch per cluster+kind across subscribers, or accept N and document
the ceiling).

Why second and not first: it is the largest item here and the only one that changes a
contract three future consumers read. Why not later: **it must precede GH#143 (TUI) and
GH#148 (server-side columns)**, because both are new clients of the list contract, and
retrofitting paging into three clients costs three times what designing it into one does.
GH#143 already states it should not start before the per-kind projection; the same argument
applies with more force to paging.

**The sequencing constraint that matters most in this document:** paging and filtering are
one piece of work, not two. A substring filter applied to a truncated page is a lie — it
reports "no matches" for an object that exists. So server-side label/field selectors land
with the continue token, or neither does.

**Blocks:** GH#143, GH#148, and T3.

### Third: T3 — find anything

Object search in the palette (⌘K finds a pod by name across kinds, not just a nav leaf), plus
a real filter syntax on the list header — regex, negation, label selector — evaluated
server-side.

Why third: highest-frequency operator action, and it is the natural first consumer of T2's
server-side filter. Building it before T2 means writing a client-side filter that T2 deletes.

**Blocked by:** T2's filter contract.

### Then, in rough order

4. **D1 relations breadth** + the [missing server-side test on the detail endpoint](kind-catalog.md)
   — the endpoint's HTTP contract is currently unverified, and it is the thing D1, D3 and the
   TUI all build on.
5. **D2 agent-attach**: correct the transport docs, verify against the 2026-07-28 MCP spec
   final and Spring AI 2.0, ship an "attach Claude Code / Codex / Copilot CLI in three lines"
   page and a generated cluster-context surface.
6. **D3 analyzers**: security/RBAC checks and the first cross-manifest rule.
7. **T4 error/empty states**, then **T5 SSAR affordances**.
8. **D4 MCP write tools**, on Radar's scoping — only once (1) has landed.

## 6. What we are explicitly NOT doing, and why

Each of these is a live suggestion in the research. Saying no once, in writing, is the point
of this section.

| Not doing | Why |
|---|---|
| **OIDC / per-user identity / token pass-through** | ADR-001, signed off. Not a gap — a position. Re-open only on the ADR's own triggers (a second person, exposure beyond a trusted network, or a need to attribute an action to a human). |
| **SSAR as an authorization mechanism** | ADR-001 rejects it: it fails open. Only ever a UI affordance (T5). |
| **A plugin framework (GH#146)** | The [research](../research/plugin-framework.md) settled the mechanism and recommended not yet. The expensive part is the published API surface, and it would have to include Naive UI. The trigger is one concrete extension someone actually wants; enumerating hooks is not that. Keep the GH#148 seam clean and publish nothing. |
| **Radar-style simultaneous multi-cluster** | [cluster-selection.md](cluster-selection.md): every route, watch and registry entry addresses one cluster id. This is an access-layer rewrite wearing a sidebar's clothes. Worth its own decision, never smuggled in under "the rail does not scale". |
| **A topology / resource-map graph** | The most-mourned feature in the category, and still the wrong next move: the relation *tables* already answer the diagnostic questions ("which pods back this Service", "what mounts this Secret") and a graph mostly answers them more beautifully. Revisit when a question arrives that the tables cannot answer. |
| **Argo-grade 3-way diff with `ignoreDifferences` / jq paths** | Over-built for one operator hand-editing YAML. The *actual* diff gap is that we diff against what we loaded rather than against the server — which T1 fixes for a fraction of the cost. |
| **kweblens-tui (GH#143) now** | Not before T2 and GH#148. A TUI written against today's TypeScript catalog would reimplement 84 renderers, which is the exact drift the module exists to prevent. |
| **Pod file browser (GH#140) on by default** | ADR-001 is explicit: it reads mounted Secrets off disk under a shared credential. Build it if wanted; ship it off by default and say why. |
| **External / off-cluster Prometheus auth models** | [metrics-sources.md](metrics-sources.md): four auth models against zero users. The apiserver proxy covers every in-cluster backend. Build when a deployment actually has an external one. |
| **SSH / bastion tunnelling** | [proxy-competitive.md](proxy-competitive.md)'s explicit non-goal — it would mean storing users' SSH keys server-side. Document the sidecar / SOCKS5 pattern instead. |
| **Competing on extensibility, auth breadth, or a published scale record** | Headlamp has 40+ hooks and 30k pods; OpenShift has 81 extension types. We will not win these and should never claim to. The honest pitch stays "the IDE surface Headlamp gates behind plugins or desktop mode, in one jar". |
| **Positioning on MCP novelty or the apiserver-proxy metrics trick** | Both retracted by the review. Do not rebuild marketing on them. |

## 7. What is uncertain

Stated rather than smoothed over.

- **How urgent T2 actually is.** We have no external scale numbers and the simulator defaults
  to ~1,000 objects; nobody has run kweblens against a 15k-pod cluster. The failure mode is
  well evidenced *in other products*; our own threshold is unmeasured. Aptakube's KWOK rig
  (4 clusters × 500 nodes × 5,000 pods for ~$15/month) is the cheap way to find out, and
  doing that **before** committing to T2's size would be a reasonable first move.
- **Whether `metadata.remainingItemCount` is reachable through fabric8** — flagged unverified
  in [overview-pages.md](overview-pages.md), still unverified.
- **Whether the SSE transport conforms to the MCP spec final of 2026-07-28.** The handshake
  was observed against the running server, but a conformance run against the spec was not
  done — an `initialize` exchange completing is not the same as full conformance.
- **What `dryRun=All` returns through fabric8's server-side-apply path** — the plan assumes
  the merged object comes back and is diffable. Unverified; it is the first thing to check
  when starting T1.
- **Whether sharing one watch per cluster+kind is safe** given that watches are currently
  per-connection and per-identity concerns do not apply under ADR-001. Probably yes; not
  designed.
- Carried from the review: Radar's multi-cluster model (simultaneous vs switching) remains
  unverified, and it is the one competitor worth re-checking monthly.

## 8. Issues this implies

Existing issues are referenced, not duplicated. Open at time of writing: GH#140, GH#141,
GH#142, GH#143, GH#146, GH#147, GH#148.

| # | Proposed issue | Maps to | Priority |
|---|---|---|---|
| A | Write-path integrity: `dryRun=All` on apply/patch/remediate, a live→would-be diff, and a durable audit log | T1 — **new**; blocks GH#142's write half | P1 |
| B | Bounded lists: `limit`/continue + server-side label/field selectors + cheap `/counts` + a watch fan-out decision | T2 — **new epic**; blocks GH#143, GH#148 | P1 |
| C | Find anything: object search in ⌘K + filter syntax on list headers | T3 — **new**; depends on B | P2 |
| D | Detail endpoint: server-side contract tests, then widen `RelationService` (rollout history, HPA target, Ingress TLS + expiry, workload→PVC, reverse references) | D1 — **new**; the enabler GH#148 and GH#143 build on | P2 |
| E | Agent-attach: correct the MCP transport in all docs, verify against the MCP spec final, ship a copy-paste attach page + generated cluster context | D2 — folds into GH#142 | P2 |
| F | Analyzers: security/RBAC checks and the first cross-manifest rule over `RelationService` | D3 — folds into GH#142 | P2 |
| G | Shared `EmptyState`, `ErrorNotice` everywhere, empty-state copy a required prop | T4 — **new** | P3 |
| H | SSAR affordances: stop offering actions the deployment's service account cannot perform | T5 — **new** | P3 |
| I | Docs currency: README/CLAUDE.md say identity is "the gap that matters" (ADR-001 says otherwise) and that MCP exposes three read-only tools (it is 15). The MCP *transport* wording is correct — see §retraction above. | — **new chore** | P3 |

GH#141's remaining workstream (the cluster rail trim and the Clusters-page landing screen) is
unaffected by this plan and can proceed on
[cluster-selection.md](cluster-selection.md)'s recommendation. GH#146 and GH#148 stay parked
on their own stated triggers; GH#143 waits on B.

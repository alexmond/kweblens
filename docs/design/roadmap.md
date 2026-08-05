# Roadmap: thesis, what is true now, and what is left

Issue: GH#147. **Re-cut 2026-08-05**, verified against the code at `ffc5039` — not against the
previous cut of this document, which was written on 2026-07-31 and is superseded in most of its
load-bearing parts.

Read [`competitor-analysis.md`](../competitive-review/competitor-analysis.md) for the landscape
(note: it is a dated snapshot and still describes a 3-tool MCP server; it is 15) and
[`adr-001-identity-model.md`](adr-001-identity-model.md) for the identity decision. This
document says what kweblens is *for*, what is actually true of it today, and what is left.

**The short version.** T1 shipped, T2 was measured and answered in the negative, D1 shipped, and
GH#140 / GH#141 / GH#142 all closed. There is no large missing feature. The two things that
matter are a measured way to OOM-kill the process (GH#293) and the fact that **nothing has ever
been released**, so nobody can install what has been built. Everything else is polish. This is a
harden-and-ship phase and the plan below is short on purpose.

---

## 1. The thesis

Unchanged, and still the right one.

**kweblens is the Kubernetes IDE for one trusted operator who wants it in a browser *and* in
their coding agent.** One JVM process holds one cluster-access layer and puts two front-ends on
it: a Freelens-grade web UI for the human, and an MCP tool surface for the agent the operator
already runs. It is self-hosted, needs no kubeconfig on the client, no desktop install and no
account, and it treats writes as suggest → preview → confirm → audit. It is explicitly **not** a
fleet platform and **not** multi-tenant: ADR-001 settled that authentication exists to stop
drive-by writes, not to separate people.

Who it is for: the platform engineer or homelab-to-small-team operator who administers a handful
of clusters, especially in a JVM shop. Who it is **not** for, and we should say so: a team that
needs per-user RBAC (Headlamp), a fleet operator (Rancher), or anyone buying SaaS observability.

The defensible ground: **IDE depth** — schema-driven YAML from the cluster's own OpenAPI, a
schema-generated form editor, diff-before-apply against what the server *would store*, the tabbed
exec/log dock, in-browser port-forward, jhelm — plus **one access layer, two surfaces**. Not MCP
(table stakes since H1 2026) and not the apiserver-proxy metrics trick (Lens has always done it).

## 2. What ADR-001 settled

The competitive review ranked the disqualifiers **identity → RBAC-awareness → pagination →
server-side dry-run → per-kind depth → diff quality → extensibility → keyboard**. ADR-001 deleted
#1 (a position, not a gap) and demoted #2 (with one shared credential there is no user whose
permissions we could reflect; `SelfSubjectAccessReview` would only answer "can kweblens's own
service account do this", which is a UI affordance). Measurement has since deleted #3 and shipped
#4. **Five of the review's eight ranked disqualifiers are now decided or done.** That is the
single most important fact about this document: the ranking it inherited no longer exists.

## 3. The 2026-07-31 plan, item by item

Each row is checked against the code, not the tracker.

| Old item | Verdict | Evidence |
|---|---|---|
| **T1** write path cannot be previewed; audit is volatile | **DONE** | `ResourceService.dryRunApply` / `dryRunPatch` send `withDryRun(List.of("All"))`; `YamlApiController` `/apply/dry-run`; the Review Changes tab's second diff (#274). Audit goes to a dedicated `kweblens.audit` logger as well as the ring (#212). Remediation previews are server-validated where a patch exists and say `notChecked` where one does not (#209). |
| **T2** nothing in the list path is bounded | **ANSWERED — do not build paging** | [`scale-measurements.md`](scale-measurements.md) §"Is server-side paging still worth building?". Wire: 16.78 MB / 637 ms for 3 000 pods after #279. Browser: main-thread block **flat** at 370 / 371 / 299 ms across a 15× range in object count, DOM rows pinned at 20 (#286). `/counts`: one `limit=1` request per kind + `metadata.remainingItemCount` (#283), 330→112 ms, 22.1 MB→111 KB. Fan-out: accept N + `SseKeepAlive` (#283, #288), argued in [`watch-fanout.md`](watch-fanout.md). **The unbounded axis is heap, not wire → GH#293.** |
| **T3** finding a specific object is weak | **HALF SHIPPED** | Palette now indexes **objects**: `web/search/SearchService` over 13 kinds, ranked, reporting what it did not search; `commandPalette.ts` `objectCommands` / `mergeCommands` / `scopeNotes` (#263). Still true: `shell.ts:filterObjects` is three `.includes()` calls, and there is no regex, negation, label selector or field selector anywhere. |
| **T4** error and empty states are inconsistent | **STILL TRUE, slightly worse** | `ErrorNotice` renders in 8 files (was 6), still Helm/Clusters-dominated. **11** bare `<div class="error">` with no retry, unchanged. **~20** ad-hoc empty-state sites under five different class names, and no `EmptyState` component exists. `ResourceTable.vue` has no `#empty` slot at all. And it is a defect class, not cosmetics — `HelmResourcesModal.vue` used `<ErrorNotice>` without importing it, so its error path rendered *nothing* (fixed in this PR). |
| **T5** RBAC-awareness in its reduced form | **STILL TRUE** | Zero hits for `SelfSubjectAccessReview` / `SelfSubjectRulesReview` / `canI` in any `.java`, `.ts` or `.vue` file. All 24 hits are prose. |
| **D1** relations breadth | **SHIPPED** | `RelationService` is now a dispatcher over five resolvers (`NetworkRelations`, `ReferenceRelations`, `WorkloadRelations`, `StorageRelations`, `AccessRelations`) resolving **12** relation keys, up from 3 (#220). [`detail-sections-audit.md`](detail-sections-audit.md) §"Group B status" is the current record. Not resolved, deliberately: Ingress → TLS secret **expiry** (`Relation` carries objects, so a *missing* reference can only be dropped or fabricated), requests-vs-usage (a metrics path, not a relation), and a general reverse index. |
| **D2** agent-attach story | **NOT STARTED** | No attach page, no `mcpServers` snippet, no `claude mcp add` anywhere in `README.md` or `docs/`; `docs/deployment.md` has zero MCP mentions. The transport *is* documented correctly (`README.md` and `CLAUDE.md` both say SSE over WebMVC, `GET /sse`). |
| **D3** analyzer breadth / cross-manifest rules | **NOT STARTED, now cheaper** | It was ranked below D1 because it depended on D1's joins. Those joins exist. |
| **D4** guarded MCP write tools | **UNBLOCKED, unscoped** | 15 `@Tool` methods across three beans, none mutating. The T1 gate has lifted; what is left is Radar's scoping question (destructive-annotated, no delete, no shell), not a missing guardrail. |
| **Chore I** docs currency | **DONE** | `README.md` and `CLAUDE.md` both say 15 read-only tools and SSE; the README frames identity per ADR-001 rather than as "the gap that matters". Only `competitor-analysis.md` still says 3, and it is a dated research snapshot. |

**Three structural things the old cut got wrong, worth naming so they are not repeated:**

1. **T1's "Blocks: nothing" line was parking work that was already unblocked.** D4 sat behind a
   gate that had lifted; the document said so in a paragraph and then re-listed D4 as item 8.
2. **The sequencing rule "paging and filtering are one piece of work"** was correct and is now
   moot — neither is being built. Everything that inherited a block from it (T3, GH#143, GH#148)
   inherited a block from a contract that will not be written.
3. **§8's issue table referenced GH#140, GH#141 and GH#142 as open.** All three closed
   (2026-08-03, 2026-08-01, 2026-08-03). "Folds into GH#142" is not a plan.

## 4. The biggest gap is not on the old list

With T1 done, T2 answered and D1 shipped, the honest answer to "what would a user miss most" is
not a feature. It is two things:

**(a) The product can be OOM-killed by listing Secrets — GH#293.** `ObjectApiController.objects`
is `Serialization.asJson(ListProjection.forList(resources.listRaw(...)))`: the whole collection is
deserialised, projected in place, then materialised as one `String`, so a request holds three
copies at once. Measured on the live path, ~241 KB of transient heap **per Secret** — 10.6× the
object's own JSON, and ~500× what #279 leaves on the wire. The shipped chart sets
`resources.limits.memory: 1Gi` (`deploy/helm/kweblens/values.yaml`), and over-limit is an
OOM-kill, not a slowdown. The trigger is **~2 000 Secrets**, and Helm stores one Secret per release
*revision*, so a cluster with a couple of hundred releases is already there with a hundred pods.
Every other axis — payload size, row count, block time — looks healthy the whole way, because
#279, #283 and #286 fixed exactly those. This is the only known way to take the process down.

**(b) Nothing has ever been released.** No git tags. No GitHub releases. The version is
`0.1.0-SNAPSHOT`. `repo1.maven.org/maven2/org/alexmond/kweblens-core/` returns **404**, while
`README.md`'s module table claims "✅ Maven Central" for both `kweblens-core` and `kweblens-cli`
(corrected in this PR). `.github/workflows/` contains `ci.yml` and `maven_release.yml` and nothing
that builds or publishes an image, and `deploy/helm/kweblens/values.yaml` defaults to
`repository: kweblens` with no registry — so the chart assumes an image you built yourself. The
only install path for a would-be user is *clone the repo and run Maven*.

That is the real state of the product: a broad, well-tested, well-measured feature surface with no
distribution. Ranking a filter syntax or an SSAR affordance above that would be planning for a
user who cannot obtain the software.

## 5. The plan, re-ranked

Ranked for the single trusted operator ADR-001 describes. Two items, then a tail of polish. If
that reads short, it is because it is — see §4.

### R1 — GH#293: stop materialising the list

Measure **first**: one `jcmd GC.class_histogram` at peak during a large Secrets list, against a
*live* cluster (the simulator's API server shares the JVM and is inside the reading). That single
number decides the fix and has not been taken.

- If the spike is dominated by the output `String`, stream the projected list straight to the
  response body. That removes it without touching the list contract, the filter semantics, or
  three future clients — and paging stays unbuilt.
- If it is dominated by the deserialised model graph, streaming the output halves it and
  something that bounds what is *deserialised* is eventually needed. Re-open the paging question
  then, with a number, and not before.

`scripts/heap-probe.sh` is the probe. **Secret count per cluster is the thing to watch**, not pod
count and not payload size.

### R2 — Cut a release and publish an image

The gap in §4(b), closed. Concretely: a numeric `0.1.0` (never `-RC`/`-M`), the two library
artifacts to Central through the existing `maven_release.yml`, a workflow that builds and pushes
the `kweblens-web` image, and a chart default that points at it. Until this exists every other
item on this list improves software nobody can install.

### R3 — T4: one error state, one empty state

11 bare error divs with no retry, ~20 ad-hoc empty states under five class names, no shared
`EmptyState`, and the main resource table falling through to naive-ui's default "No Data". The
`HelmResourcesModal` bug found while writing this — a component used without being imported, so
the error path silently rendered nothing, past `vue-tsc` and `eslint` — is the argument that this
is correctness and not decoration. Octant's convention is the fix: make the empty-state string a
**required prop** so no list can ship without one.

### R4 — Contract-test the detail endpoint

`DetailApiController` returns a `{object, relations}` envelope carrying 12 relations and has
**zero** HTTP-level coverage: no test file mentions `/detail`, `relations` or `RelationService`
anywhere under `kweblens-web/src/test` (18 MockMvc classes, none on this path). Coverage is
service-level only, in core (`RelationServiceTest`, `RelationBreadthTest`,
`RelationStorageAccessTest` — 23 tests). The envelope, `Relation`'s null-omission, the 400 on a
missing object and the unknown-`resourceId` path are all unverified — and this endpoint is what
D3, GH#148 and GH#143 all build on.

### R5 — D2: the agent-attach page

Still the cheapest differentiator on the list and still unwritten: a copy-paste "attach Claude
Code / Codex / Copilot CLI in three lines" page, a generated cluster-context surface, and an MCP
section in `docs/deployment.md` (which currently has none). The transport documentation is already
correct, so this is purely additive.

### Then, in no strong order

6. **T3 remainder** — a real filter syntax on the list header (regex, negation, label selector).
   Unblocked and now unambiguously *client-side*, because there is no server-side truncation for
   it to lie about.
7. **D3** — security/RBAC checks and the first cross-manifest rule over the 12 relations.
8. **T5** — SSAR affordances: grey out what this deployment's service account cannot do. Never an
   authorization gate (ADR-001: it fails open).
9. **D4** — guarded MCP write tools, on Radar's scoping. A scoping decision, not a blocked one.

## 6. What we are explicitly NOT doing, and why

| Not doing | Why |
|---|---|
| **OIDC / per-user identity / token pass-through** | ADR-001, signed off. A position, not a gap. Re-open only on the ADR's own triggers. |
| **SSAR as an authorization mechanism** | ADR-001 rejects it: it fails open. Only ever a UI affordance (item 8). |
| **Server-side `limit`/continue paging** | **Measured out, 2026-08-05** (#292). It buys none of the three things it was scoped to buy, and it would drag server-side selectors and a renegotiated search contract along with it — against a client-side filter measured at ~0.3 s over 3 000 objects. Re-open only if R1's histogram says the model graph, not the `String`, is the spike. |
| **Sharing one watch per cluster+kind** | Decided against with numbers ([`watch-fanout.md`](watch-fanout.md)): with `SseKeepAlive` the ceiling is "one per list view on screen, released within ~30 s", and at that ceiling a shared watch's lifecycle risk buys nothing. |
| **A plugin framework (GH#146)** | [Research](../research/plugin-framework.md) settled the mechanism and said not yet. The expensive part is the published API surface, which would have to include Naive UI. The trigger is one concrete extension someone actually wants. |
| **Radar-style simultaneous multi-cluster** | [cluster-selection.md](cluster-selection.md): every route, watch and registry entry addresses one cluster id. An access-layer rewrite wearing a sidebar's clothes. |
| **A topology / resource-map graph** | The relation *tables* — now twelve of them — already answer the diagnostic questions. Revisit when a question arrives that the tables cannot answer. |
| **Argo-grade 3-way diff with `ignoreDifferences`** | Over-built for one operator hand-editing YAML, and the actual diff gap was closed by #274. |
| **kweblens-tui (GH#143) now** | Blocked on GH#148 alone — see §7. The old "not before T2" half is void. |
| **Pod file browser on by default** | ADR-001 is explicit: it reads mounted Secrets off disk under a shared credential. Shipped off by default (`kweblens.files.enabled`), and it says why. |
| **External / off-cluster Prometheus auth models** | [metrics-sources.md](metrics-sources.md): four auth models against zero users. |
| **SSH / bastion tunnelling** | [proxy-competitive.md](proxy-competitive.md)'s explicit non-goal — it would mean storing users' SSH keys server-side. |
| **Competing on extensibility, auth breadth, or a published scale record** | Headlamp has 40+ hooks and 30k pods; OpenShift has 81 extension types. The honest pitch stays "the IDE surface Headlamp gates behind plugins or desktop mode, in one jar". |
| **Positioning on MCP novelty or the apiserver-proxy metrics trick** | Both retracted by the review. |

## 7. The parked epics, re-checked

- **GH#148 (per-kind catalog in core) — correctly parked, but its recorded block has changed.**
  It was "blocked by GH#136", which closed 2026-07-30. The live gate is the one in its own dated
  re-audit ([`kind-catalog.md`](kind-catalog.md)): *build it when the TUI or the agent tool
  surface actually starts consuming per-kind data*. Neither has — GH#143 has no module in the
  reactor and the MCP tools carry zero per-kind branching. Drift keeps accruing (`columns.ts` is
  84 `render:` functions across 28 kinds) and the re-audit's preferred first slice, the
  relations catalog, was only *mitigated*: GH#203 shipped the loud-fallback minimum
  (`genericRows`, `humanise(key)`), so `components/relations.ts` is still a client-side mirror of
  a server-side registry. **Verdict: stay parked, at P3, with the block re-stated.**
- **GH#146 (plugin framework) — correctly parked, nothing has changed.** Its stated trigger is
  "one concrete extension someone actually wants", and none has appeared; its stated precondition
  (GH#148 landing) has not either. The mechanism decisions are recorded so they need not be
  re-litigated. **Verdict: stay parked.**
- **GH#143 (kweblens-tui) — still parked, but its recorded reason is now wrong.** It carries
  "blocked by GH#136" (closed) and this document used to add "not before T2". T2's paging is not
  being built, so that half evaporates. The one real block is GH#148: a TUI written against
  today's TypeScript catalog would reimplement 84 renderers plus a second per-kind catalog in
  `relations.ts`, which is the exact drift the module exists to prevent. **Verdict: stay parked,
  blocked on GH#148 alone.**
- **GH#147 (this epic) — close it.** Its three deliverables were a thesis, a sequenced plan and a
  cut into issues; all three exist, and this is the second pass over them against the code. What
  is left is not synthesis work. **Verdict: close.**

## 8. What is uncertain

Stated rather than smoothed over. Several entries from the previous cut have been **resolved by
measurement** and are removed: T2's urgency (measured — #292), whether `metadata.remainingItemCount`
is reachable through fabric8 (yes — `ResourceService.count`, #283), what `dryRun=All` returns
through fabric8's server-side-apply path (the merged object; #274 diffs it), and whether sharing
one watch per cluster+kind is safe (moot — accept-N was chosen with numbers).

What remains genuinely unknown:

- **How GH#293's spike splits between the model graph and the output `String`.** This decides
  R1's shape and it is one histogram away. Do not design before taking it.
- **Anything past 3 000 objects/kind** is inference from demonstrated linearity, not measurement.
  KWOK remains the answer, and the simulator explicitly cannot validate paging (the CRUD mock
  ignores `limit`).
- **Whether the SSE transport conforms to the MCP spec final of 2026-07-28.** The handshake was
  observed against the running server; an `initialize` exchange completing is not conformance.
- **Radar's multi-cluster model** (simultaneous vs switching) remains unverified, and it is the
  one competitor worth re-checking monthly.
- **Exec / port-forward through a SOCKS5 proxy** — flagged by the proxy research as the
  highest-value untested case; fabric8 7.x uses WebSocket rather than SPDY, so kweblens may not
  share the limitation client-go tools document.

## 9. Issues

Open at the time of this re-cut: **GH#143, GH#146, GH#147, GH#148, GH#293** — five, of which
three are parked by decision and one is this epic.

| Plan item | Issue | Priority |
|---|---|---|
| R1 — measure the heap split, then stream the list response | **GH#293** (open) | P1 |
| R2 — cut `0.1.0`, publish the image, correct the README's Central claim | **new** | P1 |
| R3 — shared `EmptyState` + `ErrorNotice` everywhere, empty copy a required prop | **new** (was T4/G) | P2 |
| R4 — server-side contract tests for the detail endpoint | **new** (was part of D) | P2 |
| R5 — agent-attach page + generated cluster context + MCP in deployment.md | **new** (was E/D2) | P2 |
| 6 — list-header filter syntax: regex, negation, label selector | **new** (was C/T3, now client-side) | P3 |
| 7 — security/RBAC checks and the first cross-manifest rule | **new** (was F/D3) | P3 |
| 8 — SSAR affordances | **new** (was H/T5) | P3 |
| 9 — guarded MCP write tools, Radar scoping | **new** (was D4) | P3 |

Superseded from the previous cut and **not** to be re-filed: A (write-path integrity — done,
#209/#212/#274), B (bounded lists — measured out, #292), I (docs currency — done).

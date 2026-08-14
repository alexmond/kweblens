# Roadmap: thesis, what is true now, and what is left

Originally GH#147, which is closed. **Re-cut 2026-08-13**, verified against the code at `fb4e4fd`
and against the issue tracker's actual state — not against the previous cut of this document
(2026-08-10), which had already fallen behind a five-ticket epic that merged in a day.

Read [`competitor-analysis.md`](../competitive-review/competitor-analysis.md) for the landscape
(note: it is a dated snapshot and still describes a 3-tool MCP server; it is 15) and
[`adr-001-identity-model.md`](adr-001-identity-model.md) for the identity decision. This
document says what kweblens is *for*, what is actually true of it today, and what is left.

**The short version.** T1 shipped, T2 was measured and answered in the negative, D1 shipped, and
GH#140 / GH#141 / GH#142 all closed. GH#293 — the one measured way to OOM-kill the process — is
**closed** (#302). Since the last cut, epic GH#336 shipped status-driven navigation in five
tickets, which the previous cut knew nothing about; it is written up as R6 below. There is no
large missing feature. The one thing that still matters is that **nothing has ever been
released**, so nobody can install what has been built — and the remaining half of that is a
button only a human may press. Everything else is polish. This is a harden-and-ship phase and
the plan below is short on purpose.

**The tail is now filed.** D3, T5 and D4 carried prose and a priority through two re-cuts with no
tickets, which is why they kept being re-described instead of done. They are **GH#353**,
**GH#354** and **GH#355**. Every plan item below now points at an issue or at a commit.

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
| **T3** finding a specific object is weak | **DONE** | Palette indexes **objects**: `web/search/SearchService` over 13 kinds, ranked, reporting what it did not search; `commandPalette.ts` `objectCommands` / `mergeCommands` / `scopeNotes` (#263). The remainder shipped as item 6 below: `objectFilter.ts` replaces the three `.includes()` calls with a filter grammar — regex, negation, `name:`/`ns:`/`kind:` and Kubernetes label requirements. Still absent, deliberately: field selectors over arbitrary paths (`status.phase`), which the browser does not hold for every kind. |
| **T4** error and empty states are inconsistent | **DONE** | One `EmptyState` (required `title`) over `emptyState.ts`, everywhere: the four ad-hoc empty-state class names are gone, loading has its own `LoadingNotice`, and `ResourceTable.vue` has its `#empty` slot (#306). The error half then split the last 12 `v-if`-guarded `class="error"` divs into failed *reads* (`ErrorNotice`, with Retry) and failed *actions* (`ActionNotice`, without) over `paneFailure.ts` — see [R3](#r3--t4-one-error-state-one-empty-state). It was never cosmetics — `HelmResourcesModal.vue` used `<ErrorNotice>` without importing it, so its error path rendered *nothing*. |
| **T5** RBAC-awareness in its reduced form | **DONE — GH#354** | `AccessReviewService` (core) batches `SelfSubjectAccessReview` for `create` / `patch` / `delete` per (cluster, group, resource, namespace), caches per cluster with a 60 s TTL and drops it on the registry's `ClusterClientListener`; `web/access` serves it as one `GET …/access` per surface — **three reviews for a list of any length**. The result is a **tri-state**: `allowed` / `denied` / `unknown`, and `unknown` renders as ENABLED. It is an affordance and never a gate — `AccessResultIsNotAGateTest` fails the build if a verdict is referenced outside the presentation slice, and the fail-open tests are written so that inverting the fallback turns six of them red. A cluster-wide `denied` about a *namespaced* kind is weakened to `unknown`, because "not in every namespace" is not "not here". |
| **D1** relations breadth | **SHIPPED** | `RelationService` is now a dispatcher over five resolvers (`NetworkRelations`, `ReferenceRelations`, `WorkloadRelations`, `StorageRelations`, `AccessRelations`) resolving **12** relation keys, up from 3 (#220). [`detail-sections-audit.md`](detail-sections-audit.md) §"Group B status" is the current record. Not resolved, deliberately: Ingress → TLS secret **expiry** (`Relation` carries objects, so a *missing* reference can only be dropped or fabricated), requests-vs-usage (a metrics path, not a relation), and a general reverse index. |
| **D2** agent-attach story | **DONE** — see [R5](#r5--d2-the-agent-attach-page--done) | `docs/modules/ROOT/pages/attach-an-agent.adoc` + an MCP section in `docs/deployment.md` + an *Attach an agent* block in `README.md`. The transport was documented correctly; the *auth* was not — `mcp.adoc` claimed the tool endpoints were public in open-mode, and `POST /mcp/message` measures `401`. |
| **D3** analyzer breadth / cross-manifest rules | **DONE — GH#353** | `DiagnoseService` now runs a fourth validator: `kweblens-core`'s `SecurityAuditService`, which reports privileged and explicitly-root containers from the pod list the diagnosis already had, `cluster-admin` grants, and — the cross-manifest one — the ServiceAccounts those grants name joined to the pods that run as them. It shares the `grantedBy` subject predicate (`RbacSubjects`) with `AccessRelations` rather than re-implementing the join, and costs two list requests for the whole scope, nothing per pod. |
| **D4** guarded MCP write tools | **UNBLOCKED, unscoped — GH#355** | 15 `@Tool` methods across three beans (`ClusterTools` 4, `DiagnosticTools` 4, `HealthTools` 7 — counted on `@Tool\(`, because a bare `@Tool` grep also matches `@ToolParam` and returns 49). None mutating. The T1 gate has lifted; what is left is Radar's scoping question (destructive-annotated, no delete, no shell), not a missing guardrail. |
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

With T1 done, T2 answered and D1 shipped, the honest answer to "what would a user miss most" was
not a feature. It was two things. **One of them is now fixed; the other has been one human action
away since the image workflow merged on 2026-08-09.**

**(a) The product could be OOM-killed by listing Secrets — GH#293. FIXED (#302), issue closed
2026-08-07.** `ObjectApiController.objects` used to be
`Serialization.asJson(ListProjection.forList(resources.listRaw(...)))`: the whole collection
deserialised, projected in place, then materialised as one `String`. Measured on the live path,
~241 KB of transient heap **per Secret**, 10.6× the object's own JSON, against a chart that sets
`resources.limits.memory: 1Gi` — and over-limit is an OOM-kill, not a slowdown. The trigger was
**~2 000 Secrets**, which a cluster with a couple of hundred Helm releases reaches on revision
history alone. `ResourceService.listRawChunked` now walks the kind with `limit` +
`metadata.continue` and `ListJson` serialises each page straight into the payload buffer, so peak
heap follows the chunk rather than the collection: **the same request that needed `-Xmx256m` and
OOMed at 224m completes at 176m**, the app's own boot floor, at identical wall-clock. See R1. No
other way to take the process down is currently known — which is a statement about what has been
measured, not a guarantee.

**(b) Nothing has ever been released, and that is now the whole gap.** Re-verified today: **zero**
git tags, **zero** GitHub releases, version `0.1.0-SNAPSHOT`, and both
`repo1.maven.org/maven2/org/alexmond/kweblens-core/` and `…/kweblens-cli/` return **404**. What
*has* changed since the last cut is everything around it: `README.md` no longer claims Central
(the module table now says where each library *will* publish, under an explicit "No release has
been cut yet"), `.github/workflows/image.yml` builds, smoke-tests and pushes the `kweblens-web`
image, and the chart defaults to `ghcr.io/alexmond/kweblens`. So the only install path for a
would-be user is still *clone the repo and run Maven*, but the reason is no longer missing
machinery — it is that publishing to Maven Central is irreversible and nobody has decided to do
it. That decision is not this document's to make and not an agent's to take.

That is the real state of the product: a broad, well-tested, well-measured feature surface with no
distribution. Ranking a filter syntax or an SSAR affordance above that would be planning for a
user who cannot obtain the software.

## 5. The plan, re-ranked

Ranked for the single trusted operator ADR-001 describes. Two items, then a tail of polish. If
that reads short, it is because it is — see §4.

**Status, 2026-08-13.** R1, R3, R4, R5, R6 and the T3 remainder are **done**; R2 is half done and
its remaining half — cutting the release — is irreversible and belongs to a human, not to this
list. D3 (**GH#353**) is done, and so is T5 (**GH#354**), which shipped as the tri-state access
affordance described above. What is genuinely left below is D4 (**GH#355**), P3 and blocking
nothing.

Worth noting before picking the next item, because it has now happened repeatedly: **most of what
this plan actually bought was found while doing something else.** R4's contract tests uncovered a
dead relation (GH#313); R3's error sweep uncovered an authentication defect where sign-out left
the session valid so any password signed back in (GH#320); R5's attach page uncovered docs
asserting the MCP tool endpoints need no credential when they answer 401; R6 uncovered GH#352, a
bar segment whose idle colour is its empty colour. None of the four was on any list. Budget for
the sweep, not just the item.

And one about the document itself: **R6 was opened, cut into five tickets and merged in the two
days after the previous re-cut was written, which did not mention it at all.** A plan re-cut
weekly cannot describe a repo that ships an epic in a day. Treat any item here that is not
either closed on the tracker or traceable to a merged commit as unverified, and check the code —
that instruction is in CLAUDE.md for this exact reason.

### R1 — GH#293: stop materialising the list — **DONE** (#302)

`ResourceService.listRawChunked` walks a kind with `limit` + `metadata.continue`; `ListJson`
projects and serialises each page straight into the payload buffer, so a page becomes garbage as
soon as it is written. **The response does not change** — chunking is invisible to the caller, so
GH#263's refusal of a client-visible `limit` is untouched, and `ListJsonTest` asserts the
assembled bytes are byte-identical to `Serialization.asJson` over the whole collection.

**The measurement this item prescribed was the wrong one, twice over, and that is the durable
lesson.** A `jcmd GC.class_histogram` *cannot* separate the output `String` from the model graph:
since JDK 9 compact strings, both land in `byte[]`. And `heap-probe.sh`'s `transient`
(peak − base) scored chunking **worse** — 80–96 MB against 67–69 MB — because it measures
allocation *churn*, not the live set: five bounded pages allocate more total garbage than one big
graph while far less of it is live at any instant. Reading either would have got this reverted.

What answered it was the question an OOM-kill actually asks — **the smallest `-Xmx` in which the
request still completes**, where collector timing drops out because only live bytes can push a
squeezed heap over. On 8 150 live Secrets (54.89 MB stored, 2.08 MB on the wire): unchunked needs
**256m** and OOMs at 224m; chunked completes at **176m**, the app's own boot floor, at identical
wall-clock. That 256m is the number that mattered — the chart sets `limits.memory: 1Gi` and the
JVM takes a quarter of the container limit as its default max heap. Full workings:
[`scale-measurements.md`](scale-measurements.md).

**That fork is closed, and it did not resolve the way the old wording assumed.** The previous cut
offered two branches — "if it is the output `String`, stream it; if it is the model graph, re-open
paging". `alloc-probe.sh` answered it, and the answer is **the model graph, decisively**: for a
151-object Secrets list, allocation splits 56% response bytes / 37% Jackson's model graph /
**1.4% the output `String`**, and the independent per-thread cut agrees — `vert.x-eventloop-*`
52.8 MB against `tomcat-handler-*` 1.44 MB, stable across two runs an hour apart. So 97.5% of the
allocation has already happened before `ObjectApiController` runs its one line. Streaming the
response would have removed 1.4% of a Secrets list; **paging was still not the fix** either,
because #263's refusal of a client-visible `limit` stands. What answered it was the third option
neither branch named: chunk the *fetch*, invisibly to the caller. `scripts/heap-probe.sh` says how
much is resident, `scripts/alloc-probe.sh` says which code allocated it — reach for the second
whenever the first surprises you. **Secret count per cluster is the thing to watch**, not pod
count and not payload size. GH#293 is closed.

### R2 — Cut a release and publish an image — **HALF DONE** (#311); the rest needs a human

The gap in §4(b). Concretely: a numeric `0.1.0` (never `-RC`/`-M`), the two library artifacts to
Central through the existing `maven_release.yml`, a workflow that builds and pushes the
`kweblens-web` image, and a chart default that points at it. Until this exists every other item
on this list improves software nobody can install.

**Shipped (#311).** `.github/workflows/image.yml` builds `kweblens-web` through the `docker`
profile, **smoke-tests that the image reaches a healthy actuator before publishing** — a
buildpack image that boots to a stack trace is still a successful `package` — and pushes to
GHCR. It publishes on a `v*` tag, or on a `workflow_dispatch` where `publish` is explicitly
true; `publish` defaults to **false**, so "does the image still build?" pushes nothing, and
`latest` moves only on a tag. The chart default is `ghcr.io/alexmond/kweblens`, with a comment
saying plainly that the tag does not exist until the first `v*` tag is pushed.

**Not done, and deliberately not automatable: the release itself.** Publishing to Maven Central
is irreversible, so `maven_release.yml` is `workflow_dispatch` only and must be triggered by a
person who has decided to release. Nothing in this repo should ever trigger it on your behalf,
and no date belongs on this line.

**Exactly what remains, re-verified 2026-08-13.** `git tag` lists **zero** tags; `gh release list`
is empty; `repo1.maven.org/maven2/org/alexmond/kweblens-core/` and `…/kweblens-cli/` both return
**404**. `README.md`'s module table still names Maven Central as where each library *will*
publish, but now carries the caveat directly under it — "No release has been cut yet. Nothing is
on Maven Central and no image is published" — so the claim the previous cut called wrong is
fixed. `deploy/helm/kweblens/values.yaml` defaults `repository: ghcr.io/alexmond/kweblens` with a
comment saying the tag does not exist until the first `v*` tag is pushed.

So the whole remainder is **one human action**: run `Maven release` with a `releaseVersion` of
`0.1.0` and a `nextVersion` of `0.2.0-SNAPSHOT` (numeric only — never `-RC`, `-M`, `-alpha`). That
workflow does `versions:set` → full `verify` → commit and tag → `deploy -Prelease` → next
SNAPSHOT. The `v*` tag it pushes is what fires `image.yml`, so the image and the chart's default
become real as a consequence — no second decision. Everything downstream of that is already
built and already proven: `image.yml` smoke-tests that the image reaches a healthy actuator
before it pushes, and a `workflow_dispatch` run with `publish: false` (the default) proves a
change to it without publishing anything.

Local caveat worth knowing before debugging a red build: **the image cannot be built on a
rootless-podman box.** The buildpack asks the daemon to bind-mount `/var/run/docker.sock` and
podman refuses (`statfs /var/run/docker.sock: permission denied`). That is the environment, not
the build; CI has a real Docker daemon.

### R3 — T4: one error state, one empty state — **DONE**

The rule the whole item exists for: **an empty pane is a claim.** "Still loading", "this failed"
and "there is genuinely nothing here" are three different sentences and must never render as the
same one. The `HelmResourcesModal` bug found while writing the previous cut — a component used
without being imported, so the error path silently rendered nothing, past `vue-tsc` and `eslint`
— is the argument that this is correctness and not decoration. Octant's convention is the fix:
make the empty-state string a **required prop** so no list can ship without one.

**Empty half: DONE.** `components/EmptyState.vue` (title **required**) over `emptyState.ts`,
which holds the branching so it is testable without a DOM. `#298` created it and used it twice;
`#306` gave `ResourceTable.vue` its `#empty` slot and `resourceListEmpty`; the sweep did the
rest. Every ad-hoc site now renders `EmptyState` (`variant="inline"` where the pane already has
a frame) or the new `LoadingNotice`, and **`.empty` / `.palette-empty` / `.diff-empty` /
`.cp-empty` are gone** — `.tone-empty` survives and is *not* one of them: it is the neutral fill
of an `aria-hidden` stat-card bar, in the `tone-*` family, and is commented as such so the next
sweep does not count it.

The sweep found three live defects, each the same shape — a pane answering a question that had
not been answered. `HelmValuesModal` left "Loading…" on screen forever underneath its own error,
because the loading branch keyed off `values === null` and a failure never fills `values`.
`MetricChart`'s `.catch` wrote `{ available: false }`, so **every** failed `/metrics/graph` call
rendered as "Graphs need a Prometheus / VictoriaMetrics backend" — a confident claim about the
operator's cluster produced by an error nobody read; it now shows the error with a retry.
`CommandPalette` printed "No match for “x”" directly above "Object search failed". A shared
`mayClaimEmpty` predicate and one `it.each` over every builder pin the rule.

**Error half: DONE.** The remaining 12 were `<div v-if="…" class="error">` — which is why
`grep '<div class="error"'` had reported **0** and the previous cut read that as finished. The
honest pattern is `grep -rnE '<div [^>]*class="error"'`, and it now returns none.

They did **not** all become `ErrorNotice`. `ErrorNotice`'s Retry re-runs a *fetch*; a failed
Helm upgrade, a refused port-forward or "Invalid credentials." is the result of an **action**,
where the same button would offer to repeat a write nobody re-authorised. So the split is:

- **7 failed reads → `ErrorNotice` with Retry** — `CategoryOverview`, `ClusterOverview` ×2
  (nodes and warnings retry separately: they fail separately, and one button would re-fetch
  the half that worked), `EventsPane` (which owns no request, so it emits `retry` to its two
  owners), `HelmValuesModal`, `NodePodsPane`, `YamlTab`. Four moved onto `useAsyncData`,
  which already had `reload`.
- **3 action results → the new `ActionNotice`, no Retry** — `LoginModal`, `ForwardModal`,
  `HelmActionModal`. The modal's own Sign in / Start forward / Apply is the re-do, and it is
  one the operator presses knowingly.
- **2 slots hold BOTH**, which is where the "classify each site" framing was wrong: the
  verdict belongs to the *writer*, not the pane. `App.vue`'s single `error` ref was written by
  four reads **and** by every row action plus bulk delete, so a Retry there would have offered
  to re-run a failed Drain; `PortForwards` writes its 3-second poll and its Stop button into
  one slot. Both now carry a `PaneFailure` union and render `FailureNotice`, which dispatches.

`paneFailure.ts` holds the logic, so the copy and the split are testable without a DOM. Its
load-bearing rule is that **a failed write is not a write that did not happen**: only a verdict
(400/403/409/422, per `isRefusal`) or kweblens's own 401 licenses "nothing was changed", and a
timeout gets an explicit "unknown — check before trying again". CLAUDE.md already records the
shipped case where a write lands after the UI reports failure. The one exception is a Helm
dry-run, which applies nothing whatever goes wrong and says so.

Two things fell out of verifying it. `App.vue`'s `showClusterOverview` and its zero-cluster
empty state both keyed off "is there an error", so a failed **Restart** blanked the dashboard
the operator was standing on; both now ask whether a *read* failed. And the Playwright scene
that renders a rejected sign-in exposed **GH#320**: Sign out clears the in-memory credentials
but not the `HttpSession`, so after one successful sign-in any password signs back in.

### R4 — Contract-test the detail endpoint — **DONE** (#312)

`DetailApiController` returns a `{object, relations}` envelope carrying 12 relations and had
**zero** HTTP-level coverage — the endpoint D3, GH#148 and GH#143 all build on. `DetailEndpointsTest`
now pins it: the envelope's exact key set, `object` shipped **whole** (a ConfigMap's values are
present, where the list endpoint's `ListProjection` nulls them — an asymmetry a second client
needs and nothing stated), `relations` always an object rather than absent or null, `Relation`'s
three states in one response, all twelve relations asserted **as a set** so a thirteenth without
a test fails, and the 400 / unknown-`resourceId` / unknown-cluster paths.

Each relation is seeded with a **decoy** so an assertion cannot pass on "non-empty" — the
sharpest being a Deployment whose own labels are `app=db` while its pod template's are `app=web`,
with a PodDisruptionBudget on each, so a join reading the wrong labels returns exactly one item
of the wrong name. Everything passed first run, so the expectations were then **mutated 11 ways**
(wrong key set, each decoy substituted for the real answer, owner chain reversed, 400→404, a
relation dropped from the coverage set, the null-omission control inverted); all 11 failed before
being reverted. A green test that has never been shown to fail pins nothing.

**It found a live bug in the process** — GH#313, fixed in #319: `Overview.vue` skipped the
relations fetch for any object without a namespace, so a cluster-scoped PersistentVolume never
asked for `boundClaim` and 1 of the 12 relations was dead in the only shipped client.

### R5 — D2: the agent-attach page — **DONE**

Shipped as `docs/modules/ROOT/pages/attach-an-agent.adoc` (in the nav under *Integrating*), an
`## MCP` section in `docs/deployment.md`, and an *Attach an agent* block in `README.md`. Every
command on those pages was executed against a running instance.

Writing it turned up one thing the docs had wrong, which is the whole reason the page exists:
**an MCP client needs the admin credential in `open-mode` too.** `GET /sse` is a `GET` and rides
the public read path, but the JSON-RPC messages are `POST /mcp/message`, which
`anyRequest().authenticated()` catches — measured, `401`. `mcp.adoc` had said the tool endpoints
were "reachable without signing in, like every other read"; `security.adoc` had it right. Fixed.
A client configured without credentials handshakes and then reports a flat "failed to connect",
so this was the single highest-value fact to get onto an attach page.

Also settled by measurement while writing it: `codex mcp add --url` speaks *Streamable HTTP* and
authenticates only with a bearer token, so neither half matches kweblens — Codex and Copilot CLI
attach through an `npx mcp-remote … --transport sse-only` stdio bridge, verified healthy.

**The "generated cluster-context surface" was deliberately not built as an artifact.** An attached
agent already has `listClusters` and `listResourceKinds`, which are live; a generated file is a
snapshot that goes stale silently, and a stale cluster id is worse than no file because it
produces a confident tool call against nothing. What shipped instead is a two-line `curl | jq`
recipe on the attach page for people who want a snapshot in a prompt or an `AGENTS.md`, labelled
as a snapshot. Revisit only if someone wants context for a client that cannot call tools at all.

### R6 — GH#336: a status the card and the row agree on — **DONE** (#346, #348, #349, #350, #351)

**This was not on any previous version of this plan.** It was opened, cut into five tickets and
merged inside two days, and the 2026-08-10 cut — written while it was in flight — does not mention
it. That is worth recording as a fact about the document, not just about the epic: a plan re-cut
weekly will miss an epic that ships in a day.

The problem it fixed was a *disagreement*, not a missing feature. An overview card's per-state
breakdown — `80 Running · 6 Completed · 3 Pending · 2 Failed` — was inert text computed
**server-side** by `HealthService`, while a list row's status was computed **client-side** in
`columns.ts`. Two predicates producing similar words. Wiring a click straight through would have
opened a list showing 2 for a card that said 3, with nothing on screen admitting it — the same
class of defect as #157 and #316. So the first ticket was not a UI ticket.

**One seam.** `kweblens-core/…/health/StatusVocabulary.java` is it, and its javadoc says so:
*"**The** definition of what state an object is in — the single seam both an overview card's count
and a list's status filter go through."* `StatusVocabulary.FIELD` is the string `kweblensState`,
synthetic and prefixed so it cannot collide with a CRD's own top-level field.
`web/api/ListProjection` attaches it to every row — on the `objects` endpoint **and** on the
`objects/watch` stream, because they are separate code paths feeding one table and a field added
to only one of them would be erased by the first watch event to arrive.

**The rule that keeps it honest:** an uncovered kind ships **no** state, and `state()` returns
`null`. That is deliberately not "OK" — a row with no state is matched by no `status:` term, so
nothing can be counted as healthy on the strength of never having been examined. The client half
enforces the same thing: a row whose state label is empty is rejected *before* the matcher runs,
so no regex — not even `/^$/` — can select an unjudged row.

**13 kinds are judged**, via five `supports()` predicates rather than a map, so adding a kind to a
check adds it to the filter with no second list to update:

- pure-function (9): Pod, Deployment, StatefulSet, ReplicaSet, DaemonSet, Job, CronJob
  (`WorkloadHealth`); Node, Namespace (`ClusterObjectHealth`, new in #339) — the two the Cluster
  overview had nothing to click.
- context-carrying (4, #340): Service (`NetworkHealthService`), PersistentVolumeClaim
  (`StorageHealthService`), ConfigMap and Secret (`ConfigUsageService`). These are the verdicts
  that are **not in the object** — "No endpoints", "Nearly full" at a 0.90 threshold, "Not
  referenced" — so a per-request `StatusContext` carries the second collection (an Endpoints map,
  a volume-usage reading, a namespace reference scan). `StatusContexts` opens one per request,
  refreshes a watch's on a 30 s TTL lazily on the next question rather than on a timer, and falls
  back to `StatusContext.none()` if opening throws: the list still renders, the rows just carry no
  state. Fail-quiet, not fail-wrong.
- **Event is excluded on purpose.** An Event's `Warning`/`Normal` is a `type` on a report *about*
  another object, not a verdict on itself, so the Cluster overview's Warnings figure stays plain
  text.

**The vocabulary is open, not closed**, and one comment in `objectFilter.ts` calls it "a small
closed vocabulary" — that is the one thing in this area written more confidently than the code
supports. Three producers pass a value straight through: a pod's container waiting reason
(`CrashLoopBackOff`, `ImagePullBackOff`, …), an unrecognised Namespace phase, and a non-`Bound`
PVC phase. That is the right design — it beats mapping an unknown reason onto a known word — but
it means no exhaustive list of state names exists, and none should be written.

**What the operator got.** `status:Running` matches the **whole** label case-insensitively, not a
substring, so one state cannot drag another in with it; `status:"Nearly full"` for labels with
whitespace; `status:/backoff/` as the only fuzzy form; `-status:` negates like every other term.
A card line with a non-zero count and an expressible label renders as a `<button>` and any other
line as a `<span>` — `0 Failed` is not a link, because navigating to an empty list is a worse
answer than text. And `statusChips.ts` reads the buckets **off the rows** rather than from a
per-kind table, computed over the query with the positive status term removed, which is what makes
a chip's number equal the rows its click produces.

Equality is pinned by test rather than by review: `StatusVocabularyEqualityTest` and
`ContextualStatusEqualityTest` run the card tally and the row projection over one seeded fleet and
compare the **sets**, each with an explicit `theComparisonIsCapableOfFailing` negative control.
`scripts/state-link-check.mjs` does the same against a running instance across three numbers — the
card's `.ov-state-n`, the list header's count, and `tbody tr`.

**Both pieces of residue were filed the day this was written; one is now closed.**

1. **The Status *column* covered 7 of the 13 judged kinds — closed 2026-08-14 by GH#357 (#376).**
   The six that disagreed or were absent (`nodes` on a locally computed `nodeReady`, `namespaces`
   and `persistentvolumeclaims` on `status.phase`, `services`/`configmaps`/`secrets` with no
   column at all) now render `kweblensState` through the shared `serverState`. Two kinds are
   excluded **with the reason in the code**: `persistentvolumes`, because no producer judges a PV —
   `StorageHealthService` judges the *claim*, so the column would read `—` on every row — and
   `events`, refused on purpose by #339. `columns.test.ts` now pins the rule in both directions
   with a decoy kind, and was shown to fail three ways before passing. Node's old renderer could
   not express `Ready,SchedulingDisabled` at all, so a cordoned node read a plain `Ready`; that is
   fixed, though **no reachable cluster has a cordoned or NotReady node, so the two suffixed
   labels have never been rendered on screen** — the width is a clone measurement plus
   `ClusterObjectHealthTest`, not a live sighting.
2. **A card-state click is not a route — still open, GH#358.** `App.vue` hands a `pendingQuery` to
   `navigateToKind`; the filter never reaches the URL, so a state-filtered list cannot be
   reloaded, bookmarked or shared.

One bug is already filed out of the epic: **GH#352** — an idle segment of an overview bar is the
same colour as an empty one, so 109 of 164 Secrets read as nothing.

### Then, in no strong order

6. **T3 remainder** — **DONE.** A filter grammar on the list header: `objectFilter.ts` parses
   whitespace-separated terms ANDed together, any of them negatable with `-`, over bare
   substrings (unchanged), `"quoted text"`, `/regex/`, `name:`/`ns:`/`kind:` fields, and
   Kubernetes label requirements with apimachinery's own semantics — `!=` and `notin` match an
   object that lacks the key. Client-side, because the list endpoint returns the whole
   collection and a filter over a truncated page reports "no matches" for an object that
   exists. **A query that does not parse is not applied**: every row stays, and the header says
   what is wrong, rather than an empty table blaming the cluster. Presence/absence is spelled
   `label:k` / `-label:k` rather than Kubernetes' bare `k` / `!k`, because a bare word had to
   stay a text search; that, and the two other subsets (`key>1`, field selectors), are named in
   the UI's help popover. Syntax:
   [`browsing-resources.adoc`](../modules/ROOT/pages/browsing-resources.adoc).
7. **D3 — GH#353. DONE.** Security findings, and the first cross-manifest rule. Shipped as
   `SecurityAuditService` in `kweblens-core/health/`, read on the deterministic GET path and
   exposed as the MCP `checkSecurity` tool. The cross-manifest finding names a ServiceAccount,
   the binding that makes it cluster-admin and the pods that run as it — none of the three
   objects states it alone. The join reuses `grantedBy`'s subject predicate; no thirteenth
   relation was needed.
8. **T5 — GH#354.** SSAR affordances: grey out what this deployment's service account cannot do.
   **Never an authorization gate.** ADR-001 rejects that explicitly, because it fails open — a
   failed or unavailable probe must leave the control **enabled**, since a control greyed out by a
   broken probe is a lie about the cluster. That is why the ticket asks for a tri-state
   `allowed`/`denied`/`unknown` result rather than a boolean, and why the test written first is the
   one that proves fail-open.
9. **D4 — GH#355.** Guarded MCP write tools, on Radar's scoping (destructive-annotated, no delete,
   no shell). A scoping decision, not a blocked one — the T1 gate lifted when dry-run and durable
   audit shipped. The first deliverable is the scope written down: which verbs, and how an agent
   "confirms" when kweblens's write model puts a human in the middle.

All three are **P3**. None blocks another, and none is a prerequisite for R2.

## 6. What we are explicitly NOT doing, and why

| Not doing | Why |
|---|---|
| **OIDC / per-user identity / token pass-through** | ADR-001, signed off. A position, not a gap. Re-open only on the ADR's own triggers. |
| **SSAR as an authorization mechanism** | ADR-001 rejects it: it fails open. Only ever a UI affordance (item 8, **GH#354**). |
| **Server-side `limit`/continue paging** | **Measured out, 2026-08-05** (#292) and *not* re-opened by R1. It buys none of the three things it was scoped to buy, and it would drag server-side selectors and a renegotiated search contract along with it — against a client-side filter measured at ~0.3 s over 3 000 objects. The old trigger on this row said "re-open if R1's histogram says the model graph is the spike"; the model graph **is** the spike, and paging is still not the fix — chunking the *fetch* bounded the heap while leaving the response, the list contract and #263 untouched (#302). The row's decision is unchanged; only its stale trigger is. |
| **Sharing one watch per cluster+kind** | Decided against with numbers ([`watch-fanout.md`](watch-fanout.md)): with `SseKeepAlive` the ceiling is "one per list view on screen, released within ~30 s", and at that ceiling a shared watch's lifecycle risk buys nothing. |
| **A plugin framework** | [Research](../research/plugin-framework.md) settled the mechanism and said not yet. The expensive part is the published API surface, which would have to include Naive UI. The trigger is one concrete extension someone actually wants. GH#146 was the *investigation*, and it closed COMPLETED on 2026-08-07 — do not cite it as an open parked epic. |
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

Re-checked against the code at `fb4e4fd` and against the tracker, 2026-08-13. Two of the four
entries the previous cut listed here are **not issues any more**, and one of the two that remain
has had half its stated gate fire.

- **GH#148 (per-kind catalog in core) — still open, still parked, but half its gate has fired and
  the number it cites is wrong.** The live gate from its own dated re-audit
  ([`kind-catalog.md`](kind-catalog.md)) is *build it when the TUI or the agent tool surface
  actually starts consuming per-kind data*. Measured today:
  - **TUI half: still true.** No `tui` module anywhere — `grep -rn "tui" --include=pom.xml` is
    0 hits across all six poms; the reactor is `kweblens-core`, `kweblens-cli` plus
    `kweblens-ui` / `kweblens-web` / `kweblens-it` in profiles.
  - **Agent half: no longer true.** `kind-catalog.md` states the gate as "GH#143 (TUI) and GH#142
    (agent tool surface) are both open" — **GH#142 closed 2026-08-03**, and the 15-tool surface
    shipped. The tools still carry zero kind-name branching of their own — no `switch` and no
    `case "…"` anywhere in `web/mcp/*.java`, and the package's only per-kind branch is
    `ToolRedaction`'s `if ("Secret".equals(...))` — but that is because `HealthTools` now
    *consumes* a server-side per-kind registry: `.filter((d) -> WorkloadHealth.supports(d.kind()))`,
    and four of its seven tools are scoped to a specific kind set. **That document is stale on
    this exact point and should be re-dated before it is quoted again.**
  - **The drift number has gone down, not up.** `columns.ts` is **83** `render:` functions across
    28 kinds, not the 84 this document has been repeating: #341/#350 replaced seven hand-rolled
    status renderers with one shared `serverState` reused seven times.
  - The re-audit's preferred first slice, the relations catalog, is still only *mitigated*:
    `components/relations.ts` (171 lines) has `PROJECTIONS` with 3 keys, `TITLES` with 3 entries,
    and GH#203's loud fallbacks `genericRows` / `humanise(key)` — a client-side mirror of a
    server-side registry, unchanged.
  - **Verdict: stay parked, at P3 — but its recorded block must be re-stated, not repeated.**
    A server-side per-kind registry now exists in `kweblens-core` and is consumed by the web API,
    the overview cards, the MCP tools and the UI. It covers **status verdicts** for 13 kinds. It
    does **not** cover columns (28 kinds) or relations, which is what #148 is actually about. So
    the block is no longer "nothing on the server knows about kinds"; it is "the two catalogs
    #148 names still have no server-side consumer".
- **GH#143 (kweblens-tui) — still open, still parked, blocked on GH#148 alone.** The "blocked by
  GH#136" it carries is dead (closed 2026-07-30) and "not before T2" evaporated when paging was
  measured out. The real block stands: a TUI written against today's TypeScript catalog would
  reimplement **83** renderers plus a second per-kind catalog in `relations.ts`, which is exactly
  the drift the module exists to prevent. **Verdict: stay parked.**
- **GH#146 (plugin framework) — no longer a parked epic; it is closed.** It was an *investigation*
  ticket, the investigation shipped as [`plugin-framework.md`](../research/plugin-framework.md)
  (#197), and the issue closed **COMPLETED on 2026-08-07** — three days before the previous cut,
  which nevertheless recorded it as "correctly parked, nothing has changed". The decision it
  reached ("do not build one yet"; script tags over a fetched manifest, not `new Function` and not
  Module Federation; `AutoConfiguration.imports` and a restart, not PF4J; operator-installed and
  fully trusted, per ADR-001) lives in §6 above, which is where a settled non-goal belongs.
  **Verdict: not a parked epic. Do not re-file it as one.**
- **GH#147 (the epic this document was written for) — closed 2026-08-05**, as the previous cut
  recommended. This document outlived it and is no longer tracked by an issue; that is fine, and
  it is why the header no longer opens with an issue number.

## 8. What is uncertain

Stated rather than smoothed over. Several entries from the previous cut have been **resolved by
measurement** and are removed: T2's urgency (measured — #292), whether `metadata.remainingItemCount`
is reachable through fabric8 (yes — `ResourceService.count`, #283), what `dryRun=All` returns
through fabric8's server-side-apply path (the merged object; #274 diffs it), and whether sharing
one watch per cluster+kind is safe (moot — accept-N was chosen with numbers). **Removed this
cut: how GH#293's spike splits** — `alloc-probe.sh` answered it (the model graph, 97.5% of a
Secrets list's allocation before the controller runs), and note that the instrument this section
originally prescribed, a class histogram, *could not have* answered it.

What remains genuinely unknown:

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

**Open right now, in full: GH#143, GH#148, GH#352, GH#353, GH#354, GH#355** — six. Two are the
parked epics of §7; one is a bug out of R6; three are the tail this cut filed. **Nothing else is
open.** GH#146, GH#147 and GH#293 all closed and the previous cut's table still listed the first
two as live — the table below cites no issue that is not verified against `gh issue view`.

The previous cut's table said "**new**" in five rows, which here meant *never filed*. That word
is not used below: every row names a real issue number or a merge.

| Plan item | Issue | State |
|---|---|---|
| R1 — bound the list heap by chunking the fetch | **GH#293** | **closed** 2026-08-07 (#302) |
| R2 — cut `0.1.0`, publish the image, correct the README's Central claim | no issue; image half merged as **#311** | **half done** — the release itself is the user's trigger to pull, and irreversible |
| R3 — shared `EmptyState` + `ErrorNotice`/`ActionNotice` everywhere, empty copy a required prop | merged as **#306**, **#316**, **#324** | done (was T4/G) |
| R4 — server-side contract tests for the detail endpoint | merged as **#312** | done; found **GH#313**, fixed in #319 |
| R5 — agent-attach page + MCP in deployment.md | merged as **#314** | done (was E/D2); found the MCP-auth docs error |
| R6 — status-driven navigation: one vocabulary, clickable card states, a Status column | **GH#336** epic → GH#337–341, merged as #346/#348/#349/#350/#351 | **closed** 2026-08-13; left **GH#352** open |
| 6 — list-header filter syntax: regex, negation, label selector | merged as **#322** | done (was C/T3, client-side) |
| 7 — D3: security findings + the first cross-manifest rule | **GH#353** | done |
| 8 — T5: SSAR affordances, fail-open, never a gate | **GH#354** (open) | P3 |
| 9 — D4: guarded MCP write tools, scope first | **GH#355** (open) | P3 |
| — | **GH#148** (open), **GH#143** (open) | parked by decision — §7 |
| — | **GH#352** (open) | P3 bug from R6 |

Also opened out of R3's verification and since fixed: **GH#320** (Sign out did not invalidate the
`HttpSession`, so after one successful sign-in any password signed back in) — closed by #325.

Superseded from earlier cuts and **not** to be re-filed: A (write-path integrity — done,
#209/#212/#274), B (bounded lists — measured out, #292), I (docs currency — done), and GH#146,
which was an investigation that completed rather than an epic that is waiting.

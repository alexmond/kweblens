# Correctness audit — 2026-08-06, completed 2026-08-07

A six-dimension fan-out in which **every finding was then attacked by an independent
refuter**. Seeded by the `ErrorNotice` defect from #295 — a component used without an import
that passed *both* `vue-tsc` and `eslint` — on the premise that the build gate has a blind
spot and there would be more of that class.

It was run twice. The first attempt (2026-08-06) was killed by a session limit part-way
through; that partial record is kept below as an appendix because several of its findings did
not resurface in the second run and are still open. **The complete run (2026-08-07, 6
dimensions, 36 agents) is the authoritative result and is what this section reports.**

**28 confirmed · 1 refuted.**

> **What "confirmed" means here, and what it does not.** A confirmed finding is one an
> independent refuter tried to break against the source and could not. Most were argued from
> the code; a minority were reproduced by running something (the `strictTemplates` probes were
> executed against a copy of `kweblens-ui`; the shared PREPARE runner was driven with a stub
> page). **None of this is "reproduced in production."** The refuters also narrowed or
> corrected the consequence on a large fraction of findings, so read a severity as the
> refuter's, not the finder's — and re-read the reasoning before acting.

| dimension | confirmed |
|---|---|
| Drift between the server's JSON and the client's types (`contract`) | 6 |
| Code that cannot execute (`dead`) | 5 |
| Error and empty states, roadmap T4 (`error-empty`) | 6 |
| Documentation that the code contradicts (`docs-claims`) | 5 |
| Resources acquired and not released (`resources`) | 5 |
| Templates the type-checker cannot see (`templates`) | 1 |

The `templates` dimension — the one the audit was designed around, and the one whose agent
died in the first run — produced a single finding, duplicated by the `dead` dimension from the
other side: `vue-tsc` runs with `strictTemplates` off, so an unresolved component tag, a
misspelled prop, an unknown event and a non-existent slot name all pass the gate. That is the
seed defect's mechanism, still open.

## The 28

Ordered by severity, then dimension. No finding was rated **high**.

| # | sev | dimension | where | finding |
|---|---|---|---|---|
| 1 | medium | contract | `kweblens-ui/src/api.ts:75` | Four of the client's six request helpers throw away the server's ProblemDetail `detail`, so every apply / create / Helm / add-cluster failure shows a bare status line instead of the cluster's reason |
| 2 | medium | contract | `kweblens-ui/src/components/YamlEditorModal.vue:108` | An RBAC 403 from the *cluster*, which the server labels `code: "cluster-refused"`, is misread as an expired kweblens login and silently signs the operator out |
| 3 | medium | contract | `kweblens-ui/src/podFiles.ts:277` | `allowedRoots` is seeded from an unauthenticated `/api/v1/about` — where the server deliberately sends `[]` — and never re-fetched after sign-in, so a confined file browser opens on `/` and refuses itself |
| 4 | medium | dead code | `kweblens-ui/src/components/MetricChart.vue:79` | MetricChart hands `var(--muted)` / `var(--border)` to an echarts CanvasRenderer, which cannot resolve CSS custom properties — so all four axis colour declarations are silently discarded and the chart's labels and grid lines render pure black (1.34:1) on the default dark theme |
| 5 | medium | dead code | `kweblens-ui/src/styles.css:1123` | The metrics-chart hover tooltip's `.chart-tip` wrapper rule selects a class echarts never emits, so the tooltip keeps echarts' default WHITE background while kweblens styles its text near-white — the hovered value renders at 1.28:1 |
| 6 | medium | dead code | `kweblens-ui/tsconfig.app.json:1` | The front-end gate structurally cannot see an unresolved component tag — the exact defect class of the HelmResourcesModal seed is still shippable, proven by vue-tsc exiting 0 on a template with two undefined components |
| 7 | medium | docs | `README.md:50` | README's entire "suggest → confirm → apply" section states that server-side `dryRun=All` does not exist and that the audit log is lost on restart — both shipped (#274/#209/#212) and both are documented as shipped in CLAUDE.md and roadmap.md, so the two user-facing docs now contradict each other |
| 8 | medium | error/empty | `kweblens-ui/src/api.ts:165` | Every read-path failure discards the server's ProblemDetail, so RBAC denials and unreachable clusters render as a bare status line and URL |
| 9 | medium | error/empty | `kweblens-ui/src/api.ts:82` | Write failures on apply, Helm and port-forward show only "422 Unprocessable Content" — the admission-webhook / Helm reason is parsed by the server and discarded by the client |
| 10 | medium | error/empty | `kweblens-ui/src/components/ClusterOverview.vue:57` | When the events API fails, the cluster overview affirmatively claims "0 Warnings" and "No warnings." — a silent false all-clear on the primary health surface |
| 11 | medium | error/empty | `kweblens-ui/src/composables/useClusterScope.ts:63` | A failed Helm-release-resources fetch silently empties every resource list in the app while the header keeps claiming N items |
| 12 | medium | templates | `kweblens-ui/tsconfig.app.json:25` | vue-tsc runs with strictTemplates off, so component tags, props, events and slot names in every template are entirely unchecked — the exact hole the HelmResourcesModal `<ErrorNotice>` bug went through is still open |
| 13 | low | contract | `kweblens-ui/src/podFiles.ts:72` | The pod-file error table claims to be exhaustive but is missing two codes the server can send, so both degrade to a generic message with a Retry that cannot succeed |
| 14 | low | contract | `kweblens-ui/src/types.ts:147` | `HelmRelease.status` is typed as a required string but the server can send null, and the consumer calls `.trim()` on it — the whole releases table fails to render |
| 15 | low | contract | `…/web/api/ObjectPatchApiController.java:33` | The JSON-Merge-Patch endpoint built for the structured form editor has no client caller — the form applies through full server-side apply with forceConflicts instead |
| 16 | low | dead code | `docs/design/metrics-sources.md:95` | The doc tells operators to configure `kweblens.metrics.prometheus-url`; the shipped binding is `kweblens.metrics.prometheus-service` and it explicitly refuses a URL value, so the documented property binds to nothing and Spring ignores it silently |
| 17 | low | dead code | `…/cluster/ProxyStatus.java:75` | `ProxyStatus.exclusions(String[])` is a public static method with zero callers anywhere in the reactor — main, test, CLI or IT |
| 18 | low | docs | `README.md:97` | README and CLAUDE.md both say the ambient kubeconfig is seeded as cluster `default`; ClusterBootstrap actually registers one cluster per kubeconfig CONTEXT, named by context — `default` is only the no-kubeconfig fallback |
| 19 | low | docs | `README.md:30` | README says the detail drawer has "three relation sections" and names the pre-#220 three; RelationService puts twelve keys in the response and the SPA renders all of them |
| 20 | low | docs | `docs/design/overview-pages.md:82` | An undated present-tense gap list whose seven "fixes, as issues" have all shipped — it tells a reader only Cluster and Workloads overviews exist, the namespace filter is ignored, and the Job/CronJob health predicates are broken |
| 21 | low | docs | `scripts/README.md:243` | Documents a `signin:<password>` PREPARE verb as "the only way to reach a surface gated on being signed in"; the shared runner that `ui-measure.mjs` and `ui-shot.mjs` use has no such verb and throws on it |
| 22 | low | error/empty | `kweblens-ui/src/components/CategoryOverview.vue:57` | CategoryOverview swallows the events error and then hardcodes `:error="null"` on the pane built to display it, rendering "No events." for a failed fetch |
| 23 | low | error/empty | `kweblens-ui/src/components/ResourceTable.vue:294` | ResourceTable has no `#empty` slot, so three unrelated situations all render naive-ui's default "No Data" |
| 24 | low | resources | `…/cluster/ClusterConfigService.java:107` | Adding or editing a cluster builds a KubernetesClient before persisting it, so a failed `store.save()` drops the client — and with it a whole Vert.x instance — on the floor, never closed |
| 25 | low | resources | `…/cluster/ClusterConfigService.java:146` | Removing or re-pointing a cluster never stops that cluster's port-forwards, leaving a bound local TCP listener with no UI path left to release it |
| 26 | low | resources | `…/schema/SchemaService.java:60` | SchemaService caches whole OpenAPI group-version definition maps in a plain ConcurrentHashMap with no size cap, no TTL and no eviction on cluster removal |
| 27 | low | resources | `…/web/search/SearchService.java:222` | Global search and the nav count endpoint keep executing every queued API-server list after the client has aborted, blocking a Tomcat request thread on an untimed `Future.get()` |
| 28 | low | resources | `…/web/api/SseEndpointKeepAliveTest.java:98` | The structural guard against leaking SSE endpoints passes on any class that merely mentions `SseKeepAlive`, so an endpoint that only calls `completeQuietly()` ships the original leak with a green build |

### Corrections the refuters made that the table cannot carry

Several findings survived only in a narrower form. The ones where the difference changes what
you would do:

- **#26 `SchemaService`** — measured at 16.9–17.2 MB for a 38-group-version CRD-heavy cluster,
  with the key space bounded by the nav catalog. **Not an OOM risk.** The real defect is
  *staleness*: the cache is keyed by cluster id alone, so a CRD update or a re-pointed id
  serves the old schema indefinitely.
- **#27 `SearchService`** — the heap claim was refuted and the thread cost is nil under virtual
  threads. The symptom is also inverted from the report: a 250 ms debounce means fast typing
  produces *one* request, so this degrades when you type slowly.
- **#28 `SseEndpointKeepAliveTest`** — not silent today. A sibling assertion pins the streaming
  controller set to exactly four, so the build goes red the day a fifth is written. It is
  fully silent only for a `ResponseEntity<SseEmitter>` return or a plain `@Controller`.
- **#15 `ObjectPatchApiController`** — the endpoint has no SPA caller *by design*; #115 says so
  verbatim. Only the javadoc claiming a caller is wrong.
- **#14 `HelmRelease.status`** — the `status` path is near-unreachable and does **not** blank
  the pane in a production build. The live half is `appVersion`, which renders as an empty cell
  where the sibling charts pane correctly renders `—`.
- **#23 `ResourceTable`** — the error case is *not* conflated with empty (it renders above the
  list) and there is no loading flash. This is roadmap R3/P2, already tracked.
- **#6 / #12 (`strictTemplates`)** — the recommended fix is
  `"vueCompilerOptions": { "checkUnknownComponents": true }`, verified to catch the seed defect
  with **zero** collateral errors. Blanket `strictTemplates: true` first needs ~5 naive-ui
  attribute fall-throughs cleaned up.

The one **refuted** finding was the heap claim attached to #27.

The synthesis narrative ranks these as "26 findings" rather than 28 because it merges two
duplicate pairs: the `strictTemplates` finding is reported once by the `templates` dimension
and once by `dead` (#12/#6), and the two `api.ts` write-helper entries (#1/#9) describe the
same helper family from the `contract` and `error-empty` sides.

## What has been fixed since

Both fixes came out of the **first, partial** run — neither defect recurs in the complete
run's 28, which is consistent with them being gone but is not by itself proof:

| Issue | Finding | Closed by |
|---|---|---|
| GH#297 | Bulk Delete sent nothing for cluster-scoped kinds and swallowed every non-auth failure (recorded below as **unpaired**, i.e. never verified by a refuter) | PR #300, merged 2026-08-07 |
| GH#298 | A zero-cluster install rendered a blank pane and the only page that can add a cluster was unreachable (recorded below as **confirmed**) | PR #301, merged 2026-08-07 |

The six `docs-claims` / doc-drift findings (#7, #16, #18, #19, #20, #21) were corrected in
the docs directly rather than through issues. Everything else in the table above is open.

---

## Appendix — the first, partial run (2026-08-06)

**Superseded by the table above, and kept for two reasons:** several of its findings did not
resurface in the complete run and are still open (the `/audit` URL in `docs/deployment.md`,
`docs/references/freelens-vs-kweblens.md` as an undated gap list, `/actuator/info` being public
under `open-mode=false`, the empty `Watcher.onClose`, `boundClaim` never requested for
cluster-scoped objects, and global search being missing from both README and CLAUDE.md); and
GH#297/#298 were filed from it.

**Its own caveats still apply to everything in this appendix, and only to this appendix:**

- A session limit killed **10 of 37 agents**, including the synthesis stage and the whole
  `templates` dimension.
- The synthesis stage was what joined each finding to its verdict, so the pairing below is
  **reconstructed heuristically** by matching the file a verdict discusses. It is visibly wrong
  in places — some "Correction from the refuter" blocks discuss a different finding than the
  heading above them. Read the refutation text, not the pairing.
- **30 findings · 14 confirmed · 0 refuted · 16 unpaired.** An "unpaired" finding was never
  attacked by a refuter at all and carries no more weight than one agent's reading.

## Appendix — confirmed (2026-08-06 run)

### [CONFIRMED · severity low] With zero registered clusters the entire content area renders blank and the Clusters page — the only place to add one — is unreachable

**`kweblens-ui/src/App.vue:381`** · dimension `Error and empty states (roadmap T4)` · finder confidence high

**Consequence.** Start the container with `KWEBLENS_LOAD_KUBECONFIG=false` intending to add clusters through the UI (or remove your last cluster from the Clusters page): you get a bare shell with an empty grey pane, no message, no error, and no reachable path to the "Add cluster" button. The server logs the explanation; the browser says nothing. Recovery requires restarting with a kubeconfig or POSTing to the API by hand.

**Correction from the refuter.** The consequence is overstated. The claim says the user is led to believe the removal succeeded while the cluster is still there. In fact the failure path never emits `changed`, so `props.clusters` is untouched and the target row is still rendered in the table immediately below the banner — the false "it worked" reading is contradicted by what is on screen, and the genuine retry (the row's Remove popconfirm) is one click away. Nothing is written, lost, or left in an inconsistent state. The accurate defect is a mislabelled control: a button that says Retry performs a dismiss, so a transient failure is silently cleared instead of re-attempted, and the missing `:retrying` means it never shows in-flight state. Fix is one line — either wire it to a real re-attempt (track the failed id and call `removeCluster` again, with `:retrying="!!busy"`), or give ErrorNotice a dismiss affordance and use that here.

<details><summary>Evidence</summary>

Every content surface, including `ClustersPage`, is inside one guard:

```html
<div v-if="error" class="error">{{ error }}</div>
<template v-if="cluster">
  <ClustersPage v-if="showClusters" ... />
  <ClusterOverview v-else-if="showClusterOverview" ... />
```

`useClusters.ts:22` sets `cluster.value = cs[0]?.id ?? null` — with `clusters: []` the fetch *succeeds*, so `error` stays null and `cluster` stays null. `ResourceListView` needs `selected`, which is null because `nav` is empty. `Sidebar.vue` still renders its "All clusters" tile and emits `show-clusters`, which sets `showClusters = true` — but `ClustersPage` is behind `v-if="cluster"`, so clicking it renders nothing. `BrandBar.vue:57` hides its whole toolbar behind `v-if="cluster"`. `commandPalette.ts:54` builds cluster commands by mapping the (empty) clusters array and has no "open Clusters page" command. This is a first-class supported server state — `ClusterBootstrap.java:57` logs `"No clusters registered — set kweblens.clusters[*] or provide a kubeconfig."` — and runtime cluster-add is a shipped feature (`ClusterConfigApiController`).

</details>

<details><summary>Refutation attempt</summary>

Confirmed by reading the code. ClustersPage.vue:71 binds `@retry="error = null"` on a component whose only control is a button hard-labelled `Retry`/`Retrying…` (ErrorNotice.vue has no dismiss variant, and its own comment states Retry is there so the user can re-run the failed request without a full page reload). The branch is live: `error` is set solely in the `.catch` of `api.removeCluster(id)` (ClustersPage.vue:48), reachable whenever a signed-in user removes a runtime-added cluster and the DELETE fails (expired session 401, 5xx). Nothing else handles it — ClusterEditModal owns a separate `error` ref, there is no interceptor or global retry, and the parent App.vue:388 already passes `@changed="refreshClusters"`, so a real recovery call was available and simply not wired. No design decision covers it: docs/design/cluster-selection.md and failure-taxonomy.md contain no mention of retry, dismiss, or ErrorNotice, and the other seven call sites uniformly pass `@retry="reload" :retrying="loading"`. ADR-001 is irrelevant here. I could not break the claim; only its severity.

</details>

---

### [CONFIRMED · severity low] On the cluster dashboard a failed events request renders as "0 Warnings" and "No warnings." — a false all-clear

**`kweblens-ui/src/components/ClusterOverview.vue:57`** · dimension `Error and empty states (roadmap T4)` · finder confidence high

**Consequence.** The first screen an operator sees after picking a cluster states, in the danger card and again in the section body, that the cluster has zero warning events — when in fact kweblens could not read events at all. There is no retry and no way to tell the two apart short of opening devtools. A green dashboard on a cluster that is on fire.

**Correction from the refuter.** The consequence is overstated. Charts fail closed — a wrong or failing backend produces an empty chart, never wrong numbers (metrics-sources.md measured this: mis-matched services 404 through the apiserver proxy). What the operator loses is troubleshooting time to a misleading label, not data or correctness, and there is an existing (though unlinked) path to the truth: the Diagnostics modal names the discovered backend, so a discovered-but-failing backend shows up as a contradiction between the panel and the chart. The accurate statement of the defect is narrower and broader at once: the empty-state text asserts a specific cause ('needs a backend') for a state that also covers a discovered-backend query failure (403/timeout/unparseable JSON), an unresolvable node InternalIP, and an unknown chart target. A neutral message plus a pointer to Diagnostics — or propagating a reason code from MetricSeries — would fix it.

<details><summary>Evidence</summary>

The two requests in the same watch are handled asymmetrically:

```ts
api.objects(cluster, 'nodes')
  .then((n) => my === reqId && (nodes.value = n))
  .catch((e) => my === reqId && (err.value = String(e)));   // reported
api.events(cluster, namespace ?? undefined)
  .then((ev) => my === reqId && (warnings.value = ev.filter((x) => x.type === 'Warning')))
  .catch(() => my === reqId && (warnings.value = []));       // swallowed
```

`warnings.value = []` then drives the headline card at :112-115 (`:value="warnings ? warnings.length : '…'"`, `:danger="!!(warnings && warnings.length > 0)"` → prints `0`, no danger tint) and the section at :147 (`<div v-if="warnings && warnings.length === 0" class="empty">No warnings.</div>`). Nothing server-side softens this: `EventApiController.events` calls `EventService.list` with no catch, so a fabric8 `KubernetesClientException` (an RBAC role that omits `list events` — exactly the scoped read-only role `CLAUDE.md`'s Deployment section recommends — or a timeout) surfaces as a 4xx/5xx `ProblemDetail` and lands in that `.catch`. The file's own header comment claims the page "say[s] so instead of quietly showing unfiltered numbers", and `CategoryOverview.vue:72` carries the rule verbatim: "Could not check" must never render as a healthy zero.

</details>

<details><summary>Refutation attempt</summary>

Could not refute. The branch is reachable (MetricChart renders unconditionally on ClusterOverview.vue:128-129 and Detail.vue:253-261), and 'unavailable' is genuinely the only failure state with a message. PrometheusMetricService.queryRange (:115-118) returns the same MetricSeries.unavailable() for a caught RuntimeException as for no backend at all, and MetricChart's .catch() (:37) collapses transport errors into it too. The collapse is in fact WIDER than the finder said: MetricApiController.graph returns unavailable at :87/:95/:103 when nodeInstanceSelector cannot map a node name to an InternalIP, and at :121 for an unknown target — neither involves the metrics backend, yet both render 'Graphs need a Prometheus / VictoriaMetrics backend'. The strongest counter is docs/design/metrics-sources.md, which measures the wrong-guess case, accepts 'silence, not wrong numbers' as the safe failure direction, and points users at the diagnostics panel — but the doc's model is silence, whereas the component renders an affirmative causal claim the code never established. The Diagnostics modal (DiagnosticsService.prometheusBackend) reports only whether a backend was DISCOVERED, never whether the query succeeded, so in a 403 case it shows a green 'discovered ns/svc:port' beside a chart claiming no backend exists — contradictory rather than corrective. There is also no retry/refresh on the chart: the watch fires only on prop change, so a transient failure sticks until navigation.

</details>

---

### [CONFIRMED · severity medium] The Workloads overview hard-codes its events pane's error to null, so a failed events fetch renders "No events."

**`kweblens-ui/src/components/CategoryOverview.vue:57`** · dimension `Error and empty states (roadmap T4)` · finder confidence high

**Consequence.** On the page whose whole job is "what needs attention in Workloads", a 403 or timeout on events produces the sentence "No events." under a "Recent Events" heading. The page contradicts its own stated principle within one screen, and the operator has no retry and no indication anything failed.

**Correction from the refuter.** The defect is real but the "green dashboard on a cluster on fire" framing is too strong in one case and too narrow in another. (a) In a whole-cluster outage the sibling nodes request also fails, so err renders a visible error banner beside the misleading zero — that case is contradictory, not silent. The true silent false-all-clear is an EVENTS-ONLY failure: an RBAC role without `list events`, or — more likely in practice — the 20s fetchWithTimeout aborting on a large cluster's unpaged cluster-wide events list, which yields a permanent-looking "0 Warnings / No warnings." (b) The operator is not blind overall: node counts, per-category health, and the DiagnosisPanel's deterministic findings still render, so the loss is the Warnings signal specifically, not the whole page. (c) Scope is wider than reported: CategoryOverview.vue:55-57 has the identical `.catch(() => events.value = [])` and hard-codes `:error="null"` on its EventsPane (line 171), so the category overview's events pane silently reads empty on the same failure. The fix is the pattern already used for nodes and already stated at CategoryOverview.vue:72 — keep a separate eventsError ref, render "—"/"unavailable" on the card and an error notice instead of "No warnings.", in both components.

<details><summary>Evidence</summary>

The fetch swallows the failure into an empty array:

```ts
api.events(cluster, ns)
  .then((e) => my === reqId && (events.value = e))
  .catch(() => my === reqId && (events.value = []));
```

and the template (:171) passes a literal null where the error belongs:

```html
<EventsPane :events="recentEvents" :error="null" />
```

`EventsPane.vue` has a working error branch (`<div v-if="error" class="error">`) and its own comment explains why it exists — "An error means the load finished, unsuccessfully — so the table must stop spinning" — but the only caller that could supply one never does. `recentEvents` becomes `[]`, so `EventsPane` falls through to `<template #empty>No events.</template>`. The same page gets this right for the health checks two sections above: `unavailable` kinds are called out at :157-159 with "Could not check: …", under a comment saying "a missing check must not be mistaken for a clean bill of health".

</details>

<details><summary>Refutation attempt</summary>

Could not refute. The code is exactly as quoted and reachable: ClusterOverview is the default pane (App.vue:292). The events failure is not absorbed anywhere upstream — ResourceService.listRaw has no catch, and ApiExceptionHandler.kubernetesRefused deliberately preserves the status (403 stays 403, unreachable cluster maps to 502), so getJson (api.ts:161) throws and the .catch at :57 sets warnings=[]. That empty array drives the "0" headline card with no danger tint (:112-115) and "No warnings." (:147), which is indistinguishable from a genuinely clean cluster. Nothing else on the page covers it: `err` is written only by the nodes .catch, and DiagnoseService does not use EventService at all (only EventApiController and the MCP DiagnosticTools do), so the auto-loading DiagnosisPanel succeeds and can itself read clean, reinforcing the false all-clear. No deliberate-decision defence: ADR-001 is irrelevant here, and the repo states the opposite rule verbatim at CategoryOverview.vue:72 and in this file's own header. The swallow dates to the initial Vue port (6e74264), not a considered later choice. The same pattern also exists at CategoryOverview.vue:55-57, which additionally hard-codes :error="null" on its EventsPane — so it is systemic, not a one-off.

</details>

---

### [CONFIRMED · severity medium] When the resource-list SSE watch dies the only signal is a small pill disappearing; the list then sits silently stale

**`kweblens-ui/src/composables/useResourceData.ts:164`** · dimension `Error and empty states (roadmap T4)` · finder confidence medium

**Consequence.** An operator watching a rollout on the Pods list, or tailing logs through an incident, sees a view that has quietly stopped updating and looks exactly like a view where nothing is happening. The only tell is a 40-pixel "live" pill they were probably not tracking. Recovery requires navigating away and back, which nothing on screen suggests.

**Correction from the refuter.** Two adjustments. (1) Overstated: "no reconnect logic" understates EventSource's built-in retry, which recovers transport-level failures (network blip, server restart, ingress reload) with only a brief pill blink; the unrecoverable set is narrower — a non-2xx or wrong-content-type answer to the retry (a 401 once the session expires under kweblens.security.open-mode=false, an ingress 404/502). A browser reload also recovers, not just navigating away and back. (2) Understated, and the stronger form of the defect: the reconnect path is lossy. flush() (useResourceData.ts:141-151) seeds its map from the current objects.value and only applies incoming events, and a reconnect replays the list as an ADDED burst — so objects deleted during the outage were never DELETED-evented and remain in the list indefinitely, while the "live" pill is back on. The failure mode is therefore not only "silently stale while the pill is off" but also "confidently wrong after the pill returns". On the Pods/Nodes lists the 15 s metrics poll keeps CPU/Memory bars ticking during the dead window, actively reinforcing the impression of liveness.

<details><summary>Evidence</summary>

The watch's entire error handling is one assignment:

```ts
es.onopen = () => (live.value = true);
es.onerror = () => (live.value = false);
```

and `live` drives only `ResourceListView.vue:49`:

```html
<span v-if="live" class="live" title="Live-updating (SSE watch)"><span class="dot" /> live</span>
```

so the failure state is the *absence* of a badge — never a message, never a retry, and no visual difference from a page whose watch has not connected yet. There is no reconnect logic beyond `EventSource`'s built-in retry, which stops permanently when the server answers the retry with a non-2xx or a wrong content type (a 401 after the session cookie expires, a 404/502 from an ingress). The multiplexed log stream has the mirror-image hole: `useMultiLogs.ts:197-201` sets its error message only `if (!cancelled && sources.value.length === 0)`, i.e. only when the stream fails *before* it ever attached, so a stream that dies mid-follow leaves the log pane showing its last lines with nothing to say the follow stopped.

</details>

<details><summary>Refutation attempt</summary>

Verified against the source and could not refute it. useResourceData.ts:163-164 is the complete error handling for the resource-list SSE watch, and `live` has exactly one consumer (ResourceListView.vue:49, a conditional pill). There is no global connection banner, no visibilitychange/focus refetch, no polling of `objects` (the only setInterval, line 110, refreshes pod/node *metrics* only), and no refresh action in the command palette or useAppActions.ts — so recovery really does require re-navigating or reloading, with nothing on screen suggesting it. Nothing in docs/design/roadmap.md or ADR-001 sanctions this; the rest of the app uses an ErrorNotice+Retry pattern (Helm panes, DiagnosisPanel, PodFiles, ClustersPage), so this path is the inconsistent one. The useMultiLogs.ts:197-201 mirror hole is confirmed too: the per-source error/gone markers are driven by server-sent `source-error`/`source-recovered` events, which cannot fire once the stream itself is dead. Severity is medium rather than high because EventSource's built-in retry does heal the common transient failures, and the loss is a misleading view rather than data loss, a write failure, or a security hole.

</details>

---

### [CONFIRMED · severity medium] The Clusters page wires ErrorNotice's Retry button to dismiss the message instead of retrying

**`kweblens-ui/src/components/ClustersPage.vue:71`** · dimension `Error and empty states (roadmap T4)` · finder confidence high

**Consequence.** A cluster removal or edit fails (e.g. an expired session returning 401). The user clicks the button labelled Retry; the red banner vanishes instantly and no request is made. The most likely reading of an error that disappears on "Retry" is that the retry succeeded — while the cluster is still there and the operation never re-ran.

**Correction from the refuter.** The mechanism and the blank content pane are exactly as described, but two details of the stated consequence are wrong. (1) Removing your last cluster from the Clusters page does NOT blank the page: `useClusters.refresh()` assigns `cluster` only when it is already `null`, so after the delete the now-stale cluster id keeps `v-if="cluster"` truthy and `ClustersPage` stays on screen with its "No clusters configured." empty state and Add button. The blank shell appears on the NEXT load/reload (or on a fresh start) with zero clusters — the remembered id no longer matches any cluster, so `cluster` falls back to `cs[0]?.id ?? null` = null. (2) `BrandBar` does not hide its whole toolbar — only the namespace and Helm filters are behind `v-if="cluster"`; the theme toggle, the diagnostics button and Sign in still render, as does the sidebar rail and the footer. So the accurate statement is: the shell renders but the entire content pane is empty (measured: `main.content` has 0 child elements), with no message and no error, and clicking the rail's "All clusters" tile does nothing — leaving no in-UI path to add a cluster until one exists.

<details><summary>Evidence</summary>

```html
<ErrorNotice v-if="error" :message="error" @retry="error = null" />
```

`ErrorNotice.vue` renders a button labelled `Retry` (or `Retrying…` when `:retrying` is set) and documents its own contract: "Retry exists because a timeout is the one error a user can act on immediately." This is the only one of the eight call sites that binds `@retry` to something other than a reload — the seven others (`HelmReleasesPane`, `HelmChartsPane`, `HelmRepositoriesPane`, `HelmResourcesModal`, `HelmHistoryModal`, `DiagnosisPanel`, `FileNotice`) all pass `@retry="reload"` plus `:retrying="loading"`. Here `:retrying` is also omitted, so the button never shows in-flight state — because nothing is ever in flight.

</details>

<details><summary>Refutation attempt</summary>

Verified by reading and by running it. `App.vue:381` wraps every content-pane surface — including `ClustersPage`, the only place to add a cluster — in `<template v-if="cluster">`, and `useClusters.ts` leaves `cluster` null when the (successful) `/api/v1/clusters` fetch returns `[]`, so `error` is never set either. I started the built jar with `KWEBLENS_LOAD_KUBECONFIG=false` (API returned `[]`; the log printed "No clusters registered — set kweblens.clusters[*] or provide a kubeconfig."), loaded the SPA under Playwright, and measured `main.content`: `childElementCount: 0`, empty text, both on load and after clicking the rail's "All clusters" (`···`) tile, which does emit `show-clusters` and set `showClusters = true`. Whole-page text is just the brand bar, the `···` tile, `—`, Collapse and the footer. Refutation attempts all failed: there is no `v-else`/empty-state branch in the content column, `ClustersPage` is rendered in exactly one place (inside the guard), there is no router or `/clusters` URL, `useClusterScope` early-returns on a null cluster so nothing sets `error`, and nothing in CLAUDE.md, ADR-001 or docs/design treats this as a decision. The opposite: `clustersPage.ts` ships `summarise([]) === 'No clusters configured.'` and `ClustersPage.vue` has an Add button — an empty state deliberately written for a case the guard makes unreachable. Zero clusters is a first-class server state (ClusterBootstrap logs it) and runtime cluster-add is a shipped feature (ClusterConfigApiController), so the branch is reachable in normal use: a container started without a kubeconfig/service account, or one whose ambient kubeconfig fails to register. Severity is medium rather than high: nothing is lost or corrupted, the failure is confined to the zero-cluster state (most deployments have an in-cluster SA or a mounted kubeconfig), and recovery exists via POST to the cluster-config API or a restart — but in that state the app looks entirely broken with no on-screen explanation and the advertised UI onboarding path is unusable.

</details>

---

### [CONFIRMED · severity low] README's write-path section still says server-side dry-run does not exist, three PRs after it shipped — it tells users the Review-Changes diff cannot catch admission rejections, and points at a roadmap item that no longer exists

**`README.md:50`** · dimension `Documentation that the code contradicts` · finder confidence high

**Consequence.** The README is the first thing a prospective user reads, and it tells them the product's headline safety feature is absent. Someone evaluating kweblens for a production cluster reads "the diff cannot show … an admission-webhook rejection" and either rejects the tool or, worse, keeps using it while distrusting the live diff the editor is actually showing them. It also sends a reader to docs/design/roadmap.md for "the top item" that reads DONE there — the two user-facing documents disagree in the same repo.

**Correction from the refuter.** The drawer does show all twelve joins today — but the nine newer ones render through the generic fallback in relations.ts (humanised heading plus a Name/Kind/Namespace table), not through purpose-built sections; only endpoints/selectedPods/mountedBy have dedicated titles and projections. So "three" coincidentally matches the count of specially-rendered sections while being wrong about what the drawer can answer. Nothing is broken, hidden, or mis-rendered — the loss is documentation accuracy and discoverability in the primary user-facing doc (a reader comparing per-kind depth against Freelens/Headlamp undervalues a shipped capability), not any functional or data loss.

<details><summary>Evidence</summary>

README.md:50-61 states: "**YAML apply/patch** shows a Review-Changes diff of *your edit against what was loaded*, not against what the server would produce. `apply` is server-side apply with `forceConflicts()`, and **nothing is sent to the API server with `dryRun=All`**, so the diff cannot show defaulting, another controller's fields, or an admission-webhook rejection." and "**Remediation** previews are a written description of the intended change (\"pod 'x' would be deleted and recreated by its owner\"), **not a server round-trip**." and "**The audit log is an in-memory ring of the last 500 entries.** It is queryable while the process lives and is **lost on restart**" and "Closing the first three (real `dryRun=All`) and the last (durable audit) is **the top item on the roadmap**."

All four are false against the code:
- kweblens-core/.../resource/ResourceService.java:266-270 — `public String dryRunApply(...) { ... client.resource(forApply(yaml)).dryRun().forceConflicts().serverSideApply(); ... }`, plus `dryRunPatch` at :449.
- kweblens-web/.../api/YamlApiController.java:68-72 — `@PostMapping(value = "/api/v1/clusters/{clusterId}/apply/dry-run", ...)` returning `resources.dryRunApply(...)`.
- It is wired end-to-end in the UI: kweblens-ui/src/api.ts:476 `applyDryRun`, consumed at kweblens-ui/src/components/YamlEditorModal.vue:61 `requestServerPreview((m) => api.applyDryRun(props.cluster, m), asked)` — the second, live-vs-would-be diff.
- kweblens-web/.../ai/RemediationService.java:147 — `String result = resources.dryRunPatch(clusterId, descriptor(workload), namespace, workload.name(), patch);` for the two patch-shaped actions, with `.notChecked(...)` (:135, :138) naming the reason for the other two.
- kweblens-web/.../security/AuditService.java:53-61 — `AUDIT_LOGGER = "kweblens.audit"` / `LoggerFactory.getLogger(AUDIT_LOGGER)`; every entry is written to a durable log category as well as the 500-entry ring.
- docs/design/roadmap.md:57 records this as "**T1 … DONE**" and §5 ranks R1 (heap/GH#293) and R2 (cut a release) as the actual top items.

Git history confirms the direction of the staleness rather than the reverse: CLAUDE.md:209-221 was rewritten in 2a7216a (PR #295) to describe the shipped behaviour correctly ("two thirds of it has since been fixed", "The YAML editor now shows two diffs (#274)"), and that same commit touched README.md — but only its module table, leaving this block untouched.

</details>

<details><summary>Refutation attempt</summary>

Confirmed and could not be refuted. README.md:30 states the detail drawer has "three relation sections" and names the pre-#220 three (Service endpoints, selected pods, ConfigMap/Secret mounters). RelationService.relationsFor() puts twelve keys into the response map (boundClaim:103, ownedBy:106, endpoints:115, routedBy:116, selectedPods:119, mountedBy:122, boundVolume:126, serviceAccount:129, grantedBy:132, replicaSets:140, autoscaledBy:143, disruptionBudgets:146) over five resolvers. The code is reachable: kweblens-ui/src/components/relations.ts renders every key in the envelope via `TITLES[key] ?? humanise(key)` and `(PROJECTIONS[key] ?? genericRows)(relation.items)`, which is exactly why #220 was backend-only. Not deliberate: the README row was last touched by 091d9e1 (docs-currency sweep, Jul 31 2026), one day BEFORE c6c0919 shipped the ten extra joins (Aug 1 2026) — stale, not a considered scope statement. Not corrected elsewhere: README:128 mentions "relation sections" with no count; roadmap.md:62/:145/:165/:180 all say twelve, so the correction exists only in the internal design doc. CLAUDE.md's standing rule about keeping the MCP tool count correct in the README establishes that counts there are treated as load-bearing. Minor evidence flaw only: the quoted line shows bold "**three relation sections**" but the file has it unbolded.

</details>

---

### [CONFIRMED · severity low] README says the detail drawer has "three relation sections" and names them; RelationService resolves twelve — the exact understating class PR #295 flagged in the roadmap, left uncorrected in the user-facing doc

**`README.md:30`** · dimension `Documentation that the code contradicts` · finder confidence high

**Consequence.** The README undersells the drawer by 9 of 12 joins. A reader comparing kweblens against Freelens/Headlamp on "per-kind depth" — the review's #5 ranked disqualifier — sees three Service/ConfigMap joins and concludes the drawer cannot answer "what owns this pod", "what autoscales this", "which ServiceAccount does this pod run as", "what PV is this PVC bound to", or "what PDB covers this", all of which ship today.

**Correction from the refuter.** The core fact is right: /audit was deleted with the classic Thymeleaf UI in 0c31336 (#125), there is no catch-all or resource-handler fallback in kweblens-web, the Vue SPA has no router and no audit view, so GET /audit 404s. AuditApiController.java:15's own javadoc states the page "went away with the Thymeleaf UI", confirming this is stale prose rather than a design decision. The consequence is milder than stated, though: the same sentence in deployment.md:154 also names `GET /api/v1/audit`, which works, so the operator is pointed at a dead URL rather than left with no route; and the section's headline advice for "who changed what, when" is `grep kweblens-audit` against the durable `kweblens.audit` logger, which is correct. What the operator actually loses is a confusing 404 plus a doc implying an in-app audit view the product does not have. The fix should also cover three other repetitions of the dead claim: AuditService.java:34, application.yml:164, and the stale javadoc on AuditEndpointsTest.java:19-20 (which describes a "/audit (page)" the test never asserts). README.md is already correct and needs no change.

<details><summary>Evidence</summary>

README.md:30: "| **Detail drawer** | Per-kind detail from a server-side endpoint, including **three relation sections**: a Service's endpoints, the pods a Service selects, and which pods mount a ConfigMap or Secret |"

kweblens-core/.../resource/RelationService.java puts twelve keys into the response map: `boundClaim` (:103), `ownedBy` (:106), `endpoints` (:115), `routedBy` (:116), `selectedPods` (:119), `mountedBy` (:122), `boundVolume` (:126), `serviceAccount` (:129), `grantedBy` (:132), `replicaSets` (:140), `autoscaledBy` (:143), `disruptionBudgets` (:146) — dispatched over five resolvers (`NetworkRelations`, `ReferenceRelations`, `WorkloadRelations`, `StorageRelations`, `AccessRelations`, all present in the same package).

docs/design/roadmap.md:62 already records the correction — "**D1** relations breadth | **SHIPPED** | `RelationService` is now a dispatcher over five resolvers … resolving **12** relation keys, up from 3 (#220)" — and PR 2a7216a's own commit message lists "D1 SHIPPED (#220): RelationService went 3 relations -> 12 … The roadmap still described three." The roadmap was fixed; the README was not.

</details>

<details><summary>Refutation attempt</summary>

Verified and could not refute. (1) Reachability: SpaController.java:16 maps only {"/", "/ui", "/ui/"} with no catch-all; there is no ErrorController, no addResourceHandlers (the sole WebMvcConfigurer in kweblens-web is JhelmRestIntegrationConfig's deliberate no-op), and no static/template file named audit*. GET /audit reaches no handler and 404s. (2) Not deliberate — the code itself records the removal as an unfinished loose end: AuditApiController.java:15 javadoc says "The server-rendered /audit page went away with the Thymeleaf UI; the trail stays readable here so recorded actions are not write-only." So the deletion was acknowledged in code but the operator doc was never updated. (3) No SPA replacement: kweblens-ui has no router file at all, and grep -ri audit over kweblens-ui/src returns only the three unrelated hits the finder listed; api.ts never calls /api/v1/audit. (4) Timeline confirmed: git log -S'"/audit"' shows 961c4f8 added the page and 0c31336 (#125) removed it; docs/deployment.md was last edited afterwards in b6c448e (#210/#212) and the stale sentence survived. (5) Blast radius is broader than the single doc line — the same dead claim is repeated in AuditService.java:34, application.yml:164, and AuditEndpointsTest.java:19-20 (a javadoc promising a page the test does not assert). Refutation attempts that failed: no Spring default forwards unmapped paths to the SPA here; no ingress/chart rewrite (no charts/ dir with path rewrites in this repo); ADR-001 is about identity, not audit surfacing, so it does not sanction this. The one thing the finder overstated is the consequence: the same sentence names the working GET /api/v1/audit, and the section's primary recommended answer to "who changed what" is grepping the durable kweblens.audit log line, which is fully accurate. So this is a stale-URL doc defect costing a 404 and a phantom UI capability, not loss of access to the audit trail.

</details>

---

### [CONFIRMED · severity low] docs/deployment.md tells operators the audit trail is "shown at /audit"; that page was deleted with the classic UI eight months of commits ago and the Vue SPA has no audit view at all — the URL 404s

**`docs/deployment.md:154`** · dimension `Documentation that the code contradicts` · finder confidence high

**Consequence.** An operator following the deployment guide to answer "who changed what" browses to https://kweblens.example/audit and gets a 404 with no hint that the feature moved or vanished. Since there is no audit view anywhere in the SPA, the doc also implies a UI capability the product does not have — the only way to read the ring is to curl `/api/v1/audit` by hand.

**Correction from the refuter.** The observation is correct — `clusterStore` is computed by the server, is absent from `AboutInfo` (types.ts:257), and is rendered nowhere in `DiagnosticsModal.vue`; the per-cluster alternative `api.clusterConfig()` (api.ts:287) has no callers either, so the store backend and its restart-survival never reach the UI. But the consequence is overstated on three counts. (1) `persistent: false` only occurs when the operator has explicitly set `kweblens.cluster-store.mode=memory` — `ClusterStoreConfig` resolves the default `auto` to `secret` in-cluster or `file` otherwise, and even the Secret-store failure path falls back to `FileClusterStore`, all reporting `persistent: true`. So "the clusters you add here will be gone after a restart" is an opt-in state chosen by the same person who would read the panel, not a hidden trap. (2) `docs/deployment.md:70-71` already documents `GET /api/v1/about` under `clusterStore` as the way to see the active backend and whether it survives a restart, so reading it from the API is the documented path rather than a workaround forced by a bug. (3) Rendering the field would not actually answer "do my clusters survive a restart": `FileClusterStore` inherits the interface default `persistent() == true` regardless of whether its path is a mounted volume or a pod's ephemeral filesystem, so an in-cluster deployment without a PVC reports `true` and still loses its runtime clusters. This is therefore a small completeness gap in a read-only panel (one dl row never added when #198 extended the endpoint after #132 built the modal), not a defect: nothing renders wrong, no type error occurs, and no data is lost. Any fix should type `runtimeClusters` as `number | string` (the server returns the string "unavailable" on a store read failure) and should probably also tighten `FileClusterStore.persistent()` before the value is presented as an authoritative durability claim.

<details><summary>Evidence</summary>

docs/deployment.md:154-155: "- **In memory**, the newest 500, **shown at `/audit`** and `GET /api/v1/audit`. That is a live view only…"

No such route exists. The only SPA mapping is kweblens-web/.../web/ui/SpaController.java:16 — `@GetMapping({ "/", "/ui", "/ui/" })` — with no catch-all and no `addResourceHandlers`/`WebMvcConfigurer` fallback anywhere in kweblens-web (grep for `addResourceHandlers` returns only jhelm's access-mode no-op in `JhelmRestIntegrationConfig`). `GET /audit` therefore reaches no handler.

The page genuinely existed and was removed: `git log -S'"/audit"' -- kweblens-web/src/main/java` gives `961c4f8 feat: surface the audit trail at /audit` then `0c31336 Remove the classic (Thymeleaf) UI and its now-dead dependencies (#125)` (Wed Jul 29 2026). docs/deployment.md was last edited **after** that, on Fri Jul 31 (`b6c448e … (#212)`), and the stale sentence survived the edit.

Worse, the SPA has no audit surface at all to redirect them to: `grep -ri audit kweblens-ui/src` returns only unrelated matches (`parseSummary('**Audit** the manifests')` in diagnosis.test.ts, a `#24 audit` code comment in overview.ts, a docs path in schemaForm.ts). `api.ts` never calls `/api/v1/audit`. The same dead claim is repeated in a code comment at kweblens-web/src/main/resources/application.yml:164 ("the in-app /audit view is only the newest 500, in memory").

</details>

<details><summary>Refutation attempt</summary>

Verified and could not refute the core fact. DiagnosticsService.appDiagnostics (kweblens-web/.../web/diag/DiagnosticsService.java:279-285) emits clusterStore{backend,persistent,runtimeClusters}; kweblens-ui/src/types.ts:257-279 AboutInfo has no clusterStore member; DiagnosticsModal.vue:76-126 renders only Version/Clusters/Ambient kubeconfig/Security/Admin user/AI/Simulator. grep for clusterStore over kweblens-ui/src returns zero hits. api.about() has exactly one caller (the modal), and the per-cluster fallback api.clusterConfig() (api.ts:287, ClusterConfigView with storage+persistent) has NO callers, so nothing else surfaces it. Cause is drift, not design: the modal last changed in cb9a341 (#132) while clusterStore arrived later in 293ee51 (#198); the modal already renders auth-gated "(sign in to see)" values (adminUsername), so there was no barrier, and no ADR/CLAUDE.md/docs rationale for omitting it. runtimeClusterCount() does return Object (Integer or the string "unavailable"), as claimed.

Refutations that partially landed and cut the severity: (1) persistent:false only arises when the operator explicitly sets kweblens.cluster-store.mode=memory — ClusterStoreConfig resolves auto to secret in-cluster / file otherwise, and even the Secret-store failure path falls back to FileClusterStore, all persistent — so the alarming case is self-inflicted and already known to whoever set it. (2) docs/deployment.md:70-71 documents GET /api/v1/about as the reporting channel for the backend and its restart-survival, so reading the JSON is the documented path. (3) The field would not even answer the question reliably if rendered: FileClusterStore uses the interface default persistent()==true whether or not the path is a mounted volume, so a pod without a PVC reports true and still loses its clusters. (4) AboutInfo is a consumption subset by convention, not a wire mirror (podFiles.ts:266-277 declares its own local structural type for the same endpoint), so this is not a type-safety violation — nothing renders wrong, nothing throws. (5) The Clusters page (clustersPage.ts:31-38) already distinguishes STATIC vs RUNTIME and explains config ownership.

So: a genuine UI content gap, but a missing-feature/incompleteness with no malfunction and no incorrect value shown.

</details>

---

### [CONFIRMED · severity medium] docs/references/freelens-vs-kweblens.md is a prioritized gap analysis carrying no staleness marker that lists roughly a dozen shipped features as MISSING — and competitor-analysis.md cites its ranking as current leverage

**`docs/references/freelens-vs-kweblens.md:13`** · dimension `Documentation that the code contradicts` · finder confidence high

**Consequence.** Anyone — human or coding agent — who opens docs/ looking for "what is missing" finds a confident, prioritized, undated list whose top recommendation ("Tier 1.1 + 1.2 are the highest-leverage items") is to build things that already exist. In a harden-and-ship phase this is the most expensive kind of wrong doc: it manufactures a feature backlog out of shipped code and directly contradicts the roadmap's "There is no large missing feature" (roadmap.md:15).

**Correction from the refuter.** The consequence is overstated on the user side. The palette's input placeholder (kweblens-ui/src/components/CommandPalette.vue:166) already reads "Search objects, switch cluster, or jump to a resource…", so a user who presses Ctrl-K is told at the point of use that it finds objects — discovery costs one keystroke, not a lost feature. The accurate harm is threefold and narrower: (a) README:39 now understates the product relative to its own UI string, so a prospective user evaluating kweblens before install sees "jump to a kind" and not the answer to roadmap T3; (b) the API table, explicitly framed as "stable enough to script against", omits a real stable GET endpoint, and the :138 catch-all enumerates Helm/metrics/logs/port-forward/node/pod-file endpoints without naming /search; (c) the durable, unmitigated cost is CLAUDE.md's 12-of-13 slice inventory — an agent has no placeholder string to rescue it, and a list careful enough to name web/sim/ and web/diag/ implies no search slice exists, inviting a duplicate object-lookup path in another slice. This also breaks the repo's own stated standard, applied in CLAUDE.md to the MCP tool count: "Keep the count in this file and in the README correct when you add one." No functional defect and no data loss.

<details><summary>Evidence</summary>

The document presents itself as current — line 6: "Baseline — **kweblens today**: one **generic table (Kind / Namespace / Name / Status / Age)** for every kind" — and line 13 opens "## Consolidated gap analysis (prioritized)" with "The single biggest finding: **kweblens shows one generic 5-column table for everything**". There is no "as of <date>" banner anywhere in its 242 lines.

Its Tier 1/2/3 items are shipped:
- Tier 1.2 "**CRD `additionalPrinterColumns`** … currently all CRs collapse to the generic 5" — shipped: `GET /api/v1/clusters/{clusterId}/resources/{resourceId}/columns` (ObjectApiController/CountApiController package) and README.md:26 "rendered with the CRD's own printer columns".
- Tier 2.5 "Add missing kinds to the nav: ReplicationControllers; ResourceQuotas, LimitRanges, HPA, PodDisruptionBudgets, PriorityClasses, RuntimeClasses, Leases, Mutating/ValidatingWebhookConfigurations, ValidatingAdmissionPolicies (+Bindings); EndpointSlices, Endpoints, IngressClasses, NetworkPolicies" — every one of those is in NavCatalog.java:31-102 today (39 kinds / 7 categories, which I counted line by line).
- Tier 2.6 "**Overview dashboards** … MISSING — no cluster overview/dashboard at all" (also line 76) — shipped: `@GetMapping("/api/v1/clusters/{clusterId}/overview/{category}")` in OverviewApiController.
- Tier 3.9 "**Port forwarding** … **absent entirely**" — shipped: `/api/v1/clusters/{clusterId}/port-forwards` (PortForwardApiController).
- Tier 3.10 "**Terminal dock** (persistent, multi-tab) — kweblens Exec is per-pod only" — shipped: the dockable multi-tab pane (kweblens-ui/src/dock.ts, `/ws/exec`).
- Tier 3.8 "**Helm Charts browser** + install/upgrade/rollback (kweblens has deployed releases only)" — shipped: HelmActionApiController install/upgrade/rollback/uninstall with real `dryRun`.

`git log` on the file ends at `78821f9 Sun Jul 26 2026`, i.e. before nearly all of this landed. It is not merely orphaned: docs/competitive-review/competitor-analysis.md:790 still cites its ranking as live guidance ("turns ~40 of 57 views from 'name + age only' into useful lists"), and :1022 lists it under "Internal references". The roadmap explicitly hangs a staleness warning on competitor-analysis.md (roadmap.md:8-9, "it is a dated snapshot and still describes a 3-tool MCP server") but says nothing about this file, so nothing in the repo warns a reader off it.

</details>

<details><summary>Refutation attempt</summary>

Verified and could not refute. (1) The slice is live, not dead: SearchApiController is a component-scanned @RestController mapping GET /api/v1/clusters/{clusterId}/search; CommandPalette.vue:101-108 calls it on every query with no feature flag (shouldSearch is only a min-length-2 guard, per globalSearch.test.ts:51-53). (2) Not deliberate: the shipping commit c1d2044 (#263) touched exactly one .md file — .claude/skills/playwright/SKILL.md — and no README/CLAUDE.md/ADR/gotcha defers documenting it. Compounding it, docs/design/roadmap.md:66 records "Chore I docs currency | DONE", so the staleness is unlikely to be caught. (3) Nothing else covers it: `grep -i search README.md` returns exactly one line (39, the palette row, which says only "switch cluster or jump to a kind"); `grep -i search CLAUDE.md` returns only line 362, about the Maven Central search index. freelens-vs-kweblens.md mentions search only in describing Freelens's per-view boxes. (4) No misread: CLAUDE.md:121-139 is an exhaustive inventory that names even minor slices (web/sim/, web/diag/, web/ui/) — 12 named vs 13 on disk, with `search` the only omission. The README API table (119-136) omits /search and the :138 catch-all names Helm/metrics/logs/port-forward/node/pod-files but not search. The only partial refutation: the palette's own placeholder (CommandPalette.vue:166) reads "Search objects, switch cluster, or jump to a resource…", so end users are told at the point of use — which trims the user-facing half of the consequence but simultaneously proves the README now says less than the product's own UI string.

</details>

---

### [CONFIRMED · severity medium] Global object search — a whole web slice with its own endpoint — is invisible in both README and CLAUDE.md: the palette is described as only switching clusters and jumping to kinds, the API table omits /search, and CLAUDE.md's slice inventory lists 12 of the 13 packages

**`README.md:39`** · dimension `Documentation that the code contradicts` · finder confidence high

**Consequence.** Two different audiences lose. A user reads that Ctrl-K jumps to a *kind* and never discovers they can type a pod or Secret name and land on the object — the single fastest path in the UI, and the answer to "finding a specific object is weak" (T3). And a coding agent working from CLAUDE.md's slice inventory does not know `web/search` exists, so it will either reimplement object lookup or add a competing search path in another slice, which is exactly the drift that inventory exists to prevent.

**Correction from the refuter.** The claim holds, but two of its four sub-points are partly rather than wholly false, and the stated consequence should be narrowed. Accurate version: README.md:50-53 is flatly wrong — the editor does send dryRun=All and does show a live-vs-would-be diff. README.md:54-55 (remediation) is half-stale: scale-up and rollout-restart are genuinely server-validated via dryRunPatch, but restart-pod and rollback still have no round-trip — they now return an explicit notChecked("…cannot be validated by a server-side dry run") rather than the prose the README quotes. README.md:56-58 (audit) is half-stale: the queryable /api/v1/audit view IS still the volatile 500-entry ring and does reset on restart, so only "lost on restart" as a claim about the record is wrong — entries are also written to the durable kweblens.audit logger. README.md:60-61 is wrong outright: roadmap.md:57 records T1 as DONE and §5 ranks R1/R2 as top. Consequence: no user is put at risk — the error understates the product, so the realistic harm is an evaluator dismissing a shipped safety feature or distrusting the real diff the editor shows, plus two user-facing docs contradicting each other. Fix: rewrite the four bullets to say apply/patch takes a real dryRun=All second diff; remediation is server-validated for the two patch-shaped actions and says so explicitly for the other two; audit is durable in the kweblens.audit log with a volatile 500-entry live view; and drop or repoint the "top item on the roadmap" sentence.

<details><summary>Evidence</summary>

README.md:39: "| **Command palette** | `Ctrl`/`⌘`-`K` to switch cluster or **jump to a kind** |" — no mention that the palette finds actual objects. README's API table (lines 119-136) lists 15 paths and omits `/search` entirely; the catch-all sentence at :138 ("Helm, metrics, multi-source logs, port-forward, node and pod-file endpoints live under the same `/api/v1` prefix") does not name it either.

CLAUDE.md:121-133 enumerates the app's slices — "`web/api/` … `web/ui/` … `web/security/` … `web/mcp/` … `web/nav/` … `web/helm/` … `web/exec/` … `web/files/` … `web/diag/` … `web/ai/` … `web/sim/` … `web/config/`" — twelve. `ls kweblens-web/src/main/java/org/alexmond/kweblens/web/` returns thirteen: ai, api, config, diag, exec, **search**, files, helm, mcp, nav, security, sim, ui.

The slice is real and wired end to end:
- kweblens-web/.../api/SearchApiController.java:43 — `@GetMapping(value = "/api/v1/clusters/{clusterId}/search", ...)`.
- kweblens-web/.../search/SearchService.java:29 — "Global search: find an object by name across kinds and namespaces, without already knowing which of the cluster's ~120 kinds it lives in."
- kweblens-web/.../search/SearchKinds.java:31-46 — 13 searched route ids (11 `PRIMARY` + `GENERATED` = `"pods", "jobs"`), with the un-searched remainder reported by name.
- kweblens-ui/src/api.ts:433-441 `search:` → `${clusterBase(cluster)}/search?…`, consumed in kweblens-ui/src/components/CommandPalette.vue:102/113 via `shouldSearch(q)` and `objectCommands(result.value?.hits ?? [])`.

docs/design/roadmap.md:59 records it as shipped ("Palette now indexes **objects**: `web/search/SearchService` over 13 kinds, ranked, reporting what it did not search … (#263)"), so the roadmap knows and the two user-facing docs do not.

</details>

<details><summary>Refutation attempt</summary>

Confirmed against the code on every refutation angle. (1) Reachable, not dead: ResourceService.dryRunApply:266 / dryRunPatch:449 both send dryRun; YamlApiController:68-71 exposes POST /api/v1/clusters/{id}/apply/dry-run; api.ts:476 applyDryRun is called from YamlEditorModal.vue:61 via a tab watcher (:70-73) that fires whenever the Review Changes tab opens, and the rendered second diff is at :196. The only gate is `readonly`, which also hides Apply — so the preview exists in exactly the states where a write is possible. (2) Not a deliberate position: docs/design/roadmap.md:57 marks T1 "DONE" citing these same symbols and PR #274, and §5 ranks R1 (GH#293 heap) and R2 (cut a release) as the top items — so README:60's "top item on the roadmap" points at an item that reads DONE where it points. CLAUDE.md:209-221 was rewritten in 2a7216a (#295) to describe the shipped behaviour; that commit touched README.md but only its module table. (3) Nothing else corrects it — no later README text qualifies the block. (4) Not a misread: RemediationService.preview:129-146 routes scale-up and rollout-restart through dryRunPatch and returns notChecked(...) for the other two; AuditService:53-61 writes every entry to the dedicated durable `kweblens.audit` logger as well as the 500-entry ring. Severity is medium, not high: this is documentation staleness with zero runtime effect, and it errs conservative — it understates a shipped safety feature rather than claiming a guarantee the product lacks, so no reader is led into an unsafe action. What is actually lost is accuracy about the headline write-path safety story plus a self-contradiction between two user-facing docs in the same repo.

</details>

---

### [CONFIRMED · severity low] Both README and CLAUDE.md promise that with open-mode=false "everything but health and the login page needs auth"; SecurityConfig also permits /actuator/info unauthenticated, with git mode full

**`README.md:188`** · dimension `Documentation that the code contradicts` · finder confidence medium

**Consequence.** An operator who sets open-mode=false because the README told them that closes everything still ships an unauthenticated endpoint disclosing the exact commit the deployment was built from — which is what an attacker uses to map a running instance to a known-vulnerable revision. The leak is modest; the doc being wrong about the boundary is the defect, because the whole point of that sentence is to let someone reason about what is exposed without reading SecurityConfig.

**Correction from the refuter.** The doc sentence is imprecise — with open-mode=false, /actuator/info, /error, /, /ui and /ui/** are also public, not just health and login. But the leak is version metadata only: kweblens-web never gets a git.properties (git-commit-id runs only under -Prelease, which drops kweblens-web from the reactor; the built classes confirm no git.properties), so `info: git: mode: full` is inert and /actuator/info returns just build.group/artifact/name/version/time. No commit id, branch, or commit time is disclosed. The endpoint is deliberately public — the SPA footer (AppFooter.vue, api.ts) reads it and both call it "public", and it was permitted in the original security PR #8 — and the version it shows is already rendered by the publicly served /ui shell. The correct fix is to tighten the README/CLAUDE.md wording (e.g. "everything except health, the SPA shell, /actuator/info and the login page"), not to change SecurityConfig.

<details><summary>Evidence</summary>

README.md:188-189: "- **`kweblens.security.open-mode=false`** — **everything except health and the login page** requires authentication." CLAUDE.md:195 repeats it verbatim: "with `kweblens.security.open-mode=false` everything but health and the login page needs auth."

kweblens-web/.../security/SecurityConfig.java:72-73 permits five path families unconditionally, before the `openMode` branch at :90:

  auth.requestMatchers("/actuator/health/**", "/actuator/info", "/login", "/error", "/", "/ui", "/ui/**")
    .permitAll();

`/actuator/info` is neither health nor the login page. It is exposed (application.yml:148-149, `include: health,info,metrics,prometheus`), it is configured for maximum detail (application.yml:154-156, `info: git: mode: full`), and kweblens-web/pom.xml:172-176 runs the `build-info` goal, so the payload carries the full git commit id, branch, commit time and the build version. `/` and `/ui/**` being public is defensible (the SPA shell must load to show a login form); `/actuator/info` is not covered by that rationale and is not mentioned anywhere in the docs.

</details>

<details><summary>Refutation attempt</summary>

The code fact holds: SecurityConfig.java:72-73 permits /actuator/health/**, /actuator/info, /login, /error, /, /ui, /ui/** before the openMode branch at :90, so README.md:188 and CLAUDE.md:195 ("everything except health and the login page requires authentication") are literally inaccurate. That much I could not refute.

But the substance of the finding — the claimed disclosure — is refuted. git-commit-id-maven-plugin is declared only in the parent's pluginManagement (pom.xml:278-297, labelled "Release helpers") and bound in <build><plugins> only inside the `release` profile (pom.xml:414-416). Activating -Prelease deactivates the activeByDefault `default` profile, which is the only place kweblens-web is listed as a module, so the actuator-bearing module is never in the reactor when the git plugin runs; the `docker` profile re-adds web but does not enable git-commit-id. Verified against the build output: kweblens-web/target/classes/git.properties does not exist, and META-INF/build-info.properties holds only build.group/artifact/name/time/version. `management.info.git.mode: full` (application.yml:154-156) is therefore inert — the payload has no git block at all. Boot's java/os/env contributors are off by default and nothing enables them. So no commit id, no branch, no commit time; the "map a running instance to a known-vulnerable revision" consequence does not hold.

It is also a deliberate decision, not an oversight. The permitAll dates to the original security PR (688d1c6, "authentication + RBAC gate, open-mode toggle (#8)"), and the SPA depends on it: AppFooter.vue:6 describes it as "Actuator's public /actuator/info" and api.ts:321 repeats "(public)". The rationale is exactly the one the finder already waives for / and /ui/** — the publicly served shell renders a version footer. README:139 separately documents that /actuator/{health,info,metrics,prometheus} is exposed.

What remains is a doc-precision defect only, and it is both broader and smaller than described: the sentence omits five path families, three of which the finder concedes are defensible, and the information actually reachable (Maven version + build timestamp) is already printed by the publicly served SPA shell.

</details>

---

### [CONFIRMED · severity medium] When the API server ends a watch, kweblens never notices: the Watcher's onClose is empty, so the SSE emitter, its async servlet context and its keepalive schedule are held forever for a watch that no longer exists — and the UI keeps showing a green "live" badge over a frozen table

**`kweblens-core/src/main/java/org/alexmond/kweblens/resource/ResourceService.java:147`** · dimension `Resources acquired and not released` · finder confidence high

**Consequence.** An operator leaves a Pods/Events list open. The API server ends the watch with 410; the table silently stops updating while still showing the green "live" dot, so what is on screen is stale and nothing says so — the one failure mode a live view must not have. Server-side, the SseEmitter, its Tomcat async context and a repeating `sse-keepalive` task stay allocated for the life of the tab with no watch behind them, and the operator's only recovery is to navigate away and back.

**Correction from the refuter.** The cluster-scoped no-op and the blanket swallowing of non-401/403 errors are both real and confirmed. What is overstated is "the UI is indistinguishable from all 10 succeeding". Resource lists are live-watched over SSE (useResourceData.ts), so a successful delete removes its row; failed or never-attempted deletes leave their rows on screen. The operator therefore gets an ambiguous implicit signal (nothing vanished) rather than a positive false confirmation — there is no success toast. The accurate statement is: the action produces no error message, no dialog and no console line explaining why the rows are still there, and on cluster-scoped kinds no request is ever sent. Two further corrections of scope: the affected set is larger than the 13 static kinds, since every cluster-scoped CRD promoted into the dynamic Custom Resources nav is also silently skipped; and the swallowing is bulk-only — the per-row Delete in rowActions.ts:104 does surface failures via setError, so a single-object delete still reports 409/404/webhook denials.

<details><summary>Evidence</summary>

Both watch factories install a Watcher whose close handler does nothing:

```java
@Override
public void onClose(WatcherException cause) {
    // The web layer completes the SSE emitter via its own close hooks.
}
```
(ResourceService.java:146-149 in `watch`, :174-178 in `watchRaw`)

The comment is backwards. The web layer's hooks are `emitter.onCompletion(watch::close)` / `onTimeout` (ObjectApiController.java:127-131, ResourceWatchApiController.java:49-53) — they run when the *emitter* completes and then close the *watch*. Nothing runs in the other direction: no code path completes the emitter when the watch dies.

The watch does die on its own. fabric8 7.3.1 `AbstractWatchManager.onStatus` (unpacked from kubernetes-client-7.3.1-sources.jar):
```java
if (Integer.valueOf(HTTP_GONE).equals(status.getCode())) {
  close(new WatcherException(status.getMessage(), new KubernetesClientException(status)));
  return true;
}
```
and `close(WatcherException)` calls `watcher.onClose(cause)` then `close()` — no reconnect. A 410 Gone (`resourceVersion too old`) is the routine outcome once a kind's changes outrun the API server's watch cache window; `scheduleReconnect` likewise gives up with `close(new WatcherException("Exhausted reconnects"))`.

After that the emitter is still open and the browser is still there, so `SseKeepAlive`'s 15 s probe keeps *succeeding* — the exact mechanism that releases a departed subscriber never fires for a departed watch. On the client, `useResourceData.ts:163-164` is `es.onopen = () => (live.value = true); es.onerror = () => (live.value = false);` — the EventSource never errors, so `live` stays true, and `ResourceListView.vue:49` keeps rendering `<span v-if="live" class="live" title="Live-updating (SSE watch)">`.

`docs/design/watch-fanout.md` explicitly records this as never exercised: "The API server's own watch timeout was never reached in any of these runs, so what fabric8 does when the server ends a watch — reconnect, or surface it — is unverified."

</details>

<details><summary>Refutation attempt</summary>

I tried four refutation angles and all failed. (1) Reachability: the selection column in ResourceTable.vue:118 is unconditional and ResourceListView.vue renders the bulk bar + Delete button on any non-empty selection with no `selected.namespaced` guard; useAppActions.ts:105 gates only on cluster/selection/auth. objKey is `(ns ?? '') + '/' + name`, so cluster-scoped rows select correctly — only the `&& objNs(o)` filter drops them. (2) Deliberate? No. ResourceService.java:489 is `descriptor.namespaced() ? op.inNamespace(namespace).withName(name) : op.withName(name)` — the server explicitly supports cluster-scoped deletes and ignores the namespace, so the UI is withholding a request the backend would honour. No comment, doc, or ADR covers it; ADR-001 is about identity, unrelated. (3) Handled elsewhere? No global error handler, no unhandledrejection hook, no fetch interceptor — and the catch swallows so nothing escapes anyway. `deps` has no error channel, while the single-row delete one function away (rowActions.ts:104) does `(e) => c.setError(String(e))`, proving the omission is an oversight, not a stance. (4) Misread? No — /objects returns raw fabric8 JSON, so cluster-scoped objects really have no metadata.namespace, and the filter is a namespace-presence proxy that silently excludes them. The scope is in fact wider than claimed: every cluster-scoped CRD in the dynamic Custom Resources nav is affected too, not just the 13 static kinds. The only overstatement is in the consequence (see correction). Severity medium rather than high: the failure mode is fail-closed (nothing is destroyed), the live SSE watch gives an indirect signal that rows never disappeared, and the per-row Delete action remains a working, error-reporting path — what is lost is trustworthy feedback on a destructive action, not data.

</details>

---

### [CONFIRMED · severity medium] HelmRelease.status is typed non-nullable but the server explicitly emits null, and the renderer dereferences it — the whole Helm Releases table fails to render

**`kweblens-ui/src/types.ts:147`** · dimension `Drift between the server's JSON and the client's types` · finder confidence high

**Consequence.** A single release whose stored info has no status blanks the entire Helm → Releases pane (and the History modal) with a console TypeError and no message — not one bad cell, the whole table. The milder half of the same drift is already live: `appVersion: null` renders as an empty cell instead of the `—` every other nullable column in that table uses.

**Correction from the refuter.** Accurate as stated, with two refinements. (a) "Can never change a stored context" is slightly strong: the context CAN be changed, but only by re-pasting the entire kubeconfig to unlock the dropdown — which contradicts the modal's own label "Kubeconfig (leave blank to keep the stored one)" and the controller's javadoc promise. That workaround has a genuine dead end, though: the credential is write-only and never returned, so an operator who no longer holds the original kubeconfig file cannot change the context at all short of Remove + Add. (b) "Can never show" is exactly right — the stored context is never displayed, so the operator also cannot see which context a cluster is currently using. Scope is bounded: name-only edits work correctly, nothing is lost on save (ClusterConfigService.update merges via hasText(patch.context()) ? patch.context() : existing.context()), and the flow is admin-only and applies just to runtime-added clusters. The unused kubeconfigStored/storage/persistent fields are cosmetic by comparison; `contexts`/`context` are the load-bearing dead payload.

<details><summary>Evidence</summary>

Server, `HelmService.toSummary` (kweblens-web/.../web/helm/HelmService.java:277-278):

    String status = (release.getInfo() != null && release.getInfo().getStatus() != null)
            ? release.getInfo().getStatus().name() : null;

So `status` is `null` whenever jhelm hands back a Release with no `info` (or no status inside it) — the null check exists because that case is real. Same method nulls `chart`/`chartVersion`/`appVersion` when `getChart()` is null; I confirmed a live null on the wire: `GET /api/v1/clusters/default/helm/releases` returned `{"name":"test-release",...,"appVersion":null,...}`.

Client, `types.ts:147`:

    export interface HelmRelease {
      ...
      status: string;          // non-nullable
      appVersion: string;      // non-nullable, server sends null today

Consumers dereference it unguarded — `HelmReleasesPane.vue:140` and `HelmHistoryModal.vue:37`:

    { title: 'Status', key: 'status', sorter: str('status'), render: (r) => h(StatusBadge, { text: r.status }) }

`StatusBadge.vue:30` computes `TONE_VARS[props.tone ?? badgeTone(statusTone(props.text))]`, and `columns.ts:104` is `const t = value.trim().toLowerCase();` — `null.trim()` throws. The throw happens inside an NDataTable column `render`, i.e. inside a Vue render function, and `main.ts` installs no `errorHandler` and nothing uses `onErrorCaptured`. Note the sibling columns are null-safe by accident (`str()` sorter uses `?? ''`, `updated` renders `'—'`), which is why this passes review by eye.

</details>

<details><summary>Refutation attempt</summary>

Tried four refutation angles, all failed. (1) Reachability: ClustersPage.vue is the sole consumer of ClusterEditModal.vue, and startEdit(r.id, r.name) at line 100 is the only edit entry point, rendered whenever canWrite && r.editable — i.e. for every RUNTIME cluster with the admin logged in. It hardcodes `context: null`. ClusterEditModal's `contexts` ref is filled only by readContexts(), whose button is :disabled="!kubeconfig.trim()", so on edit the select is permanently empty and disabled. Not a dead branch. (2) Deliberate? The opposite — the server documents exactly the capability the UI fails to expose: ClusterConfigApiController.java:58-60 says "Omit kubeconfig to keep the stored credential — renaming or switching context does not require re-supplying it", and ClusterConfigView's javadoc calls itself "everything the editor needs to render". No TODO or staging comment anywhere. ADR-001 (single operator) is irrelevant — this is not an identity concern. (3) Handled elsewhere? grep -rn "clusterConfig|ClusterConfigView" over kweblens-ui/src returns only types.ts:22, api.ts:8, the api.ts:280 comment and the api.ts:287 declaration. No store, parent, or route guard prefetches it. (4) Is context cosmetic? No — ClusterConfigService.clientFor (core:242) passes definition.context() into KubeconfigLoader.clientFor, so it selects which cluster/user the client authenticates as. And describe() (core:152-161) genuinely populates both `context` and `contexts` from the stored kubeconfig for RUNTIME clusters, which are precisely the ones with an Edit button — the empty payload in the offered evidence is only because `default` is STATIC. Nothing breaks the claim.

</details>

---

### [CONFIRMED · severity low] The server resolves and serves a PersistentVolume's boundClaim relation, but Overview.vue refuses to request relations for any cluster-scoped object, so it is never seen

**`kweblens-ui/src/components/Overview.vue:65`** · dimension `Drift between the server's JSON and the client's types` · finder confidence high

**Consequence.** Opening a PersistentVolume's detail drawer never shows a Bound Claim section — the one join that answers "what is actually using this volume". The join is computed server-side and available; the browser simply never asks. Same for `ownedBy` on any cluster-scoped object carrying an ownerReference (RelationService.java:105, also outside the namespaced branch).

**Correction from the refuter.** The consequence is narrower than stated. A total cluster outage or timeout also fails `api.overview`, which sets `error` and renders the error div at :103 while the cards disappear — so the operator is not left uninformed in the common case. The genuinely silent window is an *events-specific* failure: a 403 on `events` when workload kinds are readable, a timeout on the cluster-wide events list while `/overview` succeeds, or a transient 5xx on that one call. It also is not "the page contradicts its own principle" for the primary signal: the health checks still report `unavailable` kinds at :157-159 and the attention table is unaffected. What is actually lost is one supplementary pane silently reading as clean when its fetch failed — most damagingly under a scoped RBAC role that omits `events`, where "No events." becomes permanent. The "no retry" point is not events-specific: the component offers no retry on the overview error path either. Fix is one line: hold an `eventsError` ref (mirroring Detail.vue:239) and pass it instead of the literal null; keep `events.value` at null on failure so the pane does not also claim a completed empty load.

<details><summary>Evidence</summary>

Server, `RelationService.relationsFor` (kweblens-core/.../resource/RelationService.java:100-103):

    if ("PersistentVolume".equals(kind)) {
        // Cluster-scoped, so it is reachable only outside the namespaced branch — and
        // it is why this method no longer requires a namespace at all.
        out.put("boundClaim", this.storage.boundClaim(clusterId, object));
    }

Verified live — `GET /api/v1/clusters/default/detail/persistentvolumes/-/pvc-014d1d07-…` returns `relations.boundClaim` populated with the bound PVC object (`"name":"vantage-holyland-cache","namespace":"venice-vr"`, `"truncated":false,"notPermitted":false`).

Client, `Overview.vue:59-68`:

    // Namespaced objects only: the relations resolved today are all namespace-scoped, and the
    // endpoint's path requires a namespace.
    ...
    if (!cluster || !resourceId || !namespace || !name) {
      return;
    }

`objNs()` is null for a PV, so the guard returns and `api.detail` is never called. The comment is stale relative to the server. The endpoint's path shape (`/detail/{resourceId}/{namespace}/{name}`) is also still namespace-required — I confirmed an empty segment 400s — so the two sides drifted apart in opposite directions.

</details>

<details><summary>Refutation attempt</summary>

Could not refute it. The path is live: `showEvents` is true for category 'workloads', so the section renders; the `.catch` at CategoryOverview.vue:57 converts any events failure into `[]`; `recentEvents` (:92) treats `[]` as truthy so it stays `[]` rather than null; EventsPane receives `error=null` and a non-null array, so `:loading` is false and the `#empty` slot renders "No events." Not deliberate: `git log -S':error=\"null\"'` traces it to 1518f50 (#166) with no rationale, and docs/design/overview-pages.md adopts the opposite rule verbatim ("Say when data is partial or unavailable, and why … overviews should be equally honest rather than rendering an empty chart"). ADR-001 is irrelevant here. Nothing else handles it — api.events throws ApiError on non-OK (api.ts:337) and there is no global fetch handler or parent catch. Server side, EventService.listFor -> ResourceService.listRaw propagates KubernetesClientException, and `events` is its own RBAC resource, so a scoped read-only role omitting it (the deployment posture CLAUDE.md recommends) makes the empty state permanent, not transient. One evidence error in the claim: it says "the only caller that could supply one never does" — there are two callers and the other, Detail.vue:239, does pass `:error="eventsError"`. That misstatement strengthens rather than weakens the finding: the correct pattern already exists for the same component.

</details>

---

## Appendix — unpaired, i.e. unverified (2026-08-06 run)

### [UNPAIRED] Bulk Delete silently does nothing on every cluster-scoped kind, and swallows every non-auth failure, so a destructive action reports success it never attempted

**`kweblens-ui/src/shell.ts:238`** · dimension `Error and empty states (roadmap T4)` · finder confidence high

**Consequence.** Select three PersistentVolumes (or Nodes, or CRDs), click Delete, confirm a dialog that says "Delete 3 Persistent Volumes? This cannot be undone." — nothing is sent to the API server, the selection clears, and no message appears. The operator believes the delete happened. The same silence covers partial failure on namespaced kinds: delete 10 pods where 7 are blocked by a webhook, and the UI is indistinguishable from all 10 succeeding.

<details><summary>Evidence</summary>

`runBulkDelete` filters the targets by whether the object has a namespace, then discards every error that is not 401/403:

```ts
const targets = objects.filter((o) => selection.has(objKey(o)) && objNs(o));
for (const o of targets) {
  try {
    await api.del(cluster, selected.id, objNs(o) as string, objName(o));
  } catch (e) {
    if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
      onAuthCleared();
      break;
    }
  }
}
clearSelection();
```

`&& objNs(o)` drops every cluster-scoped object — `NavCatalog.java` registers 13 of them in the left nav (`NODES`, `NAMESPACES`, `persistentvolumes`, `storageclasses`, `clusterroles`, `clusterrolebindings`, `priorityclasses`, `runtimeclasses`, `ingressclasses`, the four admission webhook/policy kinds, `customresourcedefinitions`). `ResourceListView.vue:88-92` renders the bulk bar with a Delete button on every list regardless of `selected.namespaced`, so the button is offered on all of them. The `catch` block has no `else`: a 409 from a finalizer, a 404, an admission-webhook denial or a 500 produces no `setError`, no dialog, no console line. `deps` carries no error channel at all. There is no test — `grep -rn runBulkDelete kweblens-ui/src` returns only the definition and its one caller.

</details>

_No verdict could be paired with this finding — unverified._

---

### [UNPAIRED] Any metrics-graph failure is reported as "Graphs need a Prometheus / VictoriaMetrics backend", telling operators who already have one to install it

**`kweblens-ui/src/components/MetricChart.vue:37`** · dimension `Error and empty states (roadmap T4)` · finder confidence high

**Consequence.** An operator whose Prometheus is running and healthy opens the cluster dashboard, sees both charts say they need a metrics backend, and goes off to install or reconfigure one that already works. The actual cause — a proxy 403, a query timeout — is only in the server log as a `log.warn`.

<details><summary>Evidence</summary>

The client collapses every failure into the one state that has a message:

```ts
api.metricGraph(props.cluster, props.target, {...})
  .then((s) => (series.value = s))
  .catch(() => (series.value = { available: false, unit: '', points: [] }));
```

`state` (:41-49) maps `!s.available` to `'unavailable'`, rendered at :115 as `Graphs need a Prometheus / VictoriaMetrics backend.` The server does the same collapse one layer down — `PrometheusMetricService.queryRange` (kweblens-core, :113-116) discovers a backend, runs the query, and on any `RuntimeException` returns `MetricSeries.unavailable()`, the identical value it returns when no backend was discovered at all. So a backend that *was* found but whose `query_range` 403s through the apiserver proxy, times out, or returns unparseable JSON is indistinguishable from no backend. There is no retry control on the chart.

</details>

_No verdict could be paired with this finding — unverified._

---

### [UNPAIRED] Removing a cluster leaves its port-forwards bound: the local listening socket stays open for the life of the process and disappears from the UI, so it can never be stopped

**`kweblens-core/src/main/java/org/alexmond/kweblens/portforward/PortForwardService.java:46`** · dimension `Resources acquired and not released` · finder confidence high

**Consequence.** Operator starts `kubectl`-style port-forward on localhost:8443 against a runtime-added cluster, then removes that cluster from the Clusters page. The forward vanishes from every screen but localhost:8443 stays bound to a dead tunnel until kweblens is restarted — connections to it hang, and re-adding the cluster and forwarding to 8443 again fails to bind. There is no UI action that can recover it.

<details><summary>Evidence</summary>

`PortForwardService` holds every forward in its own map, keyed by `clusterId + "-" + seq`:
```java
private final ConcurrentMap<String, Active> forwards = new ConcurrentHashMap<>();
```
and a forward is released only by an explicit `stop(id)` (line 111-117) or by `close()` at JVM shutdown (line 157-161).

The cluster-removal path never touches it (ClusterConfigService.java:142-152):
```java
public void remove(String id) {
    this.lock.lock();
    try {
        requireEditable(id);
        this.registry.unregister(id);
        this.store.delete(id);
    }
    ...
```
`grep -n "PortForward" ClusterConfigApiController.java kweblens-core/.../cluster/*.java` returns nothing — no listener, no callback.

What is being leaked is a real OS resource, not just a map entry. fabric8's `PortForwarderWebsocket` (7.3.1 sources, line 67) does `final ServerSocketChannel server = ServerSocketChannel.open().bind(inetSocketAddress);` and only its own `close()` (line 77-82) calls `server.close()`. `KubernetesClient.close()` does not know about the forward, so `registry.unregister(id)` kills the tunnel's transport while leaving the local port bound.

And it becomes unreachable: the only listing is per-cluster — `PortForwardApiController.list` → `portForwards.list(clusterId)` filters on `active.info.clusterId().equals(clusterId)` (PortForwardService.java:102-108) — and the UI only ever asks for the current cluster (`api.ts:492`, `PortForwards.vue`). A removed cluster has no tab to show them on.

</details>

_No verdict could be paired with this finding — unverified._

---

### [UNPAIRED] Adding or editing a cluster leaks an entire KubernetesClient — and with the Vert.x transport a whole Vertx instance — whenever persisting the definition fails

**`kweblens-core/src/main/java/org/alexmond/kweblens/cluster/ClusterConfigService.java:107`** · dimension `Resources acquired and not released` · finder confidence high

**Consequence.** On a deployment whose service account lacks Secrets write (the exact case the code comments anticipate), every "Add cluster" attempt returns an error and leaves behind one fabric8 client plus one Vert.x instance with its thread pools. An operator retrying the paste a handful of times accumulates that many; nothing ever reclaims them, and the symptom is a slowly growing thread count and heap with no cluster to attribute it to.

<details><summary>Evidence</summary>

```java
KubernetesClient client = clientFor(normalized);
this.store.save(normalized);
return this.registry.register(normalized.id(), displayName(normalized), client, ClusterOrigin.RUNTIME);
```
(add, lines 107-109; identical shape in `update`, lines 131-134.)

The client is built first and only handed to the registry — the sole owner of client lifecycles — on the last line. If `store.save` throws, nothing closes it. `FileClusterStore.save` throws `UncheckedIOException("Could not persist cluster '" + id + "'")` on any IO failure (FileClusterStore.java:99-101), and the class's own doc names the in-cluster case: "In-cluster this is the likely shape of a missing RBAC grant on Secrets" (ClusterConfigService.java:72-74).

The leak is heavier than a socket. `dependency:tree` shows the Vert.x transport (`io.vertx:vertx-web-client:4.5.14`), and `VertxHttpClientFactory` is constructed with `sharedVertx == null`, so `VertxHttpClientBuilder` (7.3.1 sources, line 55-58) takes `sharedVertx != null ? sharedVertx : createVertxInstance()` — a brand-new `Vertx.vertx(...)` per client, with its own Netty event-loop group, worker pool and timers. `closeVertx` is true, i.e. only `client.close()` shuts it down.

The class javadoc claims the failure is clean — "a rejected kubeconfig leaves both the store and the registry byte-for-byte as they were" — which is true of the *state* and false of the client.

</details>

_No verdict could be paired with this finding — unverified._

---

### [UNPAIRED] SchemaService caches a full OpenAPI schema set per (cluster, group-version) with no bound, no TTL and no invalidation when the cluster is removed

**`kweblens-core/src/main/java/org/alexmond/kweblens/schema/SchemaService.java:44`** · dimension `Resources acquired and not released` · finder confidence medium

**Consequence.** Every kind whose YAML editor is opened permanently pins that group-version's whole schema document in heap, per cluster; on a CRD-heavy cluster (CLAUDE.md cites 118 kinds, 72 of them CRDs) that is tens of group-version documents, and the core `api/v1` document alone is a large parse. Nothing ever evicts, so a long-running instance's baseline heap only grows, and removing a cluster does not give the memory back. I could not measure the per-entry size — there is no reachable cluster on this box (`kubectl get --raw /openapi/v3/api/v1` fails, no current-context) — so the magnitude is inferred from the code, not observed.

<details><summary>Evidence</summary>

```java
private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();
```
and the only writer (lines 56-62):
```java
Map<String, Object> defs = cache.get(clusterId + '|' + gvPath);
if (defs == null) {
    defs = fetchDefs(clusterId, gvPath);
    if (!defs.isEmpty()) {
        cache.put(clusterId + '|' + gvPath, defs);
    }
}
```
There is no `remove`, no size cap and no expiry anywhere in the class. Each value is the *entire* `components.schemas` object of the cluster's `/openapi/v3/<group-version>` document, parsed into nested `LinkedHashMap`s by `rewriteDefs` (line 97-110) — every schema in the group-version, not just the requested kind.

This is the odd one out: every other cache in the project is bounded. `application.yml:36-38` pins Caffeine to `maximumSize=2000,expireAfterWrite=10s` for `@Cacheable` (`CrdService`), `AuditService` is a 500-entry ring, `DiagnosisSummaryCache` is an LRU capped at `MAX_SCOPES = 64` with the comment "the cap exists so a long-lived process browsing many namespaces cannot grow without bound". `SchemaService` rolls its own map and skips all of that.

Because the key is `clusterId|gvPath`, `ClusterRegistry.unregister` — whose doc says "a removed cluster must stop holding sockets and threads, not merely disappear from the list" — does not drop the removed cluster's parsed schemas.

</details>

_No verdict could be paired with this finding — unverified._

---

### [UNPAIRED] The /counts and /search fan-outs cannot be cancelled: they hold a request thread on an untimed Future.get() and keep firing API-server calls long after the browser has given up

**`kweblens-web/src/main/java/org/alexmond/kweblens/web/api/CountApiController.java:92`** · dimension `Resources acquired and not released` · finder confidence medium

**Consequence.** Against a cluster that is registered but unreachable (a stale kubeconfig — which the design explicitly permits, since building a client does not connect), each namespace switch queues ~118 doomed list calls across 12 threads. The browser abandons the request at 20 s; the server keeps working through the backlog with a Tomcat thread pinned, and a fast-clicking operator stacks more before the previous batch drains, so the sidebar badges stop answering for everyone until it unwinds. I could not time the unwind without a cluster, so the duration is inferred from fabric8's default request timeout rather than measured.

<details><summary>Evidence</summary>

```java
for (ResourceDescriptor descriptor : kinds) {
    pending.add(this.executor.submit(() -> count(clusterId, descriptor, namespace, counts)));
}
for (Future<?> future : pending) {
    await(future);
}
```
(CountApiController.java:89-94; `await` at 120-130 calls a bare `future.get()` with no timeout and, on `InterruptedException`, re-interrupts and returns — it never cancels the remaining futures.) `SearchService.runAll`/`await` (lines 137-148, 221-233) is the same shape on an 8-thread pool.

One `/counts` submits one task per kind — 118 on the cluster CLAUDE.md describes — onto a 12-slot pool (`MAX_CONCURRENT = 12`, line 63), and it is "re-fetched on every namespace switch" per the class doc.

The client cannot stop it. `useClusterScope.ts:87-92` does `onCleanup(() => (cancelled = true))` and then `api.counts(c, ns ?? undefined)` — a boolean flag, no `AbortSignal`; `api.ts:492`-style helpers only thread a signal where one is passed, and `counts` passes none. Meanwhile `fetchWithTimeout` (api.ts:140-141) aborts the browser side after 20 s. Server-side nothing observes either event: the tasks run to completion and the request thread stays parked in `Future.get()`.

Neither executor is ever shut down — no `@PreDestroy`, unlike `HelmChartService.shutdown()` (HelmChartService.java:82-85) which does exactly that for its own pool.

</details>

_No verdict could be paired with this finding — unverified._

---

### [UNPAIRED] ClusterConfigView is declared client-side and served by the API, but api.clusterConfig has zero callers — so the Edit-cluster modal can never show or change a stored context

**`kweblens-ui/src/api.ts:287`** · dimension `Drift between the server's JSON and the client's types` · finder confidence high

**Consequence.** Editing a runtime cluster shows the Context dropdown empty, disabled, and captioned "Read the kubeconfig to list its contexts" — even when a context IS stored. The only way to switch context is to re-paste the entire kubeconfig, directly contradicting the modal's own label "Kubeconfig (leave blank to keep the stored one)". The `contexts` / `context` / `kubeconfigStored` fields exist precisely to prevent this and are dead payload.

<details><summary>Evidence</summary>

`api.ts:287` declares it and nothing calls it (`grep -rn clusterConfig kweblens-ui/src` returns only the declaration and its doc comment). Live, the endpoint works and carries exactly what the editor needs:

    GET /api/v1/clusters/default/config
    {"id":"default",...,"context":null,"contexts":[],"kubeconfigStored":false,"storage":"data directory …","persistent":true}

Instead, `ClustersPage.vue:39` fabricates the definition from the list row, which has no context field at all:

    const startEdit = (id, name) => {
      editing.value = { definition: { id, name, context: null }, isNew: false };
    };

and `ClusterEditModal.vue` then locks the control it just emptied — line 121 `:disabled="!contexts.length"` (contexts is `ref<string[]>([])`, only ever filled by `readContexts`), line 125 `:disabled="!kubeconfig.trim()"`. The server side is fine: `ClusterConfigService.update` (kweblens-core:129) merges — `hasText(patch.context()) ? patch.context() : existing.context()` — so nothing is lost, but nothing can be changed either.

</details>

_No verdict could be paired with this finding — unverified._

---

### [UNPAIRED] The whole /remediations endpoint family is dead payload — the SPA never calls propose, preview or apply

**`kweblens-web/src/main/java/org/alexmond/kweblens/web/api/RemediationApiController.java:33`** · dimension `Drift between the server's JSON and the client's types` · finder confidence high

**Consequence.** roadmap.md line 57 records remediation preview as DONE and "server-validated where a patch exists", and CLAUDE.md presents suggest→approve→apply as shipped. In the running product there is no path to any of it from the browser — restart-pod / rollout-restart / rollback / scale-up are reachable only by curl. `--previous` logs, documented in MultiLogApiController as "the crashloop diagnostic", are likewise unreachable from the UI.

<details><summary>Evidence</summary>

Server exposes three endpoints under `/api/v1/clusters/{clusterId}/remediations`: `propose` returning `List<RemediationProposal>` (line 33), `preview` returning `RemediationPreview` (line 48), `apply` returning `Map.of("result", …)` (line 54).

Client: `api.ts` contains no remediation call. Enumerating every URL it builds (`grep -oE '\`\$\{clusterBase\(cluster\)\}[^\`]*' api.ts`) yields 33 paths — apply, apply/dry-run, counts, detail, diagnose, diagnose/summary, diagnostics, events, helm/*, metrics/*, namespaces, nav, nodes/*, overview, pods/*/files, port-forwards, resources/*, schema, search, yaml — and no `/remediations`. A case-insensitive `grep -rniE 'remediat' kweblens-ui/src` returns nothing. `DiagnosisPanel.vue:129` renders only `Finding.suggestedFix`, i.e. the prose sentence from the diagnose payload, never a proposal or a preview.

Same class, smaller: `/api/v1/audit`, `/metrics/prometheus`, `/logs/sources`, `/patch`, `/pods/{ns}/{pod}/log/previous` and `GET /helm/releases/{ns}/{name}` also have no client caller. The `sources` SSE event additionally ships `"ordering":"best-effort"` (MultiLogStream.java:215) which the client destructures away (`useMultiLogs.ts:136` reads only `sources`/`truncated`/`totalFound`), despite MultiLogStream's own javadoc claiming "the endpoint reports it to the client".

</details>

_No verdict could be paired with this finding — unverified._

---

### [UNPAIRED] AboutInfo omits the clusterStore block the server sends, so the Diagnostics panel never reports where runtime clusters live or whether they survive a restart

**`kweblens-ui/src/types.ts:258`** · dimension `Drift between the server's JSON and the client's types` · finder confidence medium

**Consequence.** The panel that exists to answer "how is this instance configured" silently drops the one fact about credential storage the server bothered to compute and mark operator-relevant — including `persistent: false`, i.e. "the clusters you add here will be gone after a restart". The operator has to read the JSON by hand to find out.

<details><summary>Evidence</summary>

Server, `DiagnosticsService.appDiagnostics` (kweblens-web/.../web/diag/DiagnosticsService.java:281-285):

    Map<String, Object> store = new LinkedHashMap<>();
    store.put("backend", authenticated ? this.clusterStore.describe() : "(sign in to see)");
    store.put("persistent", this.clusterStore.persistent());
    store.put("runtimeClusters", runtimeClusterCount());
    out.put("clusterStore", store);

Confirmed live: `GET /api/v1/about` → `…"clusterStore":{"backend":"(sign in to see)","persistent":true,"runtimeClusters":0},…`.

`types.ts:258` `AboutInfo` has no `clusterStore` member, and `DiagnosticsModal.vue` renders Version / Clusters / Ambient kubeconfig / Security / Admin user / AI / Simulator and nothing else. Note also `runtimeClusterCount()` (line 79-87) returns `Object` — an `Integer` normally, the **string** `"unavailable"` when the store cannot be read — so even a naive `clusterStore.runtimeClusters: number` would be wrong.

Adjacent, same class: `api.ts:321` types `/actuator/info` as `git?: { commit?: { id?: string } }`, but the server sends `git.commit.id` as an object (`{"describe-short":"1.5.0","abbrev":"e189794","full":"…"}`) — rendering it would print `[object Object]`. `api.info()` has no callers, so nothing does today.

</details>

_No verdict could be paired with this finding — unverified._

---

### [UNPAIRED] The entire Remediation subsystem (~414 LOC + 3 endpoints) has no consumer — no UI code and no MCP tool reference it, yet README lists it as a shipped feature

**`kweblens-web/src/main/java/org/alexmond/kweblens/web/api/RemediationApiController.java:32`** · dimension `Code that cannot execute` · finder confidence high

**Consequence.** A user who reads "What works today" and opens kweblens looking for the four proposed fixes will never find them: no button, no panel, no palette entry. `DiagnosisPanel.vue` — the one place a fix would belong — renders `suggestedFix` as static prose from the health checks and never calls the remediation API. The advertised suggest→approve→apply flow is only reachable by hand-crafting an authenticated POST with curl.

<details><summary>Evidence</summary>

`RemediationApiController` exposes `GET /api/v1/clusters/{clusterId}/remediations` (`propose`), `POST …/remediations/preview`, `POST …/remediations/apply`, backed by `RemediationService` (337 lines) + `RemediationProposal` (20) + `RemediationPreview` (57).

Proof of absence — searched the whole SPA (case-insensitive), zero hits:
```
$ grep -rin "remediat" kweblens-ui/src
(no output)
```
The string does not appear in `api.ts` (the SPA's single HTTP client; I read all 62 members of `export const api`), in `types.ts`, or in any `.vue`. Cross-checked against the other front-end: `grep -rc "@Tool" web/mcp/*.java` gives 15 tools across `ClusterTools`/`DiagnosticTools`/`HealthTools`, none of which imports `RemediationService` — Java references to it outside `web/ai/` are exactly the six lines in this controller. So neither the browser nor an MCP client can reach it.

Meanwhile `README.md:37`, in the table headed **"What works today"** (alongside Logs, Terminal, Helm, Port-forward), states: `| **Remediation** | Four proposed fixes — restart-pod, rollout-restart, rollback, scale-up — each offered only when a precondition says it can actually work, and applied only with explicit confirmation |`. The row does not say it is API-only — contrast the Audit row two lines down, which honestly writes `queryable at /api/v1/audit`.

</details>

_No verdict could be paired with this finding — unverified._

---

### [UNPAIRED] `GET /api/v1/clusters/{id}/config` and the whole `ClusterConfigView` DTO are dead, and the gap is visible: the Edit-cluster dialog cannot change a kubeconfig context without re-pasting the entire kubeconfig

**`kweblens-ui/src/components/ClustersPage.vue:36`** · dimension `Code that cannot execute` · finder confidence high

**Consequence.** Editing a cluster shows the Context field permanently blank and greyed out, so the operator cannot see which context the cluster is pinned to, and cannot switch it without pasting the whole kubeconfig YAML back into the textarea — the exact re-paste the server code says causes people to paste the wrong credential. The dialog also never shows whether a kubeconfig is stored or whether the store survives a restart, both of which the dead DTO returns.

<details><summary>Evidence</summary>

`ClustersPage.startEdit` fabricates the definition instead of fetching it:
```ts
const startEdit = (id: string, name: string) => {
  editing.value = { definition: { id, name, context: null }, isNew: false };
};
```
`context: null` is hardcoded. `ClusterEditModal.vue:25` seeds from it (`const context = ref<string | null>(props.definition.context)`), and the context picker at `ClusterEditModal.vue:121` is `:disabled="!contexts.length"` where `contexts` is filled *only* by `readContexts()`, whose own button is `:disabled="!kubeconfig.trim()"`.

The endpoint that closes this exists and is never called:
```ts
// api.ts:287
clusterConfig: (id) => getJson<ClusterConfigView>(`${CLUSTERS}/${encodeURIComponent(id)}/config`),
```
```
$ grep -rn "clusterConfig" kweblens-ui/src
api.ts:280:  // ... clusterConfig reports `kubeconfigStored` ...   (comment)
api.ts:287:  clusterConfig: (id: string) => ...                    (definition)
$ grep -rn "ClusterConfigView" kweblens-ui/src
api.ts:8, api.ts:287, types.ts:22                             (declaration only)
```
Server side the chain is `ClusterConfigApiController:79 → ClusterConfigService.describe()` (`ClusterConfigService.java:155`), whose only non-test caller is that controller. `ClusterConfigView` carries exactly the four fields the dialog is missing: `context`, `contexts` (every context in the *stored* kubeconfig), `kubeconfigStored`, `persistent`.

And `ClusterConfigService.update` (line 121) was explicitly written for this: "A blank kubeconfig keeps the stored credential, so renaming or **switching context** does not require the operator to paste the kubeconfig again — which in practice is what makes people paste the wrong one." The server merges (`context = hasText(patch.context()) ? patch.context() : existing.context()`), so no data is lost — the capability is simply unreachable.

</details>

_No verdict could be paired with this finding — unverified._

---

### [UNPAIRED] Two source files contain a raw NUL byte, so grep, ripgrep and GitHub code search silently skip them entirely instead of matching lines

**`kweblens-ui/src/diagnosis.ts:270`** · dimension `Code that cannot execute` · finder confidence high

**Consequence.** Any line-oriented search over the repo silently omits these two files. `grep -rn` returns "binary file matches" with no line number; ripgrep and Claude Code's own Grep tool (`ugrep -I`) drop them from results with no warning at all. That is a functioning blind spot in exactly the tool used to prove code is dead or to find every call site — I hit it during this audit and initially concluded `depth` did not exist in `diagnosis.ts`. Move one byte earlier in either file and git starts rendering their PR diffs as "Binary files differ", making them unreviewable.

<details><summary>Evidence</summary>

Byte 10783 of `diagnosis.ts` is `0x00`, written as a literal NUL inside a string literal rather than as `'\0'`:
```
line 270: b"    const key = severity + '\x00' + finding.title;"
```
The same idiom, same raw byte, at `kweblens-web/src/main/java/org/alexmond/kweblens/web/ai/DiagnoseService.java:412`:
```
b"\t\t\tgroups.computeIfAbsent(finding.severity() + '\x00' + finding.title(), (key) -> new ArrayList<>()).add(finding);"
```
A repo-wide byte scan (excluding .git/node_modules/target/dist) finds these two files and no others.

Demonstrated consequence:
```
$ file kweblens-ui/src/diagnosis.ts
kweblens-ui/src/diagnosis.ts: data
$ grep -rn "groupFindings" kweblens-ui/src/
…/DiagnosisPanel.vue:25:  groupFindings,
…/diagnosis.test.ts:105:describe('groupFindings', () => {
grep: …/diagnosis.ts: binary file matches      ← no line, no number
$ rg -n "severityOf" kweblens-ui/src/diagnosis.ts
binary file matches (found "\0" byte around offset 10783)
```
The file is valid UTF-8 (Python decodes it cleanly) and both toolchains compile it, so every gate — prettier, vue-tsc, eslint, knip, vitest, javac, checkstyle — is green. Git itself is unaffected only by luck: git sniffs the first 8000 bytes for NUL and the byte sits at 10783, so `git diff` and `git grep` still work.

</details>

_No verdict could be paired with this finding — unverified._

---

### [UNPAIRED] `DELETE /api/v1/helm/values/{name}` and `api.helmValuesDelete` are never called — saved values files can be created from the UI but never removed from it

**`kweblens-ui/src/api.ts:415`** · dimension `Code that cannot execute` · finder confidence high

**Consequence.** The reusable values-file library is append-only from the browser. A file saved under a typo'd name, or a stale values set for a chart the operator no longer runs, stays in the "saved values" dropdown permanently and is written to the mounted volume forever. The only way to remove one is a hand-made authenticated DELETE or shelling into the pod.

<details><summary>Evidence</summary>

```ts
// api.ts:415
helmValuesDelete: (name: string) => deleteReq(`/api/v1/helm/values/${encodeURIComponent(name)}`),
```
```
$ grep -rn "helmValuesDelete" kweblens-ui/src
api.ts:415:  helmValuesDelete: ...          ← definition only
```
`HelmValuesEditor.vue` — the only component that touches the library — wires three of the four operations and not the fourth:
```
26:  .helmValuesGet(pickValues.value)
51:  .helmValuesSave(n, valuesYaml.value)
55:  return api.helmValuesList().then((list) => (savedValues.value = list));
```
Server side `HelmValuesApiController:50` (`delete`) → `library.delete(name)` + `audit.record("-", "helm-values-delete", name)` in `ValuesLibraryService` — reachable only from that controller. The values directory is persisted under the Helm home (`kweblens.helm.values-path`, a PVC in-cluster), so entries survive restarts.

</details>

_No verdict could be paired with this finding — unverified._

---

### [UNPAIRED] 46 CSS rules in styles.css select classes no template can produce — leftovers from the migration to Naive UI components that PR #272's sweep did not reach

**`kweblens-ui/src/styles.css:2720`** · dimension `Code that cannot execute` · finder confidence high

**Consequence.** Roughly a tenth of the stylesheet is unreachable. Concretely it is a maintenance trap of the kind this repo has already been bitten by: `contrast-check.mjs` reports a selector that is not on screen as `not present` rather than failing, so these 46 are permanently unmeasurable, and a future component named `.status-pill` or `.menu-item` would silently inherit forty-line rules written for a deleted implementation.

<details><summary>Evidence</summary>

I cross-referenced all 462 class selectors in `styles.css` against every `.vue`/`.ts`/`index.html`, and separately extracted the dynamic class-name prefixes the templates actually build (`csq-`, `tone-`, `dx-`, `dx-sev-`, `kw-fav-`, `msg-`, `palette-kind-`, `rs-`) so families like `.csq-ok` and `.tone-warn` are correctly counted as live. 46 selectors survive with no possible producer. They cluster by the component that replaced them:

- **`.ubar-track` (2720) / `.ubar-fill` (2727)** — `UsageBar.vue` renders `<NProgress …/>` between `.ubar` and `.ubar-text`; nothing emits a track or fill element.
- **`.acc-head` (1527), `.acc-head:hover`, `.acc-caret` (1539), `.acc-caret.open`** — `Accordion.vue` is now `<NCollapse><NCollapseItem>`; only `.acc` and `.acc-count` are still emitted.
- **`.status-pill` (2688), `.status-ok`, `.status-warn`, `.status-err`** — `StatusBadge.vue` is `<NTag :color=…>`; it emits no class at all.
- **`.pf-status` (2487), `.pf-active`, `.pf-closed`, `.pf-failed`** — `PortForwards.vue:90` renders status through `NTag` with `type: STATUS_TYPE[…]`; the string `pf-` appears nowhere in the SPA.
- **`.rowmenu-cell` (2536), `.rowmenu` (2541), `.menu-item` (2577) + `.danger`/`:hover` variants, `.menu-sep` (3029), `.menu-portal` (3036), `.has-sub` (3043), `.sub-arrow` (3050)** — a complete hand-rolled kebab/submenu implementation. `KebabMenu.vue` is now `<NDropdown>` and passes `class: 'menu-danger'`, the one class in that block still reachable.
- Plus `.linkbtn`, `.modal-backdrop`, `.dialog-field`, `.sort-ind`, `.ns-select`, `.cols-menu`, `.col-toggle`, `.ov-lbl`, `.chart-tip`, `.row-checked`, `.chk`, `.drawer-head`, `.drawer-close`, `.repl`, `.yaml-edit`, `.tall`, `.yaml-edit-cm`, `.form-tab`, `.form-actions`, `.create-btn`, `.dock-slot`, `.helm-badge`, `.tree-toggle`, `.row-actions`, `.adv-toggle`, `.yaml-view`, `.update-chip`.

The count is a floor, not a ceiling: my matcher counts a selector as live if its bare name appears anywhere in a source file, so `.kebab`, `.menu` and `.submenu` — all in the same abandoned block — were spared only because the words appear in prose comments.

</details>

_No verdict could be paired with this finding — unverified._

---

### [UNPAIRED] Four HTTP endpoints have no caller in the shipped product, including a whole SSE controller that opens a real API-server watch

**`kweblens-web/src/main/java/org/alexmond/kweblens/web/api/ResourceWatchApiController.java:34`** · dimension `Code that cannot execute` · finder confidence high

**Consequence.** #1 is the one with teeth: it is a live SSE endpoint that grabs a real fabric8 watch against the API server, and it is maintained as such — `docs/design/watch-fanout.md:189` records it leaking three API-server watches for 300 s under measurement, and `SseKeepAlive` was attached to fix a leak nobody could ever trigger through the product. It is unauthenticated reachable surface in open-mode (a GET) that costs an apiserver watch per request. #3 is a user-facing loss: the previous-run log is the only place a CrashLoopBackOff's cause survives after a restart, the server can serve it, and the log dock offers no way to ask for it.

<details><summary>Evidence</summary>

I enumerated all 79 mappings across the 31 `@RestController`s and matched each against `api.ts` (which I read in full — it is the SPA's only HTTP surface) plus every `.vue`/`.ts`. Four have no consumer:

1. **`GET /api/v1/clusters/{clusterId}/resources/{resourceId}/watch`** — `ResourceWatchApiController`, an entire class. The SPA's only watch is the *other* one: `useResourceData.ts:127` builds `…/resources/${encodeURIComponent(sel.id)}/objects/watch` (`ObjectApiController:112`). Nothing in the repo references the row-level variant outside `docs/design/watch-fanout.md` and one test's controller-name list.
2. **`GET …/logs/sources`** (`MultiLogApiController:145`). Its own javadoc states the purpose: *"lets the UI show what it is about to stream (and how many) before opening the connection"* — but the UI never does. `useMultiLogs.ts:132-164` gets the same data from the stream's own `sources` SSE event and renders truncation at `DockSession.vue:219`, so the endpoint is redundant *and* its stated contract is false.
3. **`GET …/pods/{namespace}/{pod}/log/previous`** (`MultiLogApiController:134`) — the crashloop diagnostic (`kubectl logs --previous`). Zero references in `kweblens-ui/src`.
4. **`GET …/metrics/prometheus`** (`MetricApiController:55`) — reports whether a Prometheus backend was discovered. Zero references.

Verified: `grep -rn "metrics/prometheus|logs/sources|log/previous|resources/{resourceId}/watch"` over `kweblens-ui/src` and `scripts/` returns nothing.

</details>

_No verdict could be paired with this finding — unverified._

---

### [UNPAIRED] The knip gate reports zero unused exports but structurally cannot see inside `export const api = {…}`, where two dead members were hiding

**`kweblens-ui/src/api.ts:274`** · dimension `Code that cannot execute` · finder confidence high

**Consequence.** The one automated check the project has for "exported TS symbol with no importer" is blind to the file where dead client code is most likely to accumulate. Every future endpoint the SPA stops calling will pass `npm run check` and CI exactly as `clusterConfig` and `helmValuesDelete` have — the same shape as the `<ErrorNotice>` import that vue-tsc and eslint both waved through.

<details><summary>Evidence</summary>

`npm run check` runs `prettier && vue-tsc -b && eslint src && knip && vitest run --coverage`, and knip is clean — I re-ran it with every export check forced on and it still found nothing:
```
$ npx knip --include exports,types,nsExports,nsTypes,enumMembers,duplicates
(no output)
```
That is because the entire HTTP client is one export:
```ts
// api.ts:274
export const api = {
  …62 members…
};
```
knip sees `api` imported by ~30 files and marks it used; the 62 members are invisible to it. Enumerating them by hand against every other `.ts`/`.vue` finds two with no call site anywhere: `api.clusterConfig` (api.ts:287) and `api.helmValuesDelete` (api.ts:415) — the two defects reported separately above. The same shape holds for `export const podFiles = {…}` (api.ts:~240, 6 members).

A second hole in the same gate: knip's vitest plugin treats `*.test.ts` as entry points, so a symbol whose only importer is its own test counts as used. `warningsTable.ts:56 warnMessageFloor` and `responsive.ts:35 PANE_CONTAINER` are both in that state (each is documented as deliberate, so neither is a defect — but the gate could not tell either way).

</details>

_No verdict could be paired with this finding — unverified._

---

## Appendix — refuted (2026-08-06 run)

None. No finding in the partial run was refuted — but 10 of its 37 agents never ran, so that
is a statement about coverage, not about the code.


# Overview pages — audit and redesign (GH#139)

> **Status: HISTORY. Written 2026-07-30, superseded 2026-08-07 — everything below is the
> state of the overviews *before* the redesign, not now.** GH#139 closed on 2026-07-30 and all
> seven "fixes, as issues" have shipped. Read the present tense below as past tense: the
> defects it names are the ones that were removed. Do not open work from this file, and do not
> "restore" a behaviour it describes — section 4 in particular argues for predicates the code
> deliberately replaced. What actually ships is in the [What shipped](#what-shipped-added-20260807)
> section at the end, checked against the code on 2026-08-07.

What the Cluster and Workloads overviews should answer, why the current ones fell short, and
the specific fixes. Each fix below had its own issue.

## What exists today

**Cluster overview** (90 lines): three stat cards (Nodes · N ready, Namespaces, Warnings), the
API-server URL, cluster CPU and memory charts, and a warnings table.

**Workloads overview** (93 lines): seven stat cards (total + "N ready" per kind, red when
`total - ready > 0`) and a recent-events pane.

Both are reasonable scaffolds. The problems are consistent and mostly structural rather than
cosmetic.

## The framing

Borrowed from the detail-sections audit, because it worked there: **what question does this
screen answer?**

A grid of counts answers *"how many"*. That is rarely why anyone opens a dashboard. The
question is almost always **"is anything wrong, and what?"** — and the current overviews get
within one step of answering it and then stop.

## Findings

### 1. The overview knows what is broken and throws it away

`WorkloadsOverview` computes `ready = objs.filter(healthy).length` and renders
`total - ready` as a red card. It has the unhealthy objects **in hand** at that moment and
discards them, keeping only the count. So the screen can say "one of your 52 Deployments is
unhealthy" but cannot say *which*, and the user goes hunting through a 52-row list.

This is the single highest-value fix: name the unhealthy things.

### 2. Cards are dead ends

`StatCard` is `defineProps` + a div — no click, no navigation. Every card is a fact you cannot
act on. "2 warnings" should be one click from the warnings; "51 of 52 ready" should be one
click from the unready one.

### 3. The counts are computed by listing everything — twice

`WorkloadsOverview` calls `api.objects(cluster, kind)` for **seven kinds** and counts client
side. That transfers every pod, deployment, replicaset… to the browser to produce seven
numbers. On the lab cluster that is 157 ReplicaSets and 65 pods; on a large cluster it is the
"~400 MB pod LIST" failure mode the competitive review noted in k9s.

Worse, it is **duplicated work**: a `/counts` endpoint already exists and the nav badges
already use it. The overview simply does not use it.

And the endpoint is not a real count either — `CountApiController` does
`resources.listRaw(...).size()`, so the listing still happens, just server-side (which is
better: one in-cluster transfer instead of one to each browser). A genuinely cheap count may be
possible via a `limit=1` list and `metadata.remainingItemCount`, which is how `kubectl` avoids
paging everything — **unverified against fabric8**, and worth checking before assuming.

### 4. Two health predicates are wrong

- **Jobs**: `healthy: succeeded > 0`. A Job that is *currently running* has `succeeded == 0`,
  so every in-flight Job is reported as unhealthy. False alarms train people to ignore the
  card.
- **CronJobs**: `healthy: () => true` — a CronJob can never be unhealthy, so a **suspended**
  CronJob, or one whose last run failed, is invisible. Silently always-green is worse than
  absent, because it implies a check happened.

### 5. Silent truncation, again

Warnings are `.slice(0, 30)` and events `.slice(0, 25)` with nothing saying so. The same
pattern already fixed in the multi-log source cap and the relation lists: showing a subset
without saying it is a subset reads as "this is everything".

### 6. The namespace filter is ignored

The brand bar has a namespace selector that scopes every list view. The overviews ignore it, so
selecting a namespace and clicking Overview silently shows the whole cluster. `/counts` already
accepts a namespace parameter.

### 7. Whole categories have no overview

Only Cluster and Workloads exist. The categories where an overview would earn its place:

- **Network** — Services with **no ready endpoints** is the classic silent breakage, and it is
  now cheap to detect because the relation joins (GH#136) already resolve endpoints.
- **Storage** — PVCs `Pending` (unbound), and capacity pressure.
- **Config** — ConfigMaps/Secrets that **nothing mounts** (now detectable via `mountedBy`),
  which is the safe-to-delete list.
- **Access Control** — less obviously valuable; defer until asked.

## What an overview should do (the rules adopted)

1. **Lead with what needs attention, then totals.** A count is interesting only when it is
   unexpected.
2. **Name the offenders.** If the screen can compute "3 unhealthy", it can list the three.
3. **Every number is a link** into the filtered list.
4. **Say when data is partial or unavailable, and why.** The diagnostics panel already reports
   detected capabilities; overviews should be equally honest rather than rendering an empty
   chart.
5. **Do not duplicate the list views.** If a card would just be "the first five rows", link
   instead.

## The fixes, as issues

| # | Fix | Why it matters |
|---|---|---|
| 1 | Name the unhealthy objects instead of only counting them | Highest value; the data is already in hand |
| 2 | Make cards and rows click through to the filtered list | Every fact becomes actionable |
| 3 | Use `/counts` instead of listing every object client-side | Removes a whole-cluster transfer to the browser |
| 4 | Fix the Job and CronJob health predicates | Two live false-signal bugs |
| 5 | Report truncation in the warnings and events tables | Consistent with the rest of the app |
| 6 | Honour the namespace filter | The selector silently does nothing here |
| 7 | Add Network / Storage / Config overviews | The relation joins make the useful checks cheap |

Sequencing: 4 and 5 are small and independent. 3 unblocks 7 (which should not repeat the
list-everything mistake). 1 and 2 together are the redesign proper.

## What shipped (added 2026-08-07)

All seven landed. Verified against the code on 2026-08-07, not against a changelog. Where the
implementation differs from the proposal, the difference is stated — the proposal is not the
record of what exists.

| # | Fix | Where it lives now |
|---|---|---|
| 1 | Name the unhealthy objects | `OverviewApiController` returns "per-kind tallies plus the **named** objects needing attention"; `CategoryOverview.vue` renders them as rows |
| 2 | Cards click through | `StatCard.vue` renders a real `<button>` and emits `select` when a destination exists, and stays a plain `<div>` when there is nowhere to go |
| 3 | Stop listing everything client-side | **Not** by calling `/counts` from the browser, as proposed. The whole overview moved server-side: `CategoryOverview` makes one `GET …/overview/{category}` call instead of seven `objects` calls. Separately, `/counts` itself became a real count — `ResourceService.count` uses `withLimit(1)` plus `metadata.remainingItemCount`, with an explicit fallback when the field is absent, so this file's "unverified against fabric8, and worth checking" is now verified and implemented |
| 4 | Job / CronJob predicates | `WorkloadHealth.job()` keys on the **Failed** condition and its javadoc names `succeeded > 0` as the predicate it replaced. `WorkloadHealth.cronJob()` is a deliberate three-state suspended/OK with a documented rationale for *not* guessing at failed runs — a failed run surfaces as a failed Job. **Section 4 above is the argument for the behaviour that was removed; do not re-derive it from there** |
| 5 | Report truncation | `CategoryOverview.vue` renders "Some kinds have more affected objects than shown", a separate note for kinds it could not check, and one for truncated events |
| 6 | Honour the namespace filter | `namespace` is a prop, is in the re-fetch watch, is passed to the API, and the page states its own scope on screen ("Namespace: x" / "All namespaces") |
| 7 | Network / Storage / Config overviews | `OverviewApiController` switches on `workloads`, `network`, `storage`, `config`; an unknown category 404s rather than rendering as a healthy empty dashboard. **Access Control** was deferred, as this file recommended, and is still not built |

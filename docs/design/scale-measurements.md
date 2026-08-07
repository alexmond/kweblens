# Where kweblens actually degrades: measurements, not guesses

Date: 2026-08-01. Follows `roadmap.md`, which ranked **bounded lists (T2)** second and said its
size could not be judged because "no external scale numbers exist … I recommend a KWOK rig
*before* committing to T2's size."

This is that measurement pass, run against the in-repo simulator. **It changes the
recommendation.**

## The headline

The server is not the problem. The browser is, and not because of payload size — because
**the resource list puts one DOM row on the page per object, with no virtualisation.**

So T2 as scoped (server-side `limit`/`continue` paging) attacks the wrong half first.

## What was measured

Simulator (`KWEBLENS_SIMULATOR_ENABLED=true`), `size` objects per kind across 5 namespaces,
`-Xmx2g`.

| objects/kind | seed | `GET /resources/pods` | payload | `GET /counts` | `GET /nav` |
|---:|---:|---:|---:|---:|---:|
| 200 | 23 s | 40 ms | 17 KB | 278 ms | 30 ms |
| 1 000 | 43 s | 80 ms | 87 KB | 361 ms | 25 ms |
| 3 000 | 134 s | 125 ms | 263 KB | 699 ms | 45 ms |

Re-measured at 3 000 while the browser was struggling: pods **145 ms / 272 KB**, counts
**896 ms**, health UP. The API is comfortable.

Linear in object count, ~88 bytes/row. Extrapolating to 15 000 pods gives roughly 600 ms and
1.3 MB — slow enough to want paging eventually, nowhere near the thing that breaks first.

> **CORRECTION, 2026-08-03 (#276). That extrapolation is wrong, because the simulator's
> objects do not resemble real ones.** Re-measured against the live cluster on an idle box
> (load 2.45): **~8 KB per pod and ~43 KB per secret**, 50–500× the 88 bytes/row above. The
> simulator generates objects with no annotations, no real `data` and trivial
> `managedFields`; a real one carries all three. The same 15 000-pod extrapolation on real
> objects is **~120 MB**, not 1.3 MB.
>
> | kind | objects | payload | of which `data` | of which `managedFields` |
> |---|---:|---:|---:|---:|
> | pods | 87 | 750 KB | — | 276 KB |
> | replicasets | 168 | 1.08 MB | — | 525 KB |
> | configmaps | 97 | 1.63 MB | 1.54 MB | 37 KB |
> | secrets | 150 | **6.55 MB** | 6.41 MB | 56 KB |
>
> So "the API is comfortable" held for the simulator and not for the cluster. It also changes
> what T2 should do first: **88% of those bytes are fields nothing on screen consumes**, and
> projecting them away needs no pagination contract and no renegotiation of filter semantics.
> Paging is still the eventual answer for genuinely large kinds; it is no longer the first
> move. See #276.
>
> The lesson is the one this document already makes about latency, applied to payload: **a
> rig whose objects are unrepresentative measures the rig.** Recommendation 3 below — make
> the seeder realistic or stop calling it the scale rig — was right, and understated.
>
> **Followed up:** #276 shipped the projection this correction argued for. What it actually
> removed, measured the same way, is [at the end of this document](#what-the-projection-removed-276).
> The rig itself was then rebuilt to produce representative objects — measured side by side
> against the live cluster [further down](#the-rig-rebuilt-2026-08-04).

## The thing that breaks first

At **300** objects, on the pods list:

```
server rows       : 300
rows in DOM       : 300      <- one row per object
cells in DOM      : 3 600
total DOM nodes   : 14 353
table scrollHeight: 11 999 px
```

One DOM row per object, and each row carries real components — status pills, container
squares, usage bars. Extrapolating that shape:

| objects | DOM nodes | table height |
|---:|---:|---:|
| 300 | ~14 000 | 12 000 px |
| 3 000 | ~143 000 | 120 000 px |
| 15 000 | ~715 000 | 600 000 px |

Browsers do not do well with several hundred thousand nodes. This is a **structural**
property, measurable at any load, and it is independent of how the data arrives.

**Virtualise the list first. Page the API second.** Windowing to the visible rows caps DOM
cost at a constant regardless of collection size, and it works today without touching the
access layer, the watch topology or three clients' contracts. Paging remains worth doing —
it bounds payload, memory and apiserver load — but it does not fix a 715 000-node page,
and doing it first means the visible symptom survives the work.

## Two things about the rig that matter more than the numbers

**1. The simulator cannot validate a paging implementation.** Its `KubernetesCrudDispatcher`
ignores `limit` outright:

```
GET <mock>/api/v1/pods?limit=100
  items returned   : 3000
  continue token   : None
  remainingItemCount: None
```

A paging implementation developed against this rig would appear to work while paging
nothing — a false pass of exactly the kind that is hardest to catch. **T2 needs a real API
server** (KWOK on kind, or the live cluster) from the first commit, not as a final check.
This also means the roadmap's open question — whether `metadata.remainingItemCount` is
reachable through fabric8 — still cannot be answered here.

**2. The simulator tops out around 3 000/kind.** `SimulatorSeeder` issues one HTTP POST per
object (~19 ms each), so 8 000/kind across 5 kinds is roughly 12 minutes of seeding and my
first attempt at it simply timed out. If the simulator is to stay the scale rig, seeding
needs to be bulk; otherwise the ceiling is ~3 000 and larger questions need KWOK.

## What is NOT trustworthy here, and why

**Every browser timing in this pass was taken on a box at load average 19–23** (a concurrent
agent was building). Under that load the shell rendered in 1.1 s while the pods list did not
produce a row in 120 s, and a single long-task measured 8.1 s. The ratio suggests something
real, but I cannot separate the application's cost from the machine's, and I nearly reported
a hang that was partly my own measurement environment.

So: **the DOM-shape numbers stand** (counting nodes does not care about CPU contention), and
**the latency numbers do not**. Time-to-first-row, long-task duration and any "it hangs at N
objects" claim need re-running on an idle box before anyone quotes them.

Also unmeasured: memory. The heap readings collected alongside the table above were pure GC
timing noise (683 MB at 200 objects, 149 MB at 3 000) and are omitted rather than reported.

## What the projection removed (#276)

The correction above argued for projecting the payload before paging it. That shipped;
here is what it actually removed, measured the same way — same cluster, same endpoint, idle
box (load 3.9 for both runs), HTTP body bytes.

| kind | objects | before | after | saving | bytes/row before → after |
|---|---:|---:|---:|---:|---|
| pods | 87 | 678.8 KB | 430.9 KB | 36.5% | 7 990 → 5 072 |
| replicasets | 168 | 980.6 KB | 508.0 KB | 48.2% | 5 977 → 3 096 |
| deployments | 59 | 351.5 KB | 187.1 KB | 46.8% | 6 102 → 3 248 |
| services | 62 | 97.9 KB | 57.5 KB | 41.3% | 1 617 → 950 |
| configmaps | 97 | 1.55 MB | 54.3 KB | 96.6% | 16 761 → 573 |
| secrets | 150 | 6.24 MB | 72.9 KB | 98.8% | 43 622 → 498 |
| **total** | | **9.85 MB** | **1.28 MB** | **87.0%** | |

(The absolute figures differ by a few percent from the correction's table because the cluster
moved between the two passes; the shares do not.)

`ListProjection` (`web/api`) drops `managedFields` from every list payload and ships
ConfigMap/Secret keys without their values. The cost is moved, not removed: opening a
ConfigMap or Secret drawer now fetches the whole object — 16–26 ms and ~1.3 KB for a typical
one, 60 ms and 948 KB for the largest Secret on this cluster — and every other kind's drawer
fetches nothing extra. **A row is no longer the object**, which is the thing to remember before
reading a field off one.

Two conclusions stand unchanged. Payload claims have to be taken against a real API server,
because the simulator's objects are not the ones the product serves. And the DOM finding above
is untouched: one row per object is still one row per object, so virtualisation is still the
first thing to build — this bought room, not a fix.

## What `/counts` cost, and what it costs now (T2, 2026-08-03)

`/counts` computed every badge as `listRaw(...).size()` — a full LIST of every kind to
produce an integer. Measured on the live cluster (`default`, 118 kinds, 1 561 objects), not
the simulator:

| | before | after |
|---|---:|---:|
| `GET /counts`, warm | 330 ms | **112 ms** |
| `GET /counts`, first call after start | 1.66 s | 1.44 s |
| bytes pulled from the API server (39 measurable kinds) | **22.1 MB** | **111 KB** |
| kinds returned | 118 | 118 |

The latency was never the scandal — 330 ms for a sidebar is survivable. The 22 MB is: half
of it is the CustomResourceDefinitions' own OpenAPI schemas (10.3 MB for 79 objects) and
6.5 MB is Secret data, all decoded into heap and thrown away to keep 118 integers, on an
endpoint the client re-fetches on **every namespace switch**. And the cost scales with the
cluster's total content while the numbers do not: the same endpoint on a cluster ten times
this size costs ten times as much to produce the same 118 badges.

The replacement asks each kind for one item and reads `metadata.remainingItemCount`
(`ResourceService.count`). That field is **best-effort** — the API server may omit it — so
the absence is handled explicitly rather than guessed at: a page with no continue token is
the whole collection and its size is exact (this also covers a server that ignores `limit`
outright, which `componentstatuses` on this cluster does), and a page that *is* truncated
without a `remainingItemCount` falls back to the full list. All three branches were probed
against the live API server; every derived count matched `kubectl`, and a sweep comparing
all **118** badge numbers against the full object list for the same kind found **0
mismatches**.

Two things this does not change. It is still a full LIST per kind when the API server
refuses to say how many remain — the fallback is deliberate, because a count that silently
becomes wrong is worse than one that is slow. And the fabric8 CRUD mock **ignores `limit`**,
so the test that proves the flag is sent asserts on the exact outgoing query string
(`ResourceCountTest`), not on a seeded object count, which would pass either way.

## Watch fan-out (T2, 2026-08-03)

Measured, decided and written up separately in [`watch-fanout.md`](watch-fanout.md). The
short version: the ratio is exactly one API-server watch per SSE subscriber — but that was
not the number that mattered, because a watch on a *quiet* kind outlived its departed
subscriber by over five minutes (an `SseEmitter` discovers a disconnect only from a failed
write). One operator walking twenty kinds in one tab held **22 open watches**. A 15 s
keepalive turns the ceiling back into "one per list view currently on screen, released
within ~30 s", and at that ceiling sharing a watch across subscribers is not worth its
lifecycle risk for a single-operator product.

## The rig, rebuilt (2026-08-04)

Recommendation 3 below said to make the seeder realistic or stop calling this the scale rig.
It has now been made realistic. This section is the evidence, because the claim "the
simulator is representative" is exactly the kind that got believed once already without
being checked.

**How to re-check it.** `scripts/payload-bytes.mjs` against any running instance. It reports
two different numbers per kind and the difference matters: `B/row` is the **projected** list
payload (`/objects`) divided by its row count — what the browser pulls, post-#276, so
without `managedFields` or ConfigMap/Secret values — while `obj mean/p50/p90/max` is the
**full** single object (`/object`), sampled, which is the object as the cluster stores it and
the number to compare across rigs. `mf%` is the `managedFields` share, the most reliable tell
that a generated object is not a real one.

```
PORT=8131 CLUSTER=default node scripts/payload-bytes.mjs   # the live cluster
PORT=8132 CLUSTER=sim     node scripts/payload-bytes.mjs   # the simulator
```

### Bytes per object, live cluster vs simulator, before and after

Same box, same afternoon, load 1.2–5.5; simulator at the default `size=200`, sample 60
objects per kind. "before" is the seeder as it stood at #282.

| kind | live mean | sim **before** | sim **after** | after ÷ live |
|---|---:|---:|---:|---:|
| pods | 7.8K | 739 B | 8.4K | 1.08 |
| deployments | 5.9K | 429 B | 6.1K | 1.03 |
| replicasets | 5.9K | 425 B | 6.5K | 1.10 |
| services | 1.6K | *kind absent* | 2.1K | 1.31 |
| configmaps | 16.9K | 376 B | 21.2K | 1.25 |
| secrets | 53.4K | 289 B | 44.6K | 0.84 |
| ingresses | 1.7K | 559 B | 2.0K | 1.18 |
| nodes | 10.3K | 809 B | 9.8K | 0.95 |
| events | 1.1K | *kind absent* | 1.1K | 1.00 |

The before column is the finding, not the after: every kind was out by **6× to 185×**, and
two of the nine did not exist at all. Afterwards no kind is out by more than 1.31×.

The distribution matters more than the mean, and it was the part that was completely absent
— every seeded object was within a byte or two of every other object of its kind:

| kind | live p50 / p90 / max | sim **before** | sim **after** |
|---|---|---|---|
| pods | 7.3K / 11.4K / 19.5K | 740 / 741 / 741 | 7.4K / 11.6K / 17.3K |
| replicasets | 5.2K / 9.1K / 14.4K | 425 / 425 / 426 | 5.6K / 10.4K / 12.8K |
| configmaps | 2.0K / 31.9K / 267.7K | 377 / 377 / 378 | 3.3K / 88.1K / 151.1K |
| secrets | 9.8K / 80.2K / 673.3K | 289 / 289 / 290 | 15.7K / 78.5K / 743.5K |

And the composition, which is what makes the sizes reproduce rather than merely match — the
`managedFields` share was **0% on every seeded kind** and is 25–48% on real workloads:

| kind | live mf% | sim before | sim after |
|---|---:|---:|---:|
| pods | 37 | 0 | 29 |
| deployments | 46 | 0 | 42 |
| replicasets | 48 | 0 | 41 |
| services | 40 | — | 43 |
| nodes | 25 | 0 | 24 |
| events | 29 | — | 27 |

Projected list bytes per row track the same way: pods 5 028 live vs 739 before vs 5 976
after; secrets 498 vs 284 vs 882; nodes 7 878 vs 810 vs 7 581.

### What still does not match, and why

- **Services and Ingresses have almost no spread** (sim p50 ≈ p90 ≈ max), where the live
  cluster's range 1.6–2.7K and 1.8–2.2K. Both are generated from one template with a single
  optional port; nothing else about them varies. It is a small absolute gap on two small
  kinds, and it is the honest remaining hole in "distribution matters".
- **ConfigMaps and Secrets are 25% high / 16% low respectively**, and their tails are drawn
  from four fixed buckets rather than from anything real. The tail is present and the right
  shape; its exact quantiles are a fit, not a measurement.
- **The live cluster is one home-lab k3s cluster**, 90 pods and 4 nodes. It is the only real
  cluster available here, so "representative" means "representative of that". A cluster with
  a service mesh (sidecars, injected annotations, large CRDs) has bigger pods than either.
- **The mock API server still ignores `limit`** — completely unchanged by this work, because
  it is a property of `KubernetesCrudDispatcher`, not of the objects. See below.

### Seeding cost, which got worse

| objects/kind | objects seeded | **before** | **after** | after, per object |
|---:|---:|---:|---:|---:|
| 200 | 1 206 → 1 641 | 8.8 s | 13.6 s | 8.3 ms |
| 1 000 | ~6 000 → 8 070 | 43 s * | 74 s | 9.2 ms |
| 3 000 | ~18 000 → 23 933 | 134 s * | **431 s** | 18.0 ms |

\* the 2026-08-01 figures at the top of this document, on the same box.

Two things in that table. The seeder now creates **8 kinds per index instead of 6** (Services
and Endpoints are new), so some of the increase is more objects rather than slower ones. And
the per-object cost is no longer flat: 8.3 ms at 200 against 18.0 ms at 3 000, where the old
rig was ~7.4 ms at both. Bigger objects mean more serialisation per `POST` and more GC
pressure, and both grow with what is already stored.

**The obvious lever does not work.** `kweblens.simulator.payload-scale` shrinks the generated
ConfigMap/Secret bodies; at `size=3000` with `payload-scale=0.05` seeding took **430 s**, i.e.
the same 431 s. The data bodies are not the cost — the per-object HTTP round trip and the
object graph are. `payload-scale` does cut memory (362 MB post-GC at 3 000 with 0.05; the
generated ConfigMap+Secret data at full scale is ~193 MB by itself), so it is worth having,
but it must not be sold as a way to seed faster. Heap readings while seeding are GC-timing
noise, as this document already warns; only the post-forced-GC number above is worth quoting.

**So the trade, stated plainly:** 3 000 objects/kind of *realistic* objects costs 7 minutes of
startup, against 2¼ minutes for 18 000 unrepresentative ones. Below ~1 000/kind the rig is
still comfortable (74 s). Above that, waiting seven minutes to get a rig that still cannot
answer the paging question is a bad trade — that is KWOK's job.

### What the rig now shows that it could not before

At `size=1000` a pods list is **5.88 MB / 499 ms**; at `size=3000` it is **17.6 MB / 1.33 s**,
against the old rig's 263 KB / 125 ms at the same count. That is the 2026-08-01 headline
("the API is comfortable, ~88 bytes/row") disappearing: the rig now reproduces the payload
problem instead of hiding it, and the 15 000-pod extrapolation from it is ~88 MB, in the same
league as the ~120 MB measured against real objects rather than 50× under it.

It also renders states it never could. About one pod in six is CrashLoopBackOff,
ImagePullBackOff, unschedulable, OOM-killed, evicted or completed; a tenth of workloads are
below their replica count; one Service in fourteen has no Endpoints and one in twenty has only
`notReadyAddresses`; one node is NotReady; there are Warning events. Every state occurs within
the first hundred indices, so `size=100` exercises all of them, and everything is deterministic
in the object's index so two runs are comparable. This is what `.ov-card.danger`, `.ov-card.warn`
and the row status pills need in order to be measurable at all — the contrast checker has
reported them `not present` for its entire existence.

### Verdict: can the simulator be trusted as the scale rig?

**For payload, rendering and DOM questions: yes, now.** Bytes per object are within 1.31× of
the live cluster on every kind, the composition is right (managedFields are 24–43% of the
right kinds), and the distribution has a real tail. A measurement taken here is now a
statement about kweblens rather than about the seeder. Re-check it with
`scripts/payload-bytes.mjs` rather than trusting this paragraph.

**For paging: no, and that is unchanged.** `KubernetesCrudDispatcher` still ignores `limit`
outright, so a paging implementation developed here would appear to work while paging nothing.
Realistic objects do not fix that and were never going to. **T2 still needs KWOK or the live
cluster from the first commit** — see "Two things about the rig" above, which stands in full.

**For sizes past ~3 000 objects/kind: no.** Seven minutes of seeding, superlinear, with no
lever that helps. KWOK is the answer for anything larger.

## Is server-side paging still worth building? (T2, 2026-08-05)

T2 was scoped as `limit` + continue tokens in `ResourceService` plus a cap-with-load-more in
the table. Four things have landed since that scope was written, all of them aimed at the same
problem from other directions: the list projection (#279, issue #276), the cheap `/counts`
(#283), virtualisation from 50 rows (#286), and a simulator whose objects are within 1.31× of
live ones (#287). This section re-asks the question against measurements rather than against
the plan, and **it moves the answer**: the wire and the browser are now comfortable well past
any plausible single-operator cluster, and the thing that is not bounded is the **JVM heap**.

**Conditions.** Jar built from `main` at 4d50a08, `-Xmx2g -XX:+UseG1GC`, simulator at 200 /
1 000 / 3 000 objects per kind across 3 namespaces. Box load recorded at every step and never
above 5.4 (contrast the 2026-08-01 pass this document already disowns, taken at 19–23). Server
timings are medians of 3 `curl` runs; heap is `jstat`/`jcmd` from **outside** the JVM; browser
numbers are Playwright.

### First, the instrument — because it had been setting the budgets

`perf-sweep`'s LOAD column was never time-to-first-row. It waited for
`.n-data-table-tbody tr, .count, .cluster-overview, .empty`, and `.count` is
`ResourceListView`'s items badge, which has **no `v-if`**: it renders "0 items" the moment the
list shell mounts. The positive control, taken at `size=3000` on one instance in one sitting:

| instrument | Pods list, 3 000 objects, 16.78 MB |
|---|---|
| `perf-sweep` as merged | `0 rows` · **204 ms** · `ok` |
| strict wait for a row | **1 879 ms** |

The old tool called a page it never waited for a fast, passing page — 9× understated, and the
understatement is worst exactly where the number matters, because the slower the server the
earlier the badge wins. Across a full 44-leaf sweep, **30 of 44 pages** were reporting a "load"
for a list that had produced no row. Fixed: a row is now the only thing that ends the
measurement, an empty collection prints `—` rather than a duration, and the summary says how
many pages had rows to time at all.

**This does not touch #286.** Its threshold decision was argued entirely on BLOCK, which comes
from a `PerformanceObserver` and never involved the selector. Re-measured here by rebuilding a
`VIRTUAL_FROM=150` jar and running both arms against a 90-row simulator:

| kind, 90 rows | BLOCK @150 | BLOCK @50 | #286 recorded |
|---|---:|---:|---|
| Pods | 1 519 ms | 406 ms | 1 519 → 391 |
| Deployments | 1 218 ms | 299 ms | 1 729 → 295 |
| Config Maps | 769 ms | 193 ms | 953 → 185 |
| Secrets | 994 ms | 224 ms | — |

DOM rows 90 → 20 and DOM nodes 3 767 → 1 257 across the same pair. The decision reproduces
independently; nothing in #286 needs correcting.

### The wire: comfortable, and not the constraint

Projected list payload and total request time, median of 3:

| kind | 200 | 1 000 | 3 000 |
|---|---|---|---|
| pods | 1.14 MB · 61 ms | 5.61 MB · 246 ms | 16.78 MB · 637 ms |
| deployments | 776 KB · 45 ms | 3.85 MB · 208 ms | 11.37 MB · 488 ms |
| replicasets | 740 KB · 49 ms | 3.70 MB · 186 ms | 11.11 MB · 580 ms |
| services | 232 KB · 22 ms | 1.13 MB · 76 ms | 3.41 MB · 314 ms |
| configmaps | 177 KB · 60 ms | 884 KB · 224 ms | 2.59 MB · 805 ms |
| secrets | 172 KB · 101 ms | 862 KB · 428 ms | 2.53 MB · 1 224 ms |
| ingresses | 251 KB · 24 ms | 1.23 MB · 93 ms | 3.70 MB · 203 ms |

Linear in object count, and the slowest kind at 3 000 objects is 1.2 s for a list nobody opens
by accident. This is the half #279 fixed: Secrets are 2.53 MB for 3 000 objects where the
unprojected payload would be ~134 MB.

**`/counts` on this rig is a rig artefact — do not quote it.** It reads 232 ms / 958 ms /
3 647 ms at the three sizes, which looks like #283 regressing. It is not: the fabric8 CRUD mock
**ignores `limit`**, so `ResourceService.count` gets a full, untruncated page with no continue
token, takes the "the page IS the whole collection" branch, and returns the right number having
done exactly the full LIST that #283 removed. Against a real API server the `limit=1` is
honoured — re-measured here on the live cluster to be sure rather than cited: **110 ms warm**
(1.39 s on the first call after start), matching #283's 112 ms. This is the same trap the top
of this document is about, one layer down: **the mock cannot measure anything whose cost
depends on `limit` being obeyed** — which is also, exactly, why it cannot validate paging.

### The browser: flat, because virtualisation already fixed it

Max main-thread block per list, fixed `perf-sweep`:

| kind | 200 | 1 000 | 3 000 |
|---|---:|---:|---:|
| Pods | 370 ms | 371 ms | 299 ms |
| Deployments | 288 ms | 286 ms | 344 ms |
| Replica Sets | 255 ms | 250 ms | 371 ms |
| Config Maps | 170 ms | 167 ms | 211 ms |
| Secrets | 211 ms | 227 ms | 172 ms |
| Events | 403 ms | 421 ms | 406 ms |

**Flat from 200 to 3 000**, and DOM rows stay at 20 with ~1 000–1 300 nodes at every size. That
is windowing doing exactly what #215 predicted and #286 tuned. Scrolling a 3 000-row list to the
end costs 0–226 ms of block. Time-to-first-row is *not* quoted per size here: it varies with
what the SPA already holds when the leaf is clicked (392 ms for a 3 000-row Deployments list
that takes 488 ms to fetch, because rows arrive from the watch before the list settles), so it
measures navigation order as much as scale. BLOCK is the number that behaved.

**The client-side filter is cheap, which matters for the search argument.** Typing one
character over a full collection, measured from the keystroke to the badge reporting the
filtered total:

| kind, 3 000 objects | filter | of which block |
|---|---:|---:|
| Pods | 353 ms | 294 ms |
| Secrets | 263 ms | 219 ms |
| Config Maps | 232 ms | 180 ms |

#263 kept search client-side because **a substring filter over a server-truncated page reports
"no matches" for an object that exists**. That correctness argument now has no performance
argument pushing against it below 3 000 objects/kind: a third of a second, once, per search.

### The heap: the one thing that is not bounded

Nobody had measured this — the 2026-08-01 pass says so explicitly and omits its readings as GC
noise. Taken properly here: force a full GC (`jcmd GC.run`), read heap used, sample at 50 ms
through the request (`jstat`), force another GC, read again. **Transient** is peak minus
baseline — the heap one list request needs on top of steady state.

| kind | 200 | 1 000 | 3 000 | wire at 3 000 |
|---|---:|---:|---:|---:|
| pods | 44 MB | 194 MB | 557 MB | 16.78 MB |
| configmaps | 55 MB | 239 MB | 702 MB | 2.59 MB |
| secrets | 122 MB | 542 MB | **1 326 MB** | 2.53 MB |

Retained heap returns to baseline after every request, at every size — there is no leak, only a
spike. But the spike is linear in object count and enormous relative to the wire: **3 000
Secrets are 2.53 MB on the wire and 1.33 GB of transient heap**, a ratio of 524×.

The mechanism is not subtle, and it is visible in one line of `ObjectApiController`:

```java
return Serialization.asJson(ListProjection.forList(resources.listRaw(clusterId, descriptor, namespace)));
```

`listRaw` deserialises **the whole collection** into fabric8 model objects first;
`ListProjection` then mutates those same instances (its javadoc says so), and `asJson`
materialises the entire projected result as one `String` before a byte is written. So every
list request holds, simultaneously, the API server's response, a full Java object graph of it,
and the complete output JSON. **#279 moved bytes off the wire; it did not move them out of the
heap** — which is exactly why Secrets show the largest gap between the two.

#### How much of that is the rig?

The simulator's API server is **in the same JVM**, so its own serialisation is inside every
number above. That had to be bounded rather than assumed, so the identical probe was run
against the live cluster, where the API server is out of process and the objects are real:

| kind | objects | wire | transient heap | per object | object mean | heap ÷ object |
|---|---:|---:|---:|---:|---:|---:|
| pods | 88 | 435 KB | 6.9 MB | **81 KB** | 7.6 KB | 10.6× |
| secrets | 151 | 73 KB | 35.5 MB | **241 KB** | 53.4 KB | 4.5× |
| configmaps | 99 | 56 KB | 7.8 MB | **78 KB** | 16.9 KB | 4.6× |

Against the simulator's per-object figures at the same measure — ~190 KB/pod, ~450 KB/secret,
~240 KB/configmap — **the live path costs roughly a third to a half**, so the in-JVM mock is
the larger part of the simulator's spike. It is not the whole story: the effect is real on both
rigs, at the same shape, and the live numbers are the ones to quote. Retained heap returns to
baseline on both, so this is a spike and not a leak.

**Every figure in that table is a lower bound.** A young collection ran inside the measurement
window on every rep, which resets the peak the sampler is watching, so the true spike is at
least this and possibly more. `scripts/heap-probe.sh` marks such rows `>=` rather than
reporting them as exact — the conclusion below only gets stronger if they are, so no attempt
was made to tune GC to remove them.

Two independent runs an hour apart agree to within 3% (pods 6.9 vs 7.0 MB, secrets 35.5 vs
36.4 MB), which is the positive control for the method.

The per-object cost is **flat in N on the simulator** (pods 225 → 199 → 190 KB across 200 →
1 000 → 3 000), i.e. the spike is linear in object count. Live can only be measured at the
sizes the cluster has (88–172 per kind), so the crossing numbers below take the live per-object
cost and the simulator's demonstrated linearity together. **That combination is an inference,
not a measurement** — it is the one place in this section where the two rigs are multiplied
rather than compared, and it is why the trigger below is a thing to measure rather than a
number to trust.

### Verdict

**Server-side `limit`/continue paging is not worth building now — and the thing that is
genuinely unbounded is not what T2 was scoped to bound.**

Three of the four reasons paging was proposed have been answered by other work, and the
measurements say so rather than the changelog:

- **Wire:** 3 000 pods is 16.78 MB in 637 ms; the worst kind is 1.2 s. #279 did this.
- **Browser:** main-thread block is *flat* from 200 to 3 000 objects, 170–420 ms, DOM rows
  pinned at 20. #286 did this.
- **`/counts`:** #283 did this; the only number that looks bad here is the mock ignoring
  `limit`.
- **Search:** filtering 3 000 objects client-side costs ~0.3 s, so #263's refusal to truncate
  server-side is not paying a performance price.

What is left is heap, and it is worse than the wire ever was. On the live path, **a Secrets
list costs ~241 KB of transient heap per object** — for a payload that #279 already reduced to
498 bytes per row. Taking that with the simulator's demonstrated linearity, a spike budget of
**500 MB** is exhausted at roughly:

| kind | objects before a 500 MB spike | at 1 GB |
|---|---:|---:|
| Secrets | **~2 100** | ~4 200 |
| Pods | ~6 300 | ~12 600 |
| ConfigMaps | ~6 500 | ~13 100 |

**Secrets are the kind that gets there first, and 2 000 Secrets is not a large cluster.** Helm
stores one Secret per release *revision*, so 200 releases with ten revisions each is already
there, on a cluster with a hundred pods. This is the number to watch, and it is roughly an
order of magnitude below where a wire-based or DOM-based argument would have put the ceiling.

Two things sharpen it into an operational limit rather than a curiosity:

- The shipped chart sets `resources.limits.memory: 1Gi`
  (`deploy/helm/kweblens/values.yaml`), and a container that exceeds its limit is OOM-killed,
  not slowed down. The failure mode of listing Secrets on a big-enough cluster is therefore a
  **restarting pod**, which is exactly the "spinner that never resolves" T2 was written to
  prevent, reached by a different road.
- At `size=3000` the Secrets list peaked at **2 021 MB against a 2 048 MB ceiling**. It
  completed — but with 27 MB of headroom, and that was with `-Xmx2g`, four times what the chart
  allows.

**And paging is still the wrong first fix**, for the reason the roadmap already records: a
substring filter over a truncated page reports "no matches" for an object that exists, so
`limit` on the list path drags server-side label/field selectors and a renegotiated search
contract along with it — measured above at 0.3 s of client-side cost it is not buying, and
undevelopable on this rig because the CRUD mock ignores `limit`.

The cheaper shape is to stop *materialising* the list rather than to stop *fetching* it. One
list request currently holds three full copies at once (API response, model graph, output
`String`); `Serialization.asJson(...)` returning one `String` is the copy that is pure waste,
and streaming the projected list straight to the response body removes it without touching the
list contract, the filter semantics, or three future clients.

**What is not known, and decides between the two:** how the spike splits between the model
graph and the output `String`. If most of it is the `String`, streaming is the whole fix and
paging stays unbuilt. If most of it is the graph, streaming halves the problem and something
that bounds what is deserialised — paging, or a projecting deserialiser — is eventually needed.
That split is one afternoon's work to measure (a heap histogram at peak, `jcmd GC.class_histogram`
against a live list) and should be measured **before** any of it is designed.

**What to watch, so that crossing it is noticed.** Secret count per cluster, against the
container's memory limit — not pod count, and not payload size, both of which look healthy the
whole way. `scripts/payload-bytes.mjs` reports per-object size; `scripts/heap-probe.sh` is the
other half and is the probe used above, shipped so the number can be re-taken rather than
believed. Run it against a **live** cluster; the simulator's in-JVM API server is inside the
reading.

## Where the list spike actually is (#293, 2026-08-06)

The section above ends by naming the one thing it could not settle: whether the spike is the
output `String` (cheap to fix — stream it) or the deserialised model graph (expensive — stop
materialising the collection). It proposed `jcmd GC.class_histogram` as the instrument. **That
instrument cannot answer the question**, and the reason is worth writing down before the answer:

```
     MB      %      count Δ  class            (151 Secrets, live, GC.class_histogram -all diff)
   24.8   66.0         4139  [B
    9.5   25.2           99  [C
    0.2    0.5         3210  java.util.LinkedHashMap
    0.0    0.0          151  io.fabric8.kubernetes.api.model.GenericKubernetesResource
```

91% is `byte[]` and `char[]` — which is not an answer, because since JDK 9 compact strings
**every `String` is a `byte[]`**. Those arrays are simultaneously the output `String`, Jackson's
scratch buffers, the response body, and every field name and value in the model graph. A
histogram has no call site, so it cannot separate the cheap case from the expensive one.

**JFR allocation sampling can**, because each sample carries a stack:
`jcmd <pid> JFR.start settings=profile jdk.ObjectAllocationSample#throttle=3000/s`, ten list
requests, then attribute by call site and by thread. `scripts/alloc-probe.sh` is that probe.
(Print the stacks with `--stack-depth 48`: `jfr print` truncates to **5 frames** by default,
which lands inside Jackson and netty and attributes half the heap to "an event loop".)

**The sister tool [jvmlens](https://github.com/alexmond/jvmlens) reads the same recording** and
ranks allocation by source-attributed call site, which is exactly this question. Its answer,
pointed at the same live-Secrets recording, agrees line for line with the hand-rolled
attribution below — with one caveat about scope that is worth knowing before running it, in
"Did jvmlens answer it?" at the end of this section.

### The attribution

Live cluster, per list request, mean of 10:

| stage | Secrets (151 obj) | Pods (88 obj) | removed by |
|---|---:|---:|---|
| response bytes — TLS decrypt, netty buffers, `BufferUtil.toArray` | 32.0 MB · 56% | 2.6 MB · 20% | fetching less at once |
| the model graph — Jackson decode into the maps/strings that *are* the objects | 21.1 MB · 37% | 4.8 MB · 36% | fetching less at once |
| `ListProjection` | 0.07 MB · 0.1% | ~0 | — |
| **`Serialization.asJson` — the output `String`** | **0.81 MB · 1.4%** | **2.0 MB · 15%** | **streaming the response** |
| other (JFR's own recording, vert.x timers, Tomcat) | 3.5 MB · 6% | 3.9 MB · 29% | — |
| total | 57.4 MB | 13.4 MB | |

The same split, taken independently by **thread**, which needs no bucketing rules to believe:

| thread | Secrets | Pods | what runs there |
|---|---:|---:|---|
| `vert.x-eventloop-*` | **52.8 MB** | 7.2 MB | the fabric8 client: read, decrypt, parse |
| `tomcat-handler-*` | **1.4 MB** | 3.7 MB | the whole controller — `ListProjection` **and** `asJson` |

**So it is the model graph, decisively, and the histogram's `[B` was mostly not the `String`.**
For a Secrets list, 97.5% of the allocation has already happened before `ObjectApiController`
runs its one line; the output `String` the previous section called "pure waste" is **1.4%** of
it. Pods are the friendlier end of the range at 15%, because a pod's projected row is 5 068
bytes against a Secret's 498.

The dominant stacks, if the buckets are not trusted (Secrets, per request):

| MB | allocation site |
|---:|---|
| 8.4 | `TextBuffer.carr` ← `UTF8StreamJsonParser._finishString2` — decoding a field value |
| 7.6 | `GaloisCounterMode.implGCMCrypt` ← `SSLCipher…decrypt` — decrypting the response |
| 6.2 | `BufferUtil.toArray` ← `ByteArrayBodyHandler.onBodyDone` — **the whole body as one `byte[]`** |
| 5.7 | `BufferImpl.getBytes` ← `VertxHttpRequest.lambda$consumeBytes$0` — per-chunk copies |
| 5.6 | `StringBuilder.<init>` ← `TextBuffer.contentsAsString` — building a model-graph `String` |
| 4.3 | `UnpooledByteBufAllocator.newHeapBuffer` — netty read buffers |
| 3.3 | `Arrays.copyOfRange` ← `String.<init>` ← `TextBuffer.contentsAsString` — the `String` itself |

Every one of those is **the response**, not the reply. `ByteArrayBodyHandler` is the shape of
the problem in one frame: fabric8 buffers the entire list response into a single `byte[]` before
Jackson sees it, and Jackson then builds a complete `GenericKubernetesResource` graph from it,
and only then is the controller called. Two whole-collection copies exist before the line that
makes the third.

### What that rules in and out

- **Streaming the response is not the fix.** It removes 1.4% of a Secrets list and 15% of a Pods
  list. Shipping it alone would be a change that measures well on a microbenchmark of the wrong
  stage and leaves the OOM-kill exactly where it is.
- **Paging is still not the fix**, for the unchanged reason: #263 refuses a server-side `limit`
  so a substring filter cannot report "no matches" for an object that exists, and #292 measured
  that refusal as costing ~0.3 s on 3 000 objects.
- **What is left is the one thing both of those leave alone: fetch the collection in chunks and
  let each chunk become garbage before the next arrives.** `limit` + `metadata.continue` is
  server-side paging *of the fetch*, invisible to the client — the response is still every
  object of the kind, so the list contract, the filter semantics and #263 are all untouched.
  Peak heap stops being a function of collection size and becomes a function of chunk size.

**Caveats.** JFR measures *allocation over a window*, not peak occupancy — it says which code
allocated, which is the question; `heap-probe.sh` remains the instrument for how much is
resident at once. Sample weights are extrapolations, so treat the percentages as ratios, not
readings. The "other" bucket includes JFR's own recording (visible as the `Attach Listener`
thread, ~2 MB/request), which is why the small totals are noisier.

**How stable is it?** Two runs of the same probe an hour apart put the Secrets *total* at 57 MB
and 95 MB — a single sample with an extrapolated weight of 38 MB landed in the second. The
number the conclusion rests on did not move at all: `tomcat-handler-*` read **1.44 MB/request**
in both. That is the useful positive control here, and the reason the thread table is quoted
alongside the stage table rather than instead of it.

### Did jvmlens answer it? Yes — but not on the scope you would reach for first

The same recording through the sister tool, `analyze <jfr> -a org.alexmond.kweblens`:

```
## Top allocation sites (application code, by est. bytes) [sampled]
- ResourceService.listRaw            — 1% (7.8 MB)  (:67 · byte[] 2.2 MB · int[] 1.1 MB)
- ObjectApiController.objects        — 1% (3.3 MB)  (:71 · byte[] 1.4 MB · char[] 1.4 MB)
- ListProjection.forList             — 0% (343.8 KB)
## Top allocated types (by est. bytes) [sampled]
- [B — 64% (359.4 MB)      - [C — 15% (83.7 MB)
```

It ranks the fetch above the serialise, which is the right direction and is the decision — but
both lines read **1%**, and 98% of the bytes sit in a `[B` row that is precisely the
undifferentiated bucket the class histogram already gave. The reason is structural: kweblens's
list cost is incurred **inside a client library**, on fabric8's vert.x event-loop threads, where
there is no application frame anywhere on the stack for an app-scoped attribution to anchor to.

Widening the scope to the libraries that actually spend the memory closes it completely, and
the ranking is the hand-rolled analysis above, in one command:

```
$ jvmlens analyze secrets-alloc.jfr -a org.alexmond.kweblens -a io.fabric8 \
                                    -a com.fasterxml.jackson -a io.vertx
## Top allocation sites (application code, by est. bytes) [sampled]
- com.fasterxml.jackson.core.util.TextBuffer.contentsAsString — 18% (102.9 MB)  (:492 · byte[] 102.8 MB)
- com.fasterxml.jackson.core.util.TextBuffer.carr             — 15% (83.5 MB)   (:1235 · char[] 83.5 MB)
- io.fabric8.kubernetes.client.http.BufferUtil.toArray        — 11% (63.0 MB)
- io.vertx.core.buffer.impl.BufferImpl.getBytes               — 10% (55.7 MB)
- io.vertx.core.impl.VertxImpl$InternalTimerHandler.handle    —  9% (53.1 MB)  ⚠ Long may be
      scalar-replaced (escape analysis) — confirm the actual win with -prof gc
```

Model-graph strings (`contentsAsString`), Jackson's decode scratch (`carr`), and the response
body held whole (`BufferUtil.toArray`, `BufferImpl.getBytes`) — against
`ObjectApiController.objects:71`, the `Serialization.asJson` line, at **0.6%** in the same run.
The tool also caught something the hand-rolled buckets got wrong: the 53 MB of boxed `Long` is
vert.x's timer handler, i.e. rig noise, and it is flagged as possibly scalar-replaced rather
than offered as a lever.

**The dogfood finding, since it is a result in its own right:** on an application whose cost is
inside a client library, `-a <your package>` alone reports ~1% for the two lines that matter and
leaves the rest in `[B`. The tool is right — those *are* the only app frames — but the default
gesture under-serves this shape of problem. Worth `-a`-ing the library you are calling through
whenever the profile's `[B` row dwarfs every attributed site. (`--source` was also pointed at
already-edited files here and echoed shifted lines; that is the operator's error, not the
tool's — echo sources at the revision the recording was taken from.)

## Does chunking the fetch actually bound the heap? (#293, 2026-08-07)

Yes — and the instrument built to answer it said the opposite, which is the more useful half of
this entry.

### The rig

8 000 generated Secrets in one namespace plus the cluster's own 150, on the live k3s cluster:
**8 150 objects, 54.89 MB stored** on the API server, **2.08 MB on the wire** after
`ListProjection` (#279) nulls the values. Each generated object is ~6.4 KB, at the live median
of 9.2 KB rather than at the long tail, so the rig under-states a real cluster if anything.

**Seed it with `kubectl create`, never `kubectl apply`.** The first attempt used `apply`, which
stores a full copy of every manifest in the `last-applied-configuration` annotation — an
annotation `ListProjection` does not strip, because on a real object it is small. Every rig
object therefore carried a duplicate of its own 6.2 KB payload and the response read **13.3 MB
instead of 502 KB**: a rig measuring its own seeding method, in the same family as the
739-byte simulator pod above.

### The measurement that pointed the wrong way

`heap-probe.sh`'s `transient` (peak − base) says chunking makes things **worse**:

| 2 150 Secrets | transient |
|---|---|
| chunking off (`chunk-size: 0`) | 67–69 MB |
| chunking on (500) | 80–96 MB |

That reading is real and it is not the answer. `peak − base` measures how much eden the request
dirtied before a lazy collector got round to it — allocation **churn**. Five bounded pages
allocate more total garbage than one whole-collection graph, while far less of it is live at any
instant. Taken alone this table is an argument for reverting the fix.

### The measurement that answers the question

The question an OOM-kill asks is not "how much did it allocate" but **"how small a heap can it
finish in"** — only genuinely-live bytes can push a squeezed heap over, so collector timing
drops out. Smallest `-Xmx` in which one full Secrets list completes, 8 150 objects:

| `-Xmx` | chunking off | chunking on (500) |
|---|---|---|
| 256m | ok 2.84 s | ok 2.60 s |
| 224m | **OOM** | ok 2.63 s |
| 208m | **OOM** | ok 2.85 s |
| 192m | **OOM** | ok 2.78 s |
| 176m | **OOM** | ok 3.29 s |

Unchunked needs **256m**; chunked completes at **176m**, which is the floor of the app itself —
below ~176m the process cannot finish booting Spring at all, list or no list. So chunking does
not merely reduce the list's heap cost, it removes the cluster's size from the floor: what
bounds the chunked path is kweblens's own footprint.

Wall-clock is unchanged (~2.6 s either way), so the bound costs nothing.

### Why this is the number that matters

`deploy/helm/kweblens/values.yaml` sets `limits.memory: 1Gi`, and the JVM's default max heap is
a quarter of the container limit — **~256 MB**. Unchunked, 8 150 Secrets sits exactly on that
line. This was never a slowdown; it was an OOM-kill with a cluster-sized trigger.

### Standing consequence

Two instruments, two questions, and they disagree by design:

- **`heap-probe.sh`** — how much a request allocates. Good for comparing kinds to each other.
  **Not** admissible for "does this bound the heap"; its header now says so.
- **the minimum-heap bisect** — whether the live set is bounded. This is the one that decides.

Neither can run on the simulator: its API server is in the same JVM, so its serialisation is
inside every reading.


2. ~~**Then bound the lists server-side**~~ — **superseded 2026-08-05, by measurement.** See
   "Is server-side paging still worth building?" above. The wire, the DOM and `/counts` are all
   comfortable to 3 000 objects/kind now, so paging buys none of what it was scoped to buy; the
   unbounded thing is **transient heap**, ~247 KB per Secret on the live path, which reaches the
   chart's 1 GiB container limit at roughly **2 000 Secrets**. The next move is to stop
   materialising the whole response (`Serialization.asJson` over the full list) rather than to
   stop fetching it — and before either, to measure how the spike splits between the model graph
   and the output `String`. Paging stays unbuilt, and the filter-correctness constraint below is
   part of why.
3. ~~**Either make the simulator's seeder bulk, or stop treating it as the scale rig**~~ —
   **done, partly.** The seeder now produces objects that resemble real ones (measured above),
   so it is a trustworthy rig for payload and rendering questions up to ~1 000 objects/kind and
   a usable one to 3 000. It was *not* made bulk: seeding is still one HTTP `POST` per object
   and is now slower per object, so the ceiling moved down, not up. **KWOK remains the answer
   for paging and for anything larger than ~3 000/kind**, and that is now written down here
   rather than implied.

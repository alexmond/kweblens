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

## Recommended change to the plan

1. **Virtualise the resource list.** Highest ratio of symptom removed to risk taken, needs no
   server change, and is validatable on this rig today.
2. **Then bound the lists server-side**, developed against KWOK or the live cluster from the
   start because the simulator cannot prove it works. Paging and filtering stay one piece of
   work, as the roadmap says: a substring filter over a truncated page reports "no matches"
   for an object that exists.
3. ~~**Either make the simulator's seeder bulk, or stop treating it as the scale rig**~~ —
   **done, partly.** The seeder now produces objects that resemble real ones (measured above),
   so it is a trustworthy rig for payload and rendering questions up to ~1 000 objects/kind and
   a usable one to 3 000. It was *not* made bulk: seeding is still one HTTP `POST` per object
   and is now slower per object, so the ceiling moved down, not up. **KWOK remains the answer
   for paging and for anything larger than ~3 000/kind**, and that is now written down here
   rather than implied.

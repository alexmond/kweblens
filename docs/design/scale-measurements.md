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

## Recommended change to the plan

1. **Virtualise the resource list.** Highest ratio of symptom removed to risk taken, needs no
   server change, and is validatable on this rig today.
2. **Then bound the lists server-side**, developed against KWOK or the live cluster from the
   start because the simulator cannot prove it works. Paging and filtering stay one piece of
   work, as the roadmap says: a substring filter over a truncated page reports "no matches"
   for an object that exists.
3. **Either make the simulator's seeder bulk, or stop treating it as the scale rig** and
   document KWOK as the answer. Right now it is quietly both the default and inadequate.

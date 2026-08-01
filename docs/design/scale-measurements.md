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

## Recommended change to the plan

1. **Virtualise the resource list.** Highest ratio of symptom removed to risk taken, needs no
   server change, and is validatable on this rig today.
2. **Then bound the lists server-side**, developed against KWOK or the live cluster from the
   start because the simulator cannot prove it works. Paging and filtering stay one piece of
   work, as the roadmap says: a substring filter over a truncated page reports "no matches"
   for an object that exists.
3. **Either make the simulator's seeder bulk, or stop treating it as the scale rig** and
   document KWOK as the answer. Right now it is quietly both the default and inadequate.

# Watch fan-out: accept N, and here is what N actually is

Date: 2026-08-03. Decides the open question in [`roadmap.md`](roadmap.md) §"Second: T2":
*share one watch per cluster+kind across subscribers, or accept N and document the ceiling.*

**Decision: accept N. Do not build a shared watch registry.** But the measurement changed
the question first, and that part is not optional — N was not what the roadmap thought it
was, and the difference was a leak rather than a fan-out.

Measured against the live cluster (`default`, k3s 1.35, 4 nodes, 1 561 objects across 118
kinds), not the simulator. See [`scale-measurements.md`](scale-measurements.md) for why that
distinction is load-bearing here.

## What the roadmap assumed

> watches are opened **per SSE connection**, so N browser tabs × N open list views = N
> apiserver watches with no sharing.

The first half is right and the second half over-counts in one direction and badly
under-counts in another.

**A tab does not hold several list watches.** `useResourceData.ts` opens exactly one
`EventSource` per `(cluster, kind, namespace)` and closes it in the watcher's `onCleanup`,
and the SPA shows one list at a time. Category overviews take the `isSynthetic` early
return and open nothing. So a tab's *demand* is one watch, not one per view it has ever
shown.

**But demand was never what was open.** That is the finding below.

## Measurement 1 — the ratio is exactly 1:1

Counted on the API server's own side, `apiserver_longrunning_requests{verb="WATCH",
resource="pods"}`, cross-checked against the JVM's TCP sockets to `:6443` on this box.

| concurrent SSE list clients | apiserver WATCH(pods) | delta over baseline | JVM sockets to :6443 |
|---:|---:|---:|---:|
| 0 (baseline: the cluster's own controllers) | 8 | — | 0 |
| 1 | 9 | 1 | 1 |
| 2 | 10 | 2 | 2 |
| 8 | 16 | 8 | 8 |

Real Chromium tabs, not synthetic clients, give the same answer: three tabs sitting on the
Pods list took `WATCH(pods)` from 8 to 11, and closing the browser returned it to 8.

So: **one subscriber, one apiserver watch, one connection. No sharing and no multiplexing.**

## Measurement 2 — the number that mattered was not the ratio

A departed subscriber's watch was not being released.

| what was watched | subscriber disconnected | watch still open after |
|---|---|---|
| `pods` (a quiet kind on this cluster) | yes | **5 min +** (test ended, still open) |
| `events` (a kind that ticks) | yes | ~35 s |

The mechanism is not subtle once seen. `SseEmitter` learns that its client is gone **only
from a failed write**; nothing polls the socket. The object-watch stream writes only when
the watched kind produces an event. On a quiet kind there is no write, so there is no
failure, so `onCompletion` never runs, so `watch.close()` never runs. `events` released in
35 s because that is how long it took for the next event to arrive and fail.

The consequence is the ceiling that actually bites. One operator, one tab, walking twenty
kinds in the nav and closing each list before opening the next:

```
after visiting 20 kinds : 22 open API-server watches   (live subscribers: 1)
+30s                    : 22
+90s                    : 18
+210s                   : 18
```

**The fan-out was one watch per list view *ever opened*, not per subscriber.** A single
operator browsing the nav for a minute out-consumed eight simultaneous tabs. Sharing a
watch per cluster+kind would not have fixed this: a shared watch is released by
decrementing a refcount on the same emitter completion that never fired.

## What was changed

`SseKeepAlive` (`web/api`) writes an SSE **comment** (`:keepalive`) to the object-watch
stream every 15 s. `EventSource` discards comment lines, so no client change. A write to a
departed subscriber fails, which completes the emitter, which runs the `onCompletion` hook
that closes the watch. The probe also cancels its own schedule on that first failure rather
than relying on the container to fire the callback.

Re-measured, same box, same cluster:

| | before | after |
|---|---:|---:|
| 3 subscribers disconnect, watches released after | > 300 s | ~21 s |
| walk 20 kinds in one tab, watches open at the end | 22 | 9 (transient) |
| …still open 30 s later | 22 | **0** |

15 s is chosen so the worst case is two periods — a closed tab holds a watch for at most
~30 s. The cost is a handful of bytes per stream per minute.

## The decision, and why sharing loses

**Accept N = the number of live list subscribers. Document the ceiling. Do not share.**

The reasoning, in the order it carries weight:

1. **N is small and it is now honest.** Per [ADR-001](adr-001-identity-model.md) kweblens
   serves a *single trusted operator*. One list view per tab, a handful of tabs: N is
   single digits. Ten tabs is ten watches against a control plane whose own controllers
   already hold eight on `pods` alone at idle. That is not a load worth engineering
   against.
2. **Sharing buys nothing at this N and costs real lifecycle risk.** A shared
   `(cluster, kind, namespace)` watch needs a refcount, a close policy for the last
   subscriber (immediately? after a grace period, so a page reload does not tear down and
   re-establish?), a re-establishment policy when the API server ends the watch, and a
   slow-subscriber policy — one tab that stops reading must not stall the others, so each
   subscriber needs its own bounded buffer and a drop-or-disconnect rule. Every one of
   those is a way to serve a *stale* list, which is worse than serving a duplicated one.
3. **It would have hidden the real bug.** A shared watch would have made the twenty-kind
   walk cost twenty watches instead of twenty-two, and the leak would still have been
   there, harder to see. The measurement is the argument: the win came from releasing
   watches, not from sharing them.
4. **The one case that would change this is not our case.** Sharing pays when many
   subscribers watch the *same* kind — a multi-user dashboard. ADR-001 says explicitly
   that multi-tenancy is not a goal. If that ever changes, this decision changes with it,
   and the note to re-read is this one.

## The ceiling, stated

- **One API-server watch (and one connection) per open resource-list view, per browser
  tab.** Not per kind visited, not per cluster: per list currently on screen.
- **A closed tab or a navigation releases its watch within ~30 s** (two keepalive periods).
- Nothing else in the product opens a resource watch: overviews, the drawer and the
  dashboards are plain reads. Pod logs open their own log streams (`LogApiController`,
  `MultiLogStream`), which are a different resource and are not counted here.
- The practical limit is the API server's own watch capacity and the JVM's connection
  pool, both of which are orders of magnitude above a single operator's tab count. There
  is no per-cluster cap in the code, and this decision does not add one — a cap that can
  only be hit by a usage pattern ADR-001 excludes would be a failure mode invented for a
  user who does not exist.

## Not covered, and worth knowing

- **The log streams have the same shape of exposure.** `LogApiController` and
  `MultiLogStream` are also `SseEmitter`s whose only writes are data-driven, so a quiet pod
  leaves its log watch open on a departed subscriber for the same reason. Not fixed here
  because it was not measured here; `SseKeepAlive` is reusable and this is the obvious next
  user of it.
- **Teardown timing was measured on this box against a three-replica control plane.** The
  socket counts (`ss` against the JVM) are unambiguous; the `apiserver_longrunning_requests`
  figures are scraped through a load balancer and their *tails* are noisier than their
  deltas. Every conclusion above rests on a delta or on the socket count.
- **The API server's own watch timeout was never reached** in any of these runs, so what
  fabric8 does when the server ends a watch — reconnect, or surface it — is unverified.
  It matters only for streams that live for tens of minutes.

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
  user of it. *(They did. See part 2 — and so did a fourth endpoint not listed here.)*
- **Teardown timing was measured on this box against a three-replica control plane.** The
  socket counts (`ss` against the JVM) are unambiguous; the `apiserver_longrunning_requests`
  figures are scraped through a load balancer and their *tails* are noisier than their
  deltas. Every conclusion above rests on a delta or on the socket count.
- **The API server's own watch timeout was never reached** in any of these runs, so what
  fabric8 does when the server ends a watch — reconnect, or surface it — is unverified.
  It matters only for streams that live for tens of minutes.

---

# Part 2 — the same question, asked of every stream

Date: 2026-08-04. Part 1 ended with a "not covered": the log streams *looked* like they had
the same exposure and were not measured. They did. So did a fourth SSE endpoint that the
note did not mention at all. This part is the audit that should have run then — **every
long-lived surface in the app, enumerated rather than assumed**, with what it holds, how it
learns the client is gone, and how long that took.

The headline: **three of the four SSE endpoints leaked and now do not. Nothing else does,
and that negative result is the more useful half** — exec over WebSocket and port-forward
were both checked and both need no heartbeat, for reasons that do not transfer from SSE.

## Method

Same box, same cluster (`default`, k3s 1.35), same instrument as part 1: connect N clients,
kill them, and count the JVM's ESTABLISHED sockets to the API server
(`ss -tn 'dst :6443' -p`, filtered to the app's pid). **Deltas over a baseline taken
immediately before, never absolute tails** — fabric8 retires idle pooled connections on its
own ~60 s timer, which moves the absolute number by ±2 with nothing connected at all.

Two fixtures in a `kweblens-audit` namespace, because the entire mechanism turns on whether
the stream has anything to write: a **`chatty`** pod (one line a second) and a **`quiet`**
pod (one line at startup, then nothing). The simulator was not used —
[`scale-measurements.md`](scale-measurements.md) records why its objects are not
representative, and a simulated pod does not log at all.

Thread counts (`jcmd Thread.print`, names `log-sse-*` / `multi-log`) corroborate but are
**not** the headline. An early run counted OkHttp's pool threads too and wandered by ±4 with
nothing connected — noise that would have been read as a result.

## Every long-lived surface

| Surface | Held per client | Learns the client is gone from | Release, quiet | Verdict |
|---|---|---|---|---|
| `GET …/resources/{id}/objects/watch` | 1 API-server WATCH | 15 s `SseKeepAlive` comment | **≤30 s** (held at t+15 s, clear at t+30 s) | fixed in #283, **still fixed** |
| `GET …/resources/{id}/watch` | 1 API-server WATCH | a watch event, and nothing else | **never** (+3 at t+300 s) | **leaked** → keepalive attached |
| `GET …/pods/{ns}/{pod}/log/stream` | 1 log follow + 1 reader thread | a log line, and nothing else | **never** (+3 at t+300 s) | **leaked** → keepalive attached |
| `GET …/logs/stream` (multi-log) | 1 log follow + 1 reader thread **per source**, + a dispatcher, + a 4 s refresh loop | a log line or a change in the source set | **never** (+3 at t+300 s) | **leaked** → keepalive attached |
| `ws://…/ws/exec` | 1 exec session to the API server | the WebSocket close frame, or the FIN | **~1.3 s** | fine, no change |
| `POST …/port-forwards` | 1 listener; an API-server connection only while carrying traffic | nothing — it is not client-scoped | n/a, by design | fine, no change |
| `GET /sse` (MCP) | an `McpServerSession` + an async request. **No cluster resource** | nothing was configured to notice | **never** (3 of 3 alive at t+240 s) | leaked memory → SDK keepalive turned on |
| `GET …/metrics/*` | nothing — a plain request/response | n/a | n/a | fine |

## The three that leaked, and how badly

The mechanism is part 1's, unchanged: an `SseEmitter` learns of a disconnect only from a
failed write, and all three wrote only when the cluster produced something.

The **chatty** control makes it unmistakable. The same endpoint, the same clients, the same
teardown — only the pod differs:

| endpoint | quiet pod | chatty pod |
|---|---|---|
| `log/stream` | +3 sockets still held at t+300 s | 0 by t+5 s |
| `logs/stream` | +3 sockets still held at t+300 s | 0 by t+5 s |

Nothing about the disconnect changed. The next log line did.

Two pieces of corroboration are worth keeping, because they are what make the size of it
obvious:

* **A five-second smoke test outlived the session.** A `timeout 5 curl` against
  `logs/stream`, run once at the start to check the endpoint answered, left three
  `multi-log` threads that `jcmd` still found alive **98 minutes later**, holding a log
  connection to a pod nobody was reading.
* **The server side had noticed nothing at all.** With nine abandoned SSE streams in the
  JVM, `ss` on the listen port showed **nine sockets in CLOSE-WAIT and, apart from LISTEN,
  nothing else**. The client had sent its FIN; the server had never called `close()`. That
  is the same leak stated from the other end of the wire.

**The multi-log stream is the expensive one**, and not only because it is per-source. Its
source set is re-resolved every four seconds so that pods created by a rollout join a stream
already in flight — which means a *departed* subscriber kept re-listing its workload against
the API server every four seconds for the life of the process. The others held a connection;
this one also generated load.

### The log streams had a second bug underneath the first, and it hid

Attaching the keepalive fixed both watch endpoints outright — `resources/{id}/watch` went
from "never" to clear at t+30 s on the first try. It did **nothing at all** for the log
streams: re-measured after the change, three departed subscribers still held three
connections and three parked reader threads at t+90 s.

The keepalive was working. The debug log showed it noticing within 15 s —
`SSE keepalive failed (ServletResponse failed to flushBuffer: Broken pipe); completing the
stream` — and `jcmd` showed the reader still parked in
`HttpClientReadableByteChannel.read` afterwards. So the disconnect was detected, the emitter
completed, `onCompletion` ran `watch.close()`, and **nothing happened**.

`LogWatch.close()` does not stop a log follow of the kind this project opens. fabric8's
`LogWatchCallback` has two paths: if you hand it an `OutputStream` it completes an internal
`asyncBody` future, and if you let it hand *you* an `InputStream` — `watchLog()`, which is
what `LogService.watch` uses because fabric8 rejects a piped stream of ours — it never
completes that future. `close()` is implemented as
`asyncBody.thenAccept(AsyncBody::cancel)`. On a future that is never completed, that
registers a callback that never runs. The method sets a flag and returns.

What actually tears the connection down is closing the **stream**: that signals the
channel's condition (waking the parked reader) and cancels the body.

This is the part worth remembering, because it is why the bug survived: **the chatty pod
released correctly the whole time**, before and after. Not through `close()` — through
`pump`'s `try-with-resources`, which closes the *reader* when a failed SSE write throws out
of the read loop. A test, a demo, or any pod that logs exercises only that path. The path
that `close()` was responsible for had, as far as the measurements can tell, never once
worked.

`LogService.release(LogWatch)` now closes the stream and then the watch, and both log
surfaces call it — from the completion hooks and from the reader's `finally`, because the
reader can exit by several routes and every one of them has to leave the follow closed.

## The three that did not leak

These are results, not omissions. A defensive heartbeat on any of them would be cargo.

**Exec over WebSocket does not need one, because a WebSocket is not an SSE stream.** It has
a close handshake, and Tomcat delivers `afterConnectionClosed` — which is where
`PodExecWebSocketHandler` closes the `ExecWatch`. Measured with a real headless Chromium
against the `quiet` pod: opening the terminal costs **exactly +1** socket to the API server,
and

* the tab is closed, so the browser sends a **close frame** → released after **~1.3 s**;
* the browser process is **SIGKILLed**, so no close frame is ever sent and only the kernel's
  FIN arrives → released after **~1.4 s**.

The rude case matters more than the polite one, and it is the same number. This is exactly
the asymmetry the SSE conclusion must not be carried across: SSE has no framing layer that
reports a close, so it needs a write to discover one; a WebSocket has one.

**A port-forward is not client-scoped at all.** It is started by a `POST`, held in a
server-side map, listed by id and stopped by another `POST` — a named resource that is
*meant* to outlive the tab that created it, exactly like `kubectl port-forward` in a
detached shell. Measured: it holds **no** API-server connection while idle (fabric8 dials on
demand — the socket count moves only while a request is in flight), it survives the `curl`
that created it, and `POST /{id}/stop` removes the listener and returns the socket count to
baseline. Everything is closed on shutdown by `PortForwardService.close()`. There is
deliberately no reaper: a forward that vanished because a browser tab closed would be a
worse product than one the operator has to stop.

**The metrics endpoints are plain reads.** `MetricApiController` is `@GetMapping` all the
way down and the UI polls. Nothing to leak.

## MCP's `/sse` — a leak, but a different one

The MCP transport is the same `SseEmitter` family, and an assistant that disconnects is
precisely the quiet-stream case. Three clients connected and were killed; all three
`McpServerSession` instances were **still in the heap 240 s later** (counted with a
forced-GC class histogram, so unreachable ones would already have gone), sockets parked in
CLOSE-WAIT. Nothing had been sent on the stream since the initial `event:endpoint`.

But it is a **different severity**, and the distinction is worth keeping: the session holds
no cluster-side resource. The cost is bounded process memory plus a servlet async context,
per MCP client connect — so it grows with how often an assistant reconnects, not with the
cluster.

It is also somebody else's stream. `SseKeepAlive` is for the endpoints we write; the MCP SDK
ships its own `KeepAliveScheduler`, off by default, so the fix is that knob rather than a
second heartbeat of ours: `spring.ai.mcp.server.keep-alive-interval: 30s`. Re-measured with
it on, same three clients:

| | before | after |
|---|---|---|
| sockets in CLOSE-WAIT after the clients die | 3, indefinitely | released between t+5 s and t+15 s |
| `McpServerSession` instances in the heap | 3 at t+240 s | **0** by t+120 s |

(The property is marked deprecated in Spring AI 2.0's metadata, along with the SSE transport
it belongs to — but it is the knob that exists for the transport kweblens actually serves,
and the deprecation names no replacement. If it disappears, this measurement is the reason
to look for its successor rather than to drop the setting.)

## What was changed

* `SseKeepAlive.attach(...)` on `LogApiController`, `MultiLogApiController` and
  `ResourceWatchApiController` — after the completion hooks, never before, because
  completing the emitter is what runs the hook that closes the watch.
* **`LogService.release(LogWatch)`** — closes the log *stream* and then the watch, because
  `LogWatch.close()` alone does not stop a `watchLog()` follow (see above). It lives in
  `kweblens-core` next to the call that opens the watch: the quirk belongs to the layer that
  knows fabric8, not to two controllers that would each have to remember it. Both log
  surfaces now call it from their completion hooks *and* from the reader's `finally`.
* **`SseEndpointKeepAliveTest`** — a structural guard, because this is an omission that
  passes every test and every demo. It scans the controllers for handlers returning an
  `SseEmitter` and requires the declaring class to reference `SseKeepAlive`, so a new
  streaming endpoint fails on the day it is written. It also asserts the scan still sees all
  four known controllers, so a scan that silently stops finding them fails rather than
  quietly shrinking.
* **`SseKeepAlive.completeQuietly`**, now used by every failed-send path. A bare
  `completeWithError` from a non-container thread races Tomcat's `AsyncListener.onError` and
  throws `IllegalStateException`; observed live, a disconnect from the chatty pod threw
  **out of** the log reader thread and printed an uncaught
  `Exception in thread "log-sse-…"`. The watch was released anyway — try-with-resources had
  already closed it — which is why a stack trace on the disconnect path went unnoticed.
* **`MultiLogStream.close()` is re-runnable.** It used to return early on a second call, so
  a source whose watch was still being opened when the client left registered itself *after*
  the sweep and was then never closed — the one watch nobody could cancel. `attach()` now
  rechecks after registering.
* `spring.ai.mcp.server.keep-alive-interval: 30s`.

## Where this leaves the ceiling

Part 1's ceiling now holds for the log streams too, with the same shape and the same ~30 s:

- **One API-server watch per open list view, and one log connection per followed source, per
  browser tab** — released within ~30 s of the tab going away.
- **One exec session per open terminal**, released within ~2 s.
- **Port-forwards are however many the operator started**, and stay until stopped or
  shutdown.

## What a keepalive still cannot see

`SseKeepAlive` detects a **closed** peer, not a **stalled** one. Its evidence is a failed
write, and a write only fails once the peer is gone. A client whose TCP connection stays open
but stops being read — a laptop that lost wifi without sending a FIN, or a proxy holding the
connection — absorbs a 20-byte comment every 15 s into its receive window for a very long
time before anything fails. Nothing in the app would notice until the API server or the OS
gave up first.

That case was **not measured** here and is not claimed to be handled. It needs a different
instrument (an idle timeout, or TCP keepalive on the server socket), and per
[ADR-001](adr-001-identity-model.md) it deserves about as much attention as one operator's
flaky wifi — which is why it is recorded rather than built.

The same stall has a second-order cost that is worth knowing about before it is diagnosed
from scratch. `SseKeepAlive`'s scheduler is **two threads for the whole process**, and its
probe writes through the emitter's write lock — which the multi-log dispatcher holds while
it flushes a batch. Sized when one endpoint used it, it now serves four. Two simultaneously
stuck subscribers would stall every other stream's probe, i.e. reopen this leak for
everyone else. That is a bound, not a bug, at one operator's tab count; it is written down
here so that a future report of "the keepalive stopped firing" starts in the right place.

Also unverified, carried forward from part 1: the API server's own watch timeout was never
reached in any of these runs, so what fabric8 does when the *server* ends a stream is still
unknown. It matters only for streams that live for tens of minutes.

# Multi-source logs vs `freelens-multi-pod-logs` / `stern`

Issue: GH#138. Date: 2026-07-30.

## What was actually tested, and what was not

Being precise about this, because the ticket asked for a hands-on comparison and only half
of one was possible.

| | how it was assessed |
|---|---|
| **kweblens** | **Hands-on**, against a live k3s cluster: a 2-replica `podinfo` Deployment with the stream open through three `kubectl rollout restart` cycles. |
| **`freelens-multi-pod-logs`** | **Desk check** of its README and source. Not run. |
| **`stern`** | **Desk check** of its documented behaviour. Not run — the binary is not installed here. |

Freelens itself *is* installed on this machine, but the plugin shells out to `stern`, which
is not, so the plugin could not have functioned without first installing a second tool. The
plugin is also **unmaintained** — its author cites frequent breaking changes in Freelens
updates — so a green or red result from it today would say more about Freelens's plugin API
than about the capability. The comparison below therefore treats **`stern`** as the real
reference implementation, which is fair: the plugin is a thin wrapper that opens a terminal
running it.

A true side-by-side is a `stern` install away if it is ever worth it. It was not worth
installing a tool on this machine to confirm behaviour its own documentation states plainly.

## Comparison

| | `stern` (and therefore the plugin) | kweblens |
|---|---|---|
| Kinds | Deployment, StatefulSet, ReplicaSet, DaemonSet | + Job, + a pod → all-containers entry point |
| Requires an external binary | **Yes** — `stern` on the host | No; streams through the Kubernetes API in-process |
| Works in a browser | No | Yes |
| Windows caveat | Colour needs virtual-terminal support enabled in PowerShell | None — it is a web page |
| New pods join mid-stream | **Yes** | **Yes**, as of this change (was the one real gap) |
| Ordering across sources | Whatever the pipe delivers | Timestamp-sorted within a window, contract stated to the client |
| Per-source identity | Colour | Colour **and** a text prefix, so colour is never the only cue |
| Show/hide a source | No | Click to toggle, double-click to isolate |
| Text filter | No | Yes, applied at render so it re-reveals lines already received |
| Pause / copy / follow-tail | No | Yes |
| Source cap | None | 24, and the truncation is **reported** rather than silently applied |
| `--previous` (crashloop) logs | No | Yes |
| Maintained | `stern` yes; the plugin **no** | — |

## The gap that was real, and how it was closed

`stern` picks up new pods as they appear. kweblens resolved its source set **once at
connect**, so during a rollout the new replicas never joined and the old ones just went
quiet — precisely when someone is watching.

Closed by **re-resolving the workload's pods every 4s** and attaching readers to whatever is
new, rather than by watching pods. Polling was chosen deliberately: a new pod is *not*
readable the moment it appears, so a watch would still need the retry loop that polling
already is, and would additionally own watch re-establishment. See `SourceTracker` for the
attach rules and `MultiLogStream` for the plumbing.

Two things fell out of building it that are worth recording:

- **fabric8 returns an API-server refusal as log content.** Asking for the log of a container
  that has not started answers HTTP 400 with a `Status` body, and both `getLog()` and
  `watchLog()` surface that JSON as the log itself without throwing. Before this was caught,
  every pod a rollout created contributed a fabricated line — a Kubernetes error object,
  attributed to the user's application. `LogRefusal` now recognises it on both paths. This
  bug was always present; the change made it happen on every rollout instead of rarely.
- **Concurrent `SseEmitter.send()` was already possible.** Per-source errors were sent from
  reader threads while the dispatcher sent lines, which the class's own documentation says is
  unsafe. Sends are now serialised.

## Capabilities the reference has that kweblens still lacks

None identified. The plugin's own README documents no filtering, search, timestamps, pause,
download, container selection, pod-count limit or rollout behaviour — the areas where
kweblens is ahead are ahead because `stern` is a terminal tool and this is not.

## Positioning note

Freelens users get this capability today only from an **abandoned third-party wrapper around
an external binary** that they must install separately, and which cannot work at all on a
machine without `stern`. That is a reasonable thing for kweblens to do properly in-product.

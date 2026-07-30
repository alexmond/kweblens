# ADR-001: Identity model — how kweblens acts on behalf of a user

- **Status: PROPOSED** — needs sign-off. This is an architecture decision with long-lived
  consequences, so it should not be treated as settled by the person who wrote it.
- Issue: GH#135. Constrains GH#141 (cluster config), GH#136 (detail endpoint), GH#140
  (file browser), GH#142 (agent tools), GH#144 (metrics).
- Date: 2026-07-30

## Correction up front: the premise in the issue was wrong

GH#135 (and the competitive review it came from) framed this as *"kweblens has a shared watch
cache, and a shared cache is inherently single-identity — the Octant problem"*.

**kweblens has no shared watch cache.** Verified:

- No informers anywhere — `grep -rniE "informer|SharedInformer"` over `kweblens-core` and
  `kweblens-web` returns nothing.
- `ResourceService.watchRaw()` opens a **fresh fabric8 `Watch` per call**, and
  `ObjectApiController.watch()` calls it once per SSE connection. So the topology today is
  already **one watch per open list view per browser tab**, not one shared cache serving
  everyone.

What is actually shared is **the credential**: `ClusterRegistry` owns one `KubernetesClient`
per cluster id, and every request rides it.

This matters because it **invalidates the main argument against per-user identity**. The issue
claimed option (c) "invalidates much of the shared-cache perf work". There is no shared-cache
perf work to invalidate — the rAF batching is client-side, and the watch-per-connection
topology would be unchanged. The real cost of per-user identity is therefore much lower than
the issue assumed, and the recommendation changes accordingly.

## Two more facts that shape the options

**fabric8 supports impersonation natively** **[verified]** — `Config.setImpersonateUsername`,
`setImpersonateGroups`, `setImpersonateExtras`, i.e. the standard Kubernetes
`Impersonate-User` / `Impersonate-Group` headers that back `kubectl --as`.

**`SelfSubjectAccessReview` is available** **[verified]** — present in
`kubernetes-model-admissionregistration-7.3.1`, reachable via `client.authorization()`.

So both candidate mechanisms are off-the-shelf. Neither needs new infrastructure.

## Decision

**Enforce authorization at the API server, using the calling user's identity. Do not
reimplement authorization inside kweblens.**

Concretely, in preference order:

1. **Token pass-through (preferred).** When the user authenticates with an OIDC token the
   cluster also trusts, build the per-request client with *that* token. RBAC is then exactly
   the user's own, kweblens needs no special privilege, and there is nothing for kweblens to
   get wrong. This is what the OpenShift Console does.
2. **Impersonation (fallback).** Where only a service account exists, keep the single client
   per cluster and set `Impersonate-User` / `Impersonate-Group` per request from the
   authenticated identity. The API server enforces; kweblens only asserts who is asking.
3. **`SelfSubjectAccessReview` for UI affordances ONLY** — greying out an action the user
   cannot perform, so the UI stops offering buttons that 403. **Not** the authorization
   mechanism.

### Why not option (b) from the issue (SSAR as the gate)

The issue recommended shared-watch-plus-SSAR-gating, copying Headlamp. Rejecting it as the
*enforcement* mechanism, for one reason that outweighs its convenience:

**It is authorization by reimplementation, and it fails open.** Under (b) the read still
executes with the service account's broad credential, and kweblens decides afterwards whether
the user should have seen the result. Every code path that forgets to ask, asks the wrong
question, or is added later by someone who does not know the rule, silently returns privileged
data. Under token pass-through or impersonation the API server refuses, so **a missed check
fails closed** — the request simply 403s.

That difference is worth more than the effort saved, especially with write actions, exec, a
proposed pod file browser (GH#140), and an agent tool surface (GH#142) all on the roadmap. Every
one of those multiplies the cost of a leak.

SSAR still earns its place — just as a UX hint, where being wrong is cosmetic.

### What this decision explicitly does NOT provide

- **It does not make kweblens multi-tenant on its own.** Per-user RBAC is necessary, not
  sufficient: the audit log, port-forward sessions, dock sessions and preference storage are
  all still global.
- **It does not remove the need for auth in front** during the transition. Until this lands,
  kweblens remains one shared admin — the diagnostics panel says so, and that honesty should
  stay until the code changes.
- **Impersonation is a privileged capability.** A service account with `impersonate` on
  users/groups can act as *anyone*, so a compromised kweblens is as bad as a compromised
  cluster-admin. This is not obviously worse than today (kweblens already holds a broad
  credential), but it must be a documented, deliberate grant — and token pass-through avoids it
  entirely, which is why it is preferred.

## Consequences

**Architectural**

- `ClusterRegistry` stops being "the client for cluster X" and becomes "how to *build* a client
  for cluster X as identity Y". The registry keeps owning connection config, TLS and proxy
  settings (see `docs/design/proxy-competitive.md`); the credential becomes per-request.
- Every service currently taking `clusterId` needs the calling identity threaded through. This
  is the bulk of the work and it is mechanical, but it touches every access-layer method.
- **The watch topology is unchanged** — still one watch per SSE connection, now opened with the
  user's credential instead of the shared one. No caching layer is lost, because none exists.
- Client construction becomes per-request, so it must be cheap. fabric8 clients are lightweight
  to derive but not free; a small per-identity cache with a TTL is likely, and must be keyed on
  the identity, never global.

**Operational**

- The RBAC kweblens itself needs *shrinks* under token pass-through (it stops needing broad
  read) and *changes shape* under impersonation (narrow, plus `impersonate`).
- Deployments must decide which mode they are in. That belongs in the diagnostics panel
  alongside the existing security row.

**Where it bites the roadmap**

- GH#136 (detail endpoint): its joins read more kinds than the object itself, so a relation may
  be unreadable for a given user. Report **"not permitted"**, never an empty list — an empty
  list reads as "there are none", which is a factual lie about the cluster.
- GH#140 (file browser): stays off-by-default until this lands. It reads mounted Secrets off
  disk, which under a shared credential means any kweblens user can read every Secret.
- GH#142 (agent tools): write-capable tools gate on this. An agent acting with a shared
  admin credential is the same leak with a friendlier interface.
- GH#144 (metrics): the apiserver **service-proxy** path inherently runs as kweblens's own
  credential, so metrics remain single-identity even after this decision. Either accept that
  and document it, or route metric queries through impersonation too.
- Audit entries should record the **end user**, which they currently cannot distinguish.

## Recommended sequencing

1. **Identity source first**: OIDC (token pass-through) with reverse-proxy header trust as an
   alternative — and state loudly that header trust is only safe when the proxy is *guaranteed*
   in front, since a spoofed header would otherwise be a complete auth bypass.
2. **Thread identity through the access layer** while still using the shared credential, so the
   plumbing lands separately from the behaviour change and is reviewable on its own.
3. **Switch the credential** to token pass-through / impersonation, one surface at a time,
   reads before writes.
4. **Add SSAR affordances** last — they are polish, and doing them first would create the
   illusion of enforcement.

## Alternatives considered

- **(a) Stay single-identity, document it.** Cheapest and honest, and it is what ships today. A
  legitimate answer for a single-operator homelab tool. Rejected as the *target* because the
  competitive review ranked identity the #1 disqualifier, and because features already on the
  roadmap (file browser, agent, exec) make a shared credential progressively more dangerous.
  Worth keeping as an explicitly supported mode for single-user deployments rather than
  pretending everyone needs OIDC.
- **(b) Shared credential + SSAR gating** — rejected above: fails open.
- **(c) Per-user clients without impersonation** (a client object per user) — unnecessary once
  impersonation exists; it multiplies connections for no additional correctness.

## Open questions for sign-off

1. Is multi-tenancy actually a goal, or is this about *not being embarrassing* in a shared
   homelab/team context? The answer changes how much of the "does not provide" list matters.
2. OIDC provider: is there one to integrate with today, or should header trust behind an
   existing auth proxy be the first (and possibly only) implementation?
3. Is granting `impersonate` acceptable in this cluster, or should token pass-through be the
   only supported mode?

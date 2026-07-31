# Metrics sources and their auth models

Issue: GH#144. Date: 2026-07-30. Everything below was measured against a live k3s cluster
running a VictoriaMetrics stack, not inferred from documentation.

## What exists today

1. **metrics-server** (`metrics.k8s.io`) — instantaneous pod/node CPU and memory.
2. **A Prometheus-compatible backend**, auto-discovered **by service name** and queried
   through the **kube-apiserver service proxy**
   (`/api/v1/namespaces/{ns}/services/{svc}:{port}/proxy/...`), so cluster RBAC is the only
   credential and no direct network path to the backend is needed.

The diagnostics panel reports both and names the discovered backend.

## Finding 1: name-based discovery fails *closed*, which changes how much it matters

The heuristic is a `KNOWN` list plus `contains("prometheus")`, minus an `EXCLUDE` denylist.
On the test cluster it matched exactly one service — the right one — and the denylist was
**load-bearing**: of the four services it excluded, one is a node-exporter whose name
contains "prometheus" and would otherwise have matched.

The important question is what happens when it guesses wrong. Measured, by querying two
mis-matching services through the proxy:

```
services/vmstack-prometheus-node-exporter:9100/proxy/api/v1/query   → 404 NotFound
services/…alertmanager…:9093/proxy/api/v1/query                     → 404 NotFound
```

Both 404, which `queryRange` already catches into `MetricSeries.unavailable()`. **So a
wrong guess produces silence, not wrong numbers.** That is the safe failure direction and
it downgrades this from a correctness risk to a usability one.

The case that *would* lie is two genuinely valid backends with different scopes — a global
Thanos querier alongside a local Prometheus. Both answer, `findFirst()` picks by API
ordering, and the charts would be correct-looking but scoped differently from what the user
expects. That is the case explicit configuration exists to solve.

## Finding 2: PVC capacity is available — and would be misleading if shipped naively

The Storage overview (#159) deliberately omitted capacity, saying it needs a metrics source.
That source **exists on this cluster**: `kubelet_volume_stats_capacity_bytes` and
`kubelet_volume_stats_available_bytes`, 24 series, labelled by `namespace` and
`persistentvolumeclaim`, reachable through the proxy path already in use. No new
infrastructure.

But the values do not mean what a "PVC 38% full" column would imply:

| storageClass | PVCs | requested | reported capacity |
|---|---|---|---|
| `nfs` | 10 | 1Gi – 30Gi | **3245.7 GB** for every one |
| `local-path` | 4 | 64Mi – 10Gi | **41.6 GB** for every one |

kubelet reports the **backing filesystem**, not the claim, for provisioners that do not
enforce a per-volume quota — which is both storage classes here. A capacity column built on
this would show every claim on a class with an identical size and an identical percentage,
and a 64Mi claim would be reported as "41.6 GB, 38% used". Confidently wrong is worse than
absent, which is why the overview says capacity is not checked rather than guessing.

**It is detectable, though.** When `capacity_bytes` is far larger than the PVC's
`spec.resources.requests.storage`, the number is the backing filesystem. So the check can be
built honestly:

- compare reported capacity against the requested size;
- show usage only when they are in the same ballpark (a real per-volume quota);
- otherwise report it as the *node/share* filesystem it actually is, or say the provisioner
  does not expose per-volume usage.

That is a real feature and worth its own ticket; it is not a blocker for anything.

## Finding 3: the RBAC needed is `get`, not `create`

The API server maps HTTP method to verb on the `services/proxy` subresource: a GET request
requires `get`. kweblens queries with fabric8's `raw(url)`, which is a GET, so a
least-privilege role needs:

```yaml
- apiGroups: [""]
  resources: ["services/proxy"]
  verbs: ["get"]
```

`kubectl auth can-i` cannot confirm this from a cluster-admin context — it answers "yes" to
`get`, `create` and `list` alike, which is a statement about the asker, not the requirement.

**Caveat worth writing down:** Prometheus and compatible backends accept **POST** on
`/api/v1/query` for queries too long for a URL. If kweblens ever switches to POST for long
PromQL, this role silently stops working and would need `create`. Granting both today is
defensible for that reason; granting only `get` is the tighter choice as long as the client
stays on GET.

## Recommendation

**1. Explicit configuration, overriding discovery.** `kweblens.metrics.prometheus-url`, per
cluster. This is table stakes for any non-standard install and is the only fix for the
two-valid-backends case. Discovery stays as the zero-config default.

**2. Report ambiguity, not just the choice.** The panel already names the backend it picked.
It should also say when **several** candidates matched, because that is exactly when the
pick is a guess. Silence-on-wrong-guess (Finding 1) means the user's symptom is "no charts",
and the panel is where they will look.

**3. Direct (non-proxy) access — build it only when there is a backend that needs it.** The
proxy path covers every in-cluster backend and needs no credentials at all. Direct HTTP is
required only for a backend **outside** the cluster (Grafana Cloud, a central Thanos, Mimir),
and that is where the auth models live:

| model | when | where the secret lives |
|---|---|---|
| Bearer token | most hosted backends | a Secret, referenced by name |
| Basic auth | self-hosted behind a proxy | a Secret |
| mTLS | stricter internal setups | a Secret with cert + key |
| Tenant header (`X-Scope-OrgID`) | Mimir / multi-tenant Thanos | plain config — it is not a credential |

**Credentials go in a Secret, never in configuration**, and the diagnostics panel reports
*whether* one is set — never its value, consistent with the reference-not-value rule the
Environment section and the agent tool surface both follow.

**4. The identity question is settled and no longer a design constraint.** ADR-001 accepted
a single trusted operator, so the apiserver-proxy path running as kweblens's own credential
**matches** the accepted model rather than being a caveat to work around. If per-user
identity is ever revisited, metrics inherit the decision made there; nothing here needs to
anticipate it.

## Suggested order

1. Explicit `prometheus-url` config + "several candidates matched" in diagnostics — small,
   and removes the only case where discovery can mislead.
2. PVC capacity with the requested-size sanity check — a real feature, unblocked, honest.
3. Direct access with auth — defer until a deployment actually has an external backend.
   Building four auth models against no user is speculative.

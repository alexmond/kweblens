# Proxy support: competitive survey + recommendation (#22)

Blocks workstream 3 of #21. Answers "what must kweblens support to reach clusters through a
proxy, and where can it exceed the field?"

**Headline: most of what this ticket asked for already works, and the reason is that proxying
is a property of the Kubernetes client library, not a product feature.** The verified baseline
is below; the recommendation is a small amount of surfacing plus one real gap.

## How to read the confidence levels

Claims here are marked:

- **[verified]** — tested locally against fabric8 7.3.1, or read from the product's own
  source/chart. Reproduction commands are given.
- **[quoted]** — quoted from upstream documentation.
- **[inferred]** — follows from a verified fact (e.g. "uses client-go, therefore inherits X").
- **[unverified]** — could not be established in this pass. Listed explicitly at the end
  rather than guessed at.

## 1. The baseline is the client library, not the product

Every tool in the survey delegates proxying to its Kubernetes client, so the honest matrix is
mostly a matrix of **client libraries**:

| Product | Client | Consequence |
|---|---|---|
| kubectl | client-go | reference semantics |
| **k9s** | `k8s.io/client-go v0.35.3` **[verified]** | inherits kubeconfig `proxy-url` + `HTTP(S)_PROXY` **[inferred]** |
| **Headlamp** | `k8s.io/client-go v0.36.1` **[verified]** | same **[inferred]** |
| **Rancher** | client-go | same, plus a global server proxy (below) |
| Octant (archived) | client-go | same |
| Lens / Freelens | Node/Electron | inherits Node/Chromium proxy handling **[unverified]** |
| **kweblens** | fabric8 7.3.1 **[verified]** | see §2 — broadly equivalent, with differences |

This is why the survey found so little product documentation: proxying is not a feature these
tools ship, it is something they inherit. Notably, **neither Lens's FAQ nor Headlamp's
installation docs document cluster-connection proxying at all** **[verified: fetched, absent]**.
Headlamp's Helm chart mentions `HTTPS_PROXY` only as an example env var for the *plugin
manager* container (fetching plugins from the internet), not for reaching clusters
**[verified: chart values.yaml]**.

### The upstream semantics that define "table stakes"

kubeconfig's per-cluster `proxy-url` field is the de-facto standard **[quoted]**:

> "the URL to the proxy to be used for all requests made by this client… If this configuration
> is not provided or the empty string, the client attempts to construct a proxy configuration
> from http_proxy and https_proxy environment variables. If these environment variables are not
> set, the client does not attempt to proxy requests."

Accepted schemes: `http`, `https`, `socks5` **[quoted]**.

And the single most important caveat in this entire document **[quoted]**:

> "socks5 proxying does not currently support spdy streaming endpoints (exec, attach, port
> forward)."

So in the client-go world, **a SOCKS5 proxy breaks exec, attach and port-forward** while
leaving normal API calls working. Any tool built on client-go inherits that, and it explains
why "proxy works but exec doesn't" is a recurring complaint rather than a per-product bug.

## 2. What kweblens can do today — verified, not assumed

fabric8's `Config` exposes proxying per **Config object**, and `ClusterRegistry` builds one
Config per cluster, so kweblens's proxying is **inherently per-cluster**.

Verified by compiling small probes against the project's own dependency set:

| Capability | Result |
|---|---|
| `httpProxy` / `httpsProxy` per Config | ✅ present **[verified]** |
| `noProxy` (exclusion list, `String[]`) | ✅ present **[verified]** |
| `proxyUsername` / `proxyPassword` (basic proxy auth) | ✅ present **[verified]** |
| `socks5://` scheme | ✅ `Config.SOCKS5_PROTOCOL_PREFIX` **[verified]** |
| kubeconfig `proxy-url` honoured by `Config.fromKubeconfig` | ✅ **[verified]** — but routed by the PROXY's scheme, see the warning below |
| kubeconfig `proxy-url` **overrides** an ambient `HTTPS_PROXY` | ✅ **[verified]** — per-cluster wins |
| Standard `HTTPS_PROXY` / `NO_PROXY` env vars | ✅ honoured by `Config.autoConfigure()` **[verified]** |
| System properties `https.proxy`, `no.proxy`, `proxy.username`, `proxy.password` | ✅ **[verified]** |
| PAC files | ❌ absent **[verified: no API]** |
| NTLM / Kerberos-SPNEGO proxy auth | ❌ absent **[verified: only username/password]** |
| SSH / bastion tunnelling | ❌ not a client concern — see §4 |

Reproduce: build a test classpath with
`./mvnw -o -pl kweblens-core dependency:build-classpath -Dmdep.outputFile=cp.txt`, then compile
a probe that calls `Config.fromKubeconfig(null, yaml, path)` with a `proxy-url:
socks5://…` cluster and print `getHttpsProxy()`.

### ⚠ The most important finding: `proxy-url` is routed by the PROXY's scheme, not the server's

**[verified]** fabric8 maps `proxy-url` onto one of two fields depending on the **proxy URL's own
scheme**, regardless of the apiserver's scheme:

| `proxy-url` | lands in | apiserver |
|---|---|---|
| `socks5://198.51.100.99:1080` | `httpsProxy` | `https://…` |
| `https://198.51.100.7:3129` | `httpsProxy` | `https://…` |
| **`http://198.51.100.7:3128`** | **`httpProxy`** | `https://…` |

That third row is the trap, and it is the **most common real-world configuration in existence**:
an apiserver is effectively always `https://`, while a corporate forward proxy is almost always
written `http://proxy:3128` — the proxy is reached in cleartext and tunnels TLS via `CONNECT`.
fabric8 files that under `httpProxy`, i.e. the proxy used for **http** targets.

#### Now **[verified against a real proxy]**, and the answer is worse than predicted

This was left open above as "likely to present as an unexplained connection timeout". It does
not. Tested end to end against a live cluster with a local CONNECT proxy that logs every
connection:

| `proxy-url` | apiserver | result | proxy log |
|---|---|---|---|
| `http://127.0.0.1:3128` | `https://…` | **29 namespaces listed — succeeded** | **0 connections** |
| `socks5://127.0.0.1:1080` | `https://…` | 29 namespaces listed | 1 connection |

The proxy was not at fault: `curl -x http://127.0.0.1:3128` against the same apiserver logged a
`CONNECT` and got its expected `401`.

So an `http://` proxy-url against an https apiserver does not fail — it is **silently ignored**
and the traffic goes **direct**. That is the dangerous version of this bug rather than the
merely annoying one: in an environment where egress is supposed to be audited or restricted to
a proxy, everything appears configured and working while the requirement is not being met. A
timeout would at least have prompted someone to look.

**This drives the recommendation**: do not document `http://` proxy-url as supported, and detect
the combination at runtime rather than relying on anyone reading this page. `ProxyStatus` now
reports it in the diagnostics panel as a capability that is NOT available, with the reason and
the fix (use `socks5://` or `https://`).
It is pinned by `KubeconfigProxyTest.routesTheProxyUrlByTheProxysOwnSchemeNotTheServers` so a
fabric8 upgrade that changes the mapping is caught rather than discovered in production.

**A naming trap worth recording.** fabric8's constants are named `KUBERNETES_HTTPS_PROXY`,
`KUBERNETES_NO_PROXY`, `KUBERNETES_PROXY_USERNAME` — but their **values** are `https.proxy`,
`no.proxy`, `proxy.username` (no `kubernetes.` prefix). Setting `KUBERNETES_HTTPS_PROXY` as an
environment variable does **nothing** **[verified: probe showed null]**; the working forms are
the standard `HTTPS_PROXY` env var or the `-Dhttps.proxy=` system property. Anyone reading the
Java constant names and configuring a Deployment from them will produce a silently
non-proxying instance.

## 3. Where kweblens's server shape changes the target

The ticket's framing is correct and it matters: the desktop tools proxy from the user's own
machine, so they can lean on the OS, the user's env, and an ssh tunnel the user already
started. kweblens is a shared server, so:

- **Per-cluster proxy is more important than global.** A server holding ten clusters may reach
  some directly and others through different proxies. **The closest analogue in the field does
  not do this:** Rancher's proxy is a single global `proxy` value plus `noProxy` in its Helm
  chart **[verified: rancher/rancher chart/values.yaml]**, whose `noProxy` default covers
  loopback, all three RFC1918 private ranges, and the in-cluster DNS suffixes `.svc` and
  `.cluster.local` (see the chart for the literal list — it is not reproduced here so this
  repository stays free of address literals). kweblens already
  has per-cluster proxying via `proxy-url`, for free.
- **"Use the ambient environment" is not a complete answer**, because there is no user
  environment — only the pod's. A global `HTTPS_PROXY` in the Deployment is the floor, not the
  ceiling.
- **A NO_PROXY default matters more.** In-cluster, kweblens must reach the apiserver, in-cluster
  services (the metrics service proxy!) and possibly a config server. Rancher's default list is
  a good model to copy, and its inclusion of `.svc`/`.cluster.local` is exactly what stops a
  proxy from swallowing in-cluster traffic. **Getting this wrong would break the metrics charts
  and Helm repo access, not just cluster connectivity.**
- **Credential handling is a server problem.** A proxy password in a desktop app is the user's
  own; on a shared server it is a stored secret. It must not land in a UI-readable field or in
  the diagnostics panel (which already reports config but never secret values — see #27).

## 4. Classification

**MUST-HAVE (table stakes)**
1. Per-cluster `proxy-url` from kubeconfig — **already works and is now pinned by tests**;
   needs end-to-end verification against a real proxy and documenting, not building.
2. Global `HTTPS_PROXY` / `NO_PROXY` honoured by the server — **already works** via
   `autoConfigure()`; needs a documented, sensible `NO_PROXY` default for in-cluster
   deployments.
3. Custom CA bundle / `insecure-skip-tls-verify` for TLS-intercepting proxies — kubeconfig
   already carries both fields; verify they survive our load path.
4. **Diagnostics must show the effective proxy per cluster.** A misconfigured proxy currently
   presents as a slow, mysterious failure. The #27 panel is the natural home and this is cheap.

**NICE-TO-HAVE**
5. UI-editable per-cluster proxy, stored in the persisted cluster record (#21 workstream 2).
   Only worth it once cluster records are editable at all.
6. Basic proxy auth from a Secret rather than the kubeconfig.
7. SOCKS5 — supported by the library, but see the streaming caveat before promising it.

**DIFFERENTIATOR (potential)**
8. **Per-cluster proxy in a server-side multi-cluster tool.** Rancher does not do it; kweblens
   effectively already does. Worth stating explicitly in positioning.
9. **Exec/port-forward through a SOCKS5 proxy.** client-go cannot do this because its streaming
   endpoints use SPDY. fabric8 7.x negotiates exec over **WebSocket**, not SPDY, so kweblens may
   not share the limitation. **This is the highest-value thing to test in this whole document**
   — if it works, kweblens does something the entire client-go field documents as unsupported.
   Currently **[unverified]**.

**EXPLICIT NON-GOAL**
10. SSH/bastion tunnelling built into kweblens. A jump host is infrastructure, not application
    config: run the tunnel next to the pod (sidecar) or terminate it at a SOCKS5 proxy and point
    `proxy-url` at that. Building ssh key management into kweblens would mean storing user SSH
    keys server-side — a much larger security surface than the feature is worth. Document the
    sidecar pattern instead.

## 5. Recommended v1 scope

Small, because the library already does the work:

1. ~~**Test what we claim.**~~ **DONE** — `KubeconfigProxyTest` (kweblens-core) now pins that
   `proxy-url` reaches the built client, that no proxy is invented when the kubeconfig asks for
   none, that TLS settings survive alongside it, and the scheme-routing behaviour above.
2. ~~**Test the http-proxy-to-https-apiserver case against a REAL proxy**~~ **DONE** — and it
   does not time out, it **silently bypasses the proxy** (see the verified table above). The
   conclusion is stronger than "test before advertising": `http://` proxy-url must be documented
   as **not supported for an https apiserver**, and detected at runtime, because the symptom of
   getting it wrong is that everything works.
3. ~~**Document it**~~ **DONE** — `docs/proxy.md` covers per-cluster `proxy-url`,
   `HTTPS_PROXY`/`NO_PROXY` for the whole server, the `KUBERNETES_*`-constant naming trap, the
   in-cluster `NO_PROXY` default including `.svc`/`.cluster.local` and the pod/service CIDRs,
   and leads with the silent-bypass rule.
4. ~~**Surface the effective proxy per cluster in the #27 diagnostics panel**~~ **DONE** —
   `ProxyStatus` reports the proxy actually in force, and reports the silent-bypass combination
   as a capability that is NOT available, with the reason and the fix. One capability row
   ("Egress proxy: none / socks5://… from kubeconfig"), so a proxy problem is visible instead of
   presenting as an unexplained timeout.
5. **Verify streaming through a proxy** (classification item 9): stand up a SOCKS5 proxy, point `proxy-url` at
   it, and try list, watch/SSE, exec and port-forward. Record the result — it decides whether
   SOCKS5 is a supported configuration or a documented trap.

**Defer**: UI-editable proxy (until #21 makes cluster records editable), PAC, NTLM/Kerberos
(no library support — would need a custom `HttpClient` and is a large piece of work; revisit
only if a real corporate deployment asks), and anything SSH.

## 6. Not verified in this pass — do not treat as known

- Whether fabric8's **WebSocket** exec and port-forward actually traverse an HTTP CONNECT or
  SOCKS5 proxy (item 9). The single most valuable open question here.
- Whether `proxyUsername`/`proxyPassword` apply to SOCKS5 auth or only to HTTP proxy auth.
- Lens/Freelens's actual behaviour: their docs do not cover it, and Electron/Node proxy handling
  was not tested. Lens's "Policy-Controlled Proxy" (a Lens Agents feature) appeared in its docs
  navigation but was not read and is **not** the same thing as a forward proxy.
- Portainer's Edge agent, which solves reachability by outbound-connecting *from* the cluster
  rather than proxying *to* it — architecturally interesting for clusters behind NAT and worth a
  look if that use case comes up.
- k9s and Headlamp were confirmed to use client-go, so their proxy behaviour is *inferred* from
  client-go rather than observed.

The web-search budget for the session was exhausted partway through, which is why several
product-level rows are inferred from source and charts rather than from documentation. The
library-level findings — the ones the recommendation actually rests on — are all locally
verified.

## Sources

- [kubeconfig v1 reference (`proxy-url`, schemes, socks5/SPDY caveat)](https://kubernetes.io/docs/reference/config-api/kubeconfig.v1/)
- [rancher/rancher Helm chart values (global `proxy` + `noProxy` defaults)](https://github.com/rancher/rancher/blob/main/chart/values.yaml)
- [kubernetes-sigs/headlamp `backend/go.mod` (client-go version)](https://github.com/kubernetes-sigs/headlamp/blob/main/backend/go.mod)
- [kubernetes-sigs/headlamp Helm chart values (`HTTPS_PROXY` for the plugin manager only)](https://github.com/kubernetes-sigs/headlamp/blob/main/charts/headlamp/values.yaml)
- [derailed/k9s `go.mod` (client-go version)](https://github.com/derailed/k9s/blob/master/go.mod)
- [Lens documentation FAQ (searched; cluster proxying not documented)](https://docs.k8slens.dev/faq/)
- [Headlamp desktop installation docs (searched; cluster proxying not documented)](https://headlamp.dev/docs/latest/installation/desktop/)
- fabric8 `kubernetes-client-api` 7.3.1 — `io.fabric8.kubernetes.client.Config` and
  `io.fabric8.kubernetes.api.model.Cluster`, inspected and probed locally.

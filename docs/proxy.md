# Running kweblens behind an egress proxy

kweblens reaches each cluster's apiserver through whatever proxy that cluster's kubeconfig
names, so proxying is **per cluster** rather than per process. Analysis and competitor
comparison: `docs/design/proxy-competitive.md`.

## The one thing to get right

**Use `socks5://` or `https://` in `proxy-url`. A plain `http://` proxy is silently
ignored for an `https` apiserver.**

fabric8 routes `proxy-url` by the **proxy's own scheme**, not the target's. An apiserver is
effectively always `https`, so:

| `proxy-url` | used for an https apiserver? |
|---|---|
| `socks5://proxy:1080` | yes |
| `https://proxy:3129` | yes |
| `http://proxy:3128` | **no — traffic goes direct** |

That last row is the most common corporate configuration there is, and it does **not**
fail. Verified against a real proxy: kweblens listed the cluster's namespaces normally
while the proxy logged zero connections. Nothing breaks, so nothing prompts anyone to
check — which matters if egress is supposed to be audited or restricted.

**kweblens detects this.** The diagnostics panel's *Egress proxy* row reports the proxy
actually in force, and reports this combination as unavailable with the reason. Check it
after configuring a proxy rather than assuming.

## Per-cluster: kubeconfig `proxy-url`

```yaml
clusters:
  - name: behind-proxy
    cluster:
      server: https://apiserver.example:6443
      proxy-url: socks5://proxy.example:1080
```

This wins over any ambient `HTTPS_PROXY` for that cluster. Basic auth in the URL
(`socks5://user:pass@host:1080`) is honoured.

## Whole-server: environment variables

`HTTPS_PROXY`, `HTTP_PROXY` and `NO_PROXY` apply to every cluster that does not name its
own `proxy-url`.

### The `KUBERNETES_*` naming trap

fabric8 also reads `KUBERNETES_ALL_PROXY`, `KUBERNETES_HTTPS_PROXY`, `KUBERNETES_NO_PROXY`.
Those names look like they come from Kubernetes and are read by kweblens's client rather
than by the platform, so setting them affects **only** kweblens. Prefer the standard names
unless you specifically want to proxy kweblens and nothing else in the pod.

### `NO_PROXY` in-cluster

Running kweblens **in** the cluster with a proxy set and no exclusions sends in-cluster
traffic out through the proxy, where it fails or hangs. That includes the apiserver itself,
the metrics backend queried through the apiserver's service proxy, and every
`*.svc` address.

A sane starting point:

```
NO_PROXY=localhost,127.0.0.1,.svc,.cluster.local,kubernetes.default,<pod CIDR>,<service CIDR>
```

Add the pod and service CIDRs for the cluster — they are not implied by the DNS suffixes.

## Exec, logs and port-forward

These are streaming endpoints. fabric8 7.x negotiates exec over **WebSocket** rather than
SPDY, so it is not subject to the SOCKS5-with-SPDY limitation client-go tools document —
but this is **not yet verified end to end** through a proxy, so do not rely on it without
testing your own path.

## Not supported, deliberately: SSH and bastion hosts

A jump host is infrastructure, not application configuration, and building it in would mean
storing users' SSH keys server-side. Terminate the tunnel outside kweblens — a sidecar or a
local SOCKS5 endpoint — and point `proxy-url` at that.

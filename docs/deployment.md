# Deploying kweblens

kweblens runs the same fat jar / image three ways, and can monitor **only the local
(hosting) cluster**, **only external clusters**, or **both** — all through configuration.
No code changes are needed to switch; you pick a run mode and a cluster scope.

## Cluster-scope model

kweblens discovers clusters two ways, and they combine:

| Source | Config | Effect |
|---|---|---|
| **Ambient kubeconfig / in-cluster** | `kweblens.load-kubeconfig=true` (`KWEBLENS_LOAD_KUBECONFIG`) | With a kubeconfig present, each context becomes a cluster. In a pod with a ServiceAccount and no kubeconfig, fabric8 auto-detects **in-cluster config** → the hosting cluster registers as `default`. |
| **Explicit clusters** | `kweblens.clusters[*]` (`KWEBLENS_CLUSTERS_<i>_ID`, `_NAME`, `_CONTEXT`, `_KUBECONFIG`) | Each entry is an additional cluster loaded from a kubeconfig file + context. |

So the three scopes are just combinations:

| Scope | Settings |
|---|---|
| **Local only** | `KWEBLENS_LOAD_KUBECONFIG=true`, no kubeconfig mounted (in a pod → in-cluster SA) |
| **External only** | `KWEBLENS_LOAD_KUBECONFIG=false` + one or more `kweblens.clusters[*]` pointing at a mounted kubeconfig |
| **Both** | in-cluster SA (local) **+** `kweblens.clusters[*]` (external) |

## Run mode 1 — standalone jar

```bash
./mvnw -Pdocker -pl kweblens-web -am package -DskipTests   # or a plain package for the jar
KUBECONFIG=~/.kube/config \
KWEBLENS_SECURITY_ADMIN_USERNAME=admin KWEBLENS_SECURITY_ADMIN_PASSWORD=change-me \
java -jar kweblens-web/target/kweblens.jar
# → http://localhost:8080/ui  (every kubeconfig context becomes a cluster)
```

## Run mode 2 — container (Docker / docker-test)

The image is built by **Spring Boot's buildpacks** (`build-image`), not a Dockerfile:

```bash
./mvnw -Pdocker -pl kweblens-web -am package -Ddocker.image.name=kweblens:local
```

Run it with a mounted kubeconfig (external-cluster monitoring) — see
[`deploy/docker/compose.yaml`](../deploy/docker/compose.yaml):

```bash
KWEBLENS_KUBECONFIG=/path/to/kubeconfig \
KWEBLENS_ADMIN_PASSWORD=change-me \
docker compose -f deploy/docker/compose.yaml up
```

## Run mode 3 — in-cluster pod (Helm)

Chart: [`deploy/helm/kweblens`](../deploy/helm/kweblens). It ships a Deployment, Service,
optional Traefik-style Ingress, and — for local-cluster monitoring — a **ServiceAccount +
ClusterRole/Binding**. Defaults are neutral placeholders; a private deploy overlay supplies
the real registry, ingress host, pull secret, and RBAC choice.

```bash
# arg-driven; a deploy overlay wraps this with the lab's registry + values file
scripts/deploy-k8s.sh \
  --registry registry.example.com \
  --namespace kweblens \
  --pull-secret regcred \
  --values ../kweblens-deploy/values-homelab.yaml
```

### RBAC (local scope)

`rbac.role` selects the ClusterRole granted to the pod's ServiceAccount:

- **`viewer`** — `get/list/watch` on everything (+ `services/proxy`, `nodes/proxy`,
  `pods/log`). Pure monitoring; the mutating drawer actions return 403.
- **`editor`** — adds `create/update/patch/delete`, enabling scale / restart / rollback /
  delete / drain / exec / port-forward / Helm against the hosting cluster. It deliberately
  omits `escalate`/`bind`/`impersonate`, so it can't grant itself more.

RBAC objects are only rendered when `clusterScope.local=true`.

### Admin login

kweblens' `SecurityConfig` is **open by default** — every real deployment MUST set an
admin login and stay behind a gate. Create the auth Secret out of band and reference it:

```bash
kubectl -n kweblens create secret generic kweblens-auth \
  --from-literal=KWEBLENS_SECURITY_ADMIN_USERNAME=admin \
  --from-literal=KWEBLENS_SECURITY_ADMIN_PASSWORD='<strong-password>'
# then: --set auth.existingSecret=kweblens-auth  (and auth.openMode=false)
```

### Key chart values

| Value | Purpose |
|---|---|
| `image.repository` / `image.tag` | Image (tag defaults to chart appVersion) |
| `imagePullSecrets` | Registry pull secret(s) |
| `clusterScope.local` | Monitor the hosting cluster via the SA |
| `clusterScope.external[]` | External clusters (`id`, `name`, `context`) |
| `clusterScope.kubeconfigSecret` | Secret (key `kubeconfig`) mounted at `/kubeconfigs` |
| `rbac.role` | `viewer` or `editor` |
| `auth.existingSecret` / `auth.openMode` | Admin creds Secret / open-mode toggle |
| `ingress.enabled` / `ingress.host` | Traefik ingress (host required when enabled) |

## Lab / private overlay

Anything environment-specific (real registry host, ingress domain, pull-secret name,
namespace) lives in a **private `kweblens-deploy` overlay**, never in this public repo —
`lab-leak-guard` enforces that. The public chart + `deploy-k8s.sh` stay arg-driven with
placeholder defaults.

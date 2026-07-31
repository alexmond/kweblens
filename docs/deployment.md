# Deploying kweblens

kweblens runs the same fat jar / image three ways, and can monitor **only the local
(hosting) cluster**, **only external clusters**, or **both** — all through configuration.
No code changes are needed to switch; you pick a run mode and a cluster scope.

## Cluster-scope model

kweblens discovers clusters three ways, and they combine:

| Source | Config | Effect |
|---|---|---|
| **Ambient kubeconfig / in-cluster** | `kweblens.load-kubeconfig=true` (`KWEBLENS_LOAD_KUBECONFIG`) | With a kubeconfig present, each context becomes a cluster. In a pod with a ServiceAccount and no kubeconfig, fabric8 auto-detects **in-cluster config** → the hosting cluster registers as `default`. |
| **Explicit clusters** | `kweblens.clusters[*]` (`KWEBLENS_CLUSTERS_<i>_ID`, `_NAME`, `_CONTEXT`, `_KUBECONFIG`) | Each entry is an additional cluster loaded from a kubeconfig file + context. |
| **Added at runtime** | `POST /api/v1/clusters` (see [below](#adding-clusters-at-runtime)) | Persisted by kweblens and restored on the next boot. |

Startup order is configured → ambient → persisted, and a persisted cluster **never
overwrites** an id the first two declared: the deployment manifest is the authority on
the clusters it names.

So the three scopes are just combinations:

| Scope | Settings |
|---|---|
| **Local only** | `KWEBLENS_LOAD_KUBECONFIG=true`, no kubeconfig mounted (in a pod → in-cluster SA) |
| **External only** | `KWEBLENS_LOAD_KUBECONFIG=false` + one or more `kweblens.clusters[*]` pointing at a mounted kubeconfig |
| **Both** | in-cluster SA (local) **+** `kweblens.clusters[*]` (external) |

## Adding clusters at runtime

Clusters can be added, edited and removed without a restart. Everything below is a
non-`GET`, so it requires the admin login in both security modes, and every call is
recorded in the audit trail.

| Call | Does |
|---|---|
| `POST /api/v1/clusters` | Add a cluster. Body: `{"id","name","context","kubeconfig"}`. → `201` + the `ClusterInfo`. |
| `PUT /api/v1/clusters/{id}` | Rename / switch context / replace the credential. **Omit `kubeconfig` to keep the stored one.** |
| `DELETE /api/v1/clusters/{id}` | Close the client and delete the stored credential. → `204`. |
| `GET /api/v1/clusters/{id}/config` | How the cluster is configured — including `kubeconfigStored`, never the kubeconfig itself. |
| `POST /api/v1/clusters/contexts` | List the contexts in a kubeconfig you are about to submit. Stores nothing. |

Rules worth knowing before you wire a UI to this:

- **Only runtime clusters are editable.** `ClusterInfo.origin` is `RUNTIME` or `STATIC`;
  editing or deleting a `STATIC` one answers `409`, because it would be re-created from
  `kweblens.clusters[*]` or the kubeconfig on the next boot anyway.
- **The kubeconfig travels one way.** It goes in on `POST`/`PUT` and is never returned by
  any endpoint. A bad one is rejected `400` *before* anything is registered or persisted,
  so a failed add cannot disturb the clusters already in the rail.
- **Validation is structural, not a connection test** — building a fabric8 client does not
  connect. A cluster that is merely unreachable can still be added (which is deliberate:
  you may be adding it during an outage); a kubeconfig that does not parse, or names a
  context it does not contain, cannot.
- **Runtime kubeconfigs must embed their credentials** (`certificate-authority-data`,
  `client-certificate-data`, a token). There is no file on the kweblens host for relative
  paths to resolve against.

### Where the credential is stored

| `kweblens.cluster-store.mode` | Backend |
|---|---|
| `auto` (default) | Secrets when running in-cluster, the data directory otherwise |
| `secret` | One `Opaque` Secret per cluster, `kweblens-cluster-<id>`, in kweblens's own namespace |
| `file` | `kweblens.cluster-store.path` (default `~/.kweblens/clusters`; `KWEBLENS_DATA_DIR` moves it). The kubeconfig is written `0600` in a `0700` directory |
| `memory` | Not persisted — runtime clusters are lost on restart |

A kubeconfig is a credential, hence the split: in-cluster it belongs somewhere with
encryption-at-rest and RBAC, and off-cluster there is no API server to hold a Secret. The
active backend is reported by `GET /api/v1/about` under `clusterStore`, along with whether
it survives a restart.

**In `secret` mode the ServiceAccount needs** `get`/`list`/`create`/`update`/`delete` on
`secrets` in its own namespace. Without it kweblens still starts and still browses
clusters — it just cannot persist new ones. Point `kweblens.cluster-store.path` at a
mounted volume and set `mode=file` if you would rather not grant that.

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
| `persistence.enabled` / `storageClass` / `size` | PVC for `KWEBLENS_HELM_HOME` (Helm repo list + index cache + saved values). RWO ⇒ forces `replicas: 1` + `Recreate`. |

## Lab / private overlay

Anything environment-specific (real registry host, ingress domain, pull-secret name,
namespace) lives in a **private `kweblens-deploy` overlay**, never in this public repo —
`lab-leak-guard` enforces that. The public chart + `deploy-k8s.sh` stay arg-driven with
placeholder defaults.

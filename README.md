# kweblens

A **web-based Kubernetes IDE** — [Freelens](https://freelens.app)/Lens reimagined as a
self-hosted Spring Boot web app instead of an Electron desktop app. Point it at your clusters,
browse their resources from a browser, and expose the same read-only view to AI assistants over
MCP.

> Status: **early scaffold (0.1.0-SNAPSHOT)** — cluster registry, resource listing (namespaces,
> pods), JSON API, Thymeleaf/htmx dashboard, and an MCP server are in place. Logs, YAML editing,
> events, metrics, and exec are on the roadmap.

## Stack

- **Spring Boot 4.0.6 / Java 21**, multi-module Maven (`org.alexmond:kweblens-parent`)
- **fabric8 Kubernetes client** for cluster access
- **Thymeleaf + htmx + Bootstrap** dashboard (assets served in-jar via WebJars, no CDN)
- **Spring AI MCP server** (SSE over WebMVC) exposing read-only cluster tools
- Actuator + Micrometer/Prometheus; spring-javaformat / Checkstyle / PMD / JaCoCo gates

## Modules

| Module | What it is | Published |
|---|---|---|
| `kweblens-core` | Cluster registry, kubeconfig loading, resource access | ✅ Maven Central |
| `kweblens-cli`  | Dependency-light cluster inspector (picocli)          | ✅ Maven Central |
| `kweblens-web`  | The runnable dashboard app (REST API + UI + MCP)      | ❌ container image |
| `kweblens-it`   | On-demand operational/connectivity tasks (tag `it`)   | ❌ |

## Build & run

```bash
scripts/dev-verify.sh                       # format + full reactor verify (CI parity)
./mvnw -pl kweblens-web -am spring-boot:run  # run the dashboard on http://localhost:8080
```

The dashboard seeds your **ambient kubeconfig** (`KUBECONFIG` / `~/.kube/config`) as cluster
`default` on startup. Set `KWEBLENS_LOAD_KUBECONFIG=false` to start with no clusters.

## API

| Method & path | Returns |
|---|---|
| `GET /api/v1/clusters` | connected clusters |
| `GET /api/v1/clusters/{id}/namespaces` | namespaces in a cluster |
| `GET /api/v1/clusters/{id}/pods?namespace=` | pods (optionally scoped to a namespace) |

## CLI

```bash
java -jar kweblens-cli/target/kweblens-cli-exec.jar            # show current kubeconfig target
java -jar kweblens-cli/target/kweblens-cli-exec.jar -c staging # select a context
```

## Container image

```bash
./mvnw -Pdocker -pl kweblens-web -am package \
  -Ddocker.image.name=ghcr.io/alexmond/kweblens:0.1.0 -Ddocker.publish=true
```

## Security

The scaffold ships **open** (all endpoints permit-all, CSRF off on `/api`) so it is immediately
usable. kweblens exposes cluster data — put authentication in front before any real deployment,
and give the pod a least-privilege RBAC role. See `SecurityConfig` and `CLAUDE.md`.

## License

Libraries (`kweblens-core`, `kweblens-cli`) are Apache-2.0; the server (`kweblens-web`) is
AGPL-3.0. See `LICENSE-APACHE-2.0.txt` / `LICENSE-AGPL-3.0.txt`.

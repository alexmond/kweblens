# kweblens security review rules

Read by the `security-guidance` plugin's LLM diff review and concatenated into its prompt.

This file exists because kweblens makes several security decisions that **look like defects from
a diff alone**, and re-deriving them every review costs a round trip without protecting anything.
Each entry below is a decision with a written rationale, linked to where the reasoning lives.

**This is not a blanket exclusion.** The last section lists what must STILL be flagged, including
in the same files as the accepted decisions. When a diff changes the *decision itself* rather than
working within it, that is a finding — say so.

## Accepted by design — do not report as vulnerabilities

- **One shared operator identity. No OIDC, no per-user identity, no RBAC-awareness.**
  [`docs/design/adr-001-identity-model.md`](../docs/design/adr-001-identity-model.md) is
  **ACCEPTED**: kweblens is a single-trusted-operator tool. Audit entries naming an action but no
  person, and a UI offering actions that then 403, are consequences of that decision, not
  oversights. Impersonation is the sanctioned mechanism if identity ever arrives.

- **Reads are open, writes are authenticated.** In `open-mode` (the default) every `GET` is public
  and every non-`GET` requires the admin login. This is `SecurityConfig`'s intent, not a missing
  guard. Pod exec, Helm values and the whole pod-file family are authenticated **even in
  open-mode**, because what they return is itself a secret.

- **`SSAR` is never an authorization gate.** ADR-001 rejects it explicitly: it fails open. Where
  it appears it is a UI affordance only. Do not recommend promoting it to a gate.

- **The CSRF exemption `ignoringRequestMatchers("/api/**", "/mcp/**")` is deliberate.** It is what
  makes the MCP transport callable at all. It is a **CSRF** exemption, not an authentication one —
  `anyRequest().authenticated()` still catches `POST /mcp/message`, which measures 401 in
  open-mode too.

- **`web/api/ListProjection` is a projection, NOT redaction.** It ships ConfigMap/Secret
  `data`/`stringData`/`binaryData` as keys with `null` values so a list row is not the whole
  object. It deliberately does **not** sit in `ResourceService.listRaw`, which health checks,
  overviews, `RelationService` and the MCP tools share and which needs those values. Do not report
  the `listRaw` path as a leak because the projection is absent from it.

- **`ToolRedaction` is the MCP boundary.** MCP tool output is redacted there because it leaves the
  machine and lands in inference logs. The asymmetry with the dashboard is intentional.

- **The audit logger's quoting, escaping and control-character stripping is anti-forgery, not
  over-engineering.** A target can carry attacker-influenceable text and a bare newline could forge
  a second, fake audit line. The category is pinned to INFO so `logging.level.root=WARN` cannot
  silently switch the trail off.

- **`forceConflicts().serverSideApply()` on the write path is the intended apply semantics** for a
  single operator hand-editing YAML. The preview/dry-run surfaces are described in `CLAUDE.md`;
  where an action has no real dry-run, the code says so rather than faking one.

- **The pod file browser is off by default** (`kweblens.files.enabled`), because it reads mounted
  Secrets off disk under a shared credential. Shipped off, and it says why.

- **exec-over-WebSocket and port-forward deliberately have no SSE heartbeat.** Every *SSE* endpoint
  must attach `SseKeepAlive` and `SseEndpointKeepAliveTest` enforces that in bytecode; those two
  are not SSE and are excluded on purpose.

- **Test fixtures use RFC 5737 documentation ranges** (`192.0.2.0/24`, `198.51.100.0/24`,
  `203.0.113.0/24`) and RFC 3849 for IPv6. These are reserved-for-documentation addresses, not real
  infrastructure. Do not report them as hardcoded IPs or as leaked internal topology.

- **`admin`/`admin` appears only in local dev tooling** (`scripts/dev-run.sh`) and never as an
  application default. `SecurityConfig` generates a password per run and logs it when none is set.
  Flag any change that moves a credential into `application.yml` or any other packaged resource —
  see the next section.

- **`page.$$eval(...)` in `scripts/*.mjs` is Playwright, not JavaScript `eval()`.** It runs a
  function against the elements a selector matched, inside the browser page — there is no dynamic
  code execution and no untrusted string being evaluated. It appears in the measurement scripts
  (`ui-measure.mjs`, `contrast-check.mjs`, `state-link-check.mjs`), which drive a local browser
  against a locally running instance. Do not report it as `eval` injection.

- **Tests are hermetic**: `@EnableKubernetesMockClient(crud = true)` serves an in-JVM API server and
  `kweblens.load-kubeconfig=false` keeps the registry empty. Test code that constructs clients or
  seeds objects is not touching a real cluster.

## Still report these — including inside the files above

- A credential, token, kubeconfig or private key committed to the repo in any form, including a
  "default" or "example" one in `application.yml`, a chart's `values.yaml`, a Dockerfile, or a test
  resource. `.gitignore` blocks `*.kubeconfig`, `kubeconfig` and `.kube/`; a change that
  circumvents that is a finding.
- Anything that widens `open-mode` beyond "reads are open": a non-`GET` route reachable
  unauthenticated, or a `GET` that returns exec output, Helm values, pod file contents or Secret
  values.
- A new MCP tool returning raw objects **without** going through `ToolRedaction`.
- A new SSE endpoint that does not attach `SseKeepAlive`.
- Injection reachable from cluster-controlled or user-controlled text: command construction for
  exec, path handling in the pod file browser (traversal outside `allowedRoots`), log or audit
  lines assembled without escaping, or YAML/JSON parsed with an unsafe loader.
- GitHub Actions workflows interpolating untrusted values (`inputs.*`, `github.event.*`) directly
  into a `run:` block instead of routing them through `env:`. This has happened here before and was
  a true positive.
- Any change to `SecurityConfig`, `PresentedCredentialsFilter`, the logout path, or session
  handling — these are load-bearing and have carried real bugs (#320: sign-out left the server
  session valid, so any password signed back in).
- Secret values rendered unmasked in the UI, or made multiline while masked
  (`-webkit-text-security` fails open).

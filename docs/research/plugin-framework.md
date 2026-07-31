# Plugin framework: should kweblens have one?

Issue: GH#146. Date: 2026-07-31.

The ticket says **"do not start this before the per-kind detail endpoint and the identity
decision."** Both have since landed — the detail endpoint in #150/#152 (GH#136), the
identity decision as ADR-001 (GH#135) — so the investigation is unblocked. One stated
prerequisite is still open: the per-kind catalog (GH#148). That matters, and it shapes the
answer below.

## Recommendation, up front

**Do not build a plugin framework yet.** P3-low is the right priority, and the reason is
not "we are busy" — it is that the expensive part of a plugin system is not the loading
mechanism, and building it now would freeze the wrong things.

But the mechanism question *is* now settled, so it should not be re-litigated later:

| Question | Answer |
|---|---|
| Frontend loading | **Script tags over a fetched manifest** — Spring Boot Admin's mechanism, with Headlamp's discovery timing. Not `new Function`, not Module Federation. |
| Backend loading | **`AutoConfiguration.imports` + jar on the classpath + restart.** Not PF4J. |
| Trust model | **Operator-installed, fully trusted, no sandbox** — and this is consistent with ADR-001, not a new compromise. |
| Versioning | A single coarse compatibility floor, checked at load, refusing to load on mismatch. |

## Why not now: the API surface is the cost, not the loader

Both prior-art systems chose the *same* primitive — a UMD library build with the host's
shared dependencies declared as externals mapped to globals. Neither chose Module
Federation. That primitive is roughly a hundred lines of host code. It is cheap.

What is not cheap is what it exposes. Headlamp's `window.pluginLib` publishes ~25 named
modules (React, MUI, Recharts, Lodash, Monaco, their K8s client, their common components)
and `registry.tsx` exports ~34 registration functions. Every one of those is now public
API, pinned behind a single hardcoded semver floor.

For kweblens the equivalent list would have to include Vue, the router, **Naive UI**, and
the resource-data composables. Committing to Naive UI as public API is a much larger
promise than it looks — it makes a UI-library migration a breaking change for every
plugin.

And the per-kind catalog (GH#148) is exactly the abstraction a plugin would extend. It is
still open. Publishing extension points over an abstraction that is mid-decision is how
you end up either breaking plugins repeatedly or pinning the wrong shape permanently —
which is the risk the ticket named, and it is still live.

## If and when we do build it

### Frontend: SBA's loading, adapted — because our `index.html` is static

SBA injects `<script>` tags into a **Thymeleaf-rendered** `index.html` using
`cssExtensions`/`jsExtensions` model attributes, populated by a `classpath*:` scan at
startup.

**That does not transfer directly.** kweblens's `SpaController` forwards `/`, `/ui`, `/ui/`
to a **static** `/ui/index.html` built by Vite — there is no server-rendered shell to inject
into. Two ways out:

1. Make `index.html` Thymeleaf-rendered, so we can inject at render time. Costs us the
   static-asset simplicity and couples the SPA build to a server template.
2. **The SPA fetches a plugin manifest at boot and appends the script tags itself.**

Take (2). It keeps `index.html` static, and it is exactly what Headlamp does — fetch
`/plugins`, then load each — **minus the `new Function` eval**. So the synthesis is:
**Headlamp's discovery timing, SBA's execution mechanism.** Neither project does precisely
this; it is the combination that fits our shape.

The backend half is straight SBA and needs no invention: a `classpath*:` resource scan for
plugin assets, exposed as a manifest endpoint plus a resource handler.

### Why not `new Function` (Headlamp's choice)

It requires `script-src 'unsafe-eval'`. kweblens **sets no CSP today** — I checked, there is
none configured — so this is not currently a live constraint. But adopting `unsafe-eval`
into the design would permanently foreclose a strict CSP later, to buy error containment
we can get other ways. Headlamp's own hardening (a captured `PrivateFunction` so plugins
cannot monkeypatch `window.Function`) is plugin-vs-plugin hygiene, not a security boundary
— it does not stop a malicious plugin, and their docs say so.

### Why not Module Federation

`@module-federation/vite` is genuinely healthy (1.20.1, released two days ago) and would
work. It is the wrong weight: its value is versioned shared-dependency *negotiation* across
independently deployed hosts, and we have exactly one host. Its own default loader path is
native `import()` anyway, so it does not even buy CSP-safety we would not otherwise have.

`@originjs/vite-plugin-federation` should be ruled out regardless — ~15 months without a
release and 235 open issues, and the maintained project ships a migration guide *away* from
it.

Import maps are structurally awkward here: an import map cannot be an external file, so
under a strict CSP it needs a per-request nonce on an inline script — which drags us back to
a server-rendered shell, the thing option (2) exists to avoid. SystemJS is effectively
dormant (last release April 2024) and solves a browser-support problem we do not have.

### Backend: no framework needed

**Neither prior-art project has a backend plugin runtime.** Headlamp's Go backend only
watches directories and serves files — it executes no plugin code. SBA's "backend half" is
just "it is a Spring jar, so its beans come along for free."

That is available to kweblens for nothing: a plugin jar carrying
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` is
picked up at context bootstrap. Jar in, restart, done.

**PF4J is over-engineering for this.** Its distinguishing feature is a per-plugin
classloader enabling true hot load/unload — which we do not need if the deployment model is
"restart the pod." And the Spring integration is weak: `pf4j-spring`'s own README opens by
calling itself *"a proof of concept"*, and `sbp` (the project that actually addresses Boot
integration) has no releases and unevaluated Boot 4 / Java 21 compatibility.

**A specific risk if we ever want external jar directories** rather than baked-in ones:
that needs `PropertiesLauncher`, which requires the `ZIP`/`DIR` layout on
`spring-boot-maven-plugin`. We build our image with **Cloud Native Buildpacks**, and whether
Paketo's Spring Boot buildpack handles that layout cleanly is **unverified** — it would need
a test build before anyone depends on it. Classpath-at-startup avoids the question entirely,
which is another reason to prefer it.

### Trust: say it plainly

A kweblens plugin would run with the server's cluster credentials for **every** registered
cluster. It can read every Secret in every one of them. That makes a plugin **strictly more
privileged than a logged-in admin using the UI**.

Headlamp is explicit that plugins run in the same JavaScript context and must be trusted,
and mitigates only through distribution controls (downloads restricted to
GitHub/GitLab/Bitbucket, checksums, an official-only catalog default).

For kweblens this is less of a compromise than it first appears: **ADR-001 already accepted
a single trusted operator** as the identity model. "Plugins are operator-installed and
fully trusted" is *consistent* with that decision rather than a new weakening of it. It
should still be written down explicitly rather than left implied — and it is a reason not to
build a plugin catalog or any "install from a URL" flow, which would quietly turn a trusted
extension point into an untrusted one.

### Versioning

Headlamp checks the plugin's declared toolchain version against one hardcoded semver range
and refuses to load on mismatch, surfacing a warning. It is coarse — one range for the whole
API surface — but it is honest and it fails closed. Copy it. Per-extension-point versioning
is not worth the machinery at our scale.

## What would change this answer

The trigger to revisit is a concrete extension someone actually wants — most plausibly a
kind-specific detail panel for a CRD that we will not ship in-tree. One real use case would
tell us which two or three extension points matter, which is far better input than
enumerating all thirty-four Headlamp has.

Until then the useful discipline is not to foreclose it: when the per-kind catalog (GH#148)
lands, that is the natural seam a plugin API would attach to, and it is worth building it as
if something external might one day register a kind — without publishing that as API.

## Confidence and gaps

Mechanisms above were read from the projects' actual sources (Headlamp's
`frontend/src/plugin/runPlugin.ts` and `registry.tsx`, SBA's `UiExtensionsScanner` and
`frontend/index.ts`, Module Federation's `runtime-core/src/utils/load.ts`), not from
marketing pages. Two things are explicitly **unverified**: the Paketo buildpack's handling
of the `ZIP`/`PropertiesLauncher` layout, and whether PF4J's classloader would collide with
Boot's `LaunchedClassLoader` over shared Spring/fabric8 classes. Both only matter if we
reject the classpath-at-startup recommendation, and neither was worth a test build to settle
a question we are recommending against anyway.

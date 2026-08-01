# scripts/

Development helpers. Each exists because doing the same thing by hand went wrong at least
once — the reason is in the header comment of each script.

| Script | What it does |
|---|---|
| [`dev-verify.sh`](dev-verify.sh) | Format + full-reactor `verify`. **Green here means green on the PR.** |
| [`dev-test.sh`](dev-test.sh) | Targeted `-Dtest` run; last argument is the selector. |
| [`dev-run.sh`](dev-run.sh) | Run the app locally with a known login (`admin`/`admin`). |
| [`contrast-check.mjs`](contrast-check.mjs) | Measure WCAG contrast of rendered UI in **both** themes. |
| [`perf-sweep.mjs`](perf-sweep.mjs) | Walk every nav leaf, fail on slow loads or main-thread hangs. |
| [`pr-watch.sh`](pr-watch.sh) | Wait for a PR's checks; optionally merge when they pass. |
| [`deploy-k8s.sh`](deploy-k8s.sh) | Build/push the image and `helm upgrade --install`. |

## Before committing

```bash
scripts/dev-verify.sh                  # the gate — mirrors CI exactly
scripts/dev-verify.sh -pl kweblens-web -am    # extra args pass through to Maven
scripts/dev-test.sh 'ResourceServiceTest,Cluster*'
```

## Running it locally

```bash
scripts/dev-run.sh                 # build if needed, run on :8080, login admin/admin
scripts/dev-run.sh --build         # force a rebuild first
scripts/dev-run.sh --sim           # no cluster needed — the built-in simulator
scripts/dev-run.sh --ai            # LLM enrichment of /diagnose (needs an Anthropic key)
scripts/dev-run.sh --files         # pod file browser ON, read-write
scripts/dev-run.sh --files=ro      # pod file browser ON, browse-and-download only
scripts/dev-run.sh --port 8085     # a second instance alongside the first
scripts/dev-run.sh --stop          # stop whatever is on the port
```

**Always start it this way rather than `java -jar`.** With no admin password set,
`SecurityConfig` generates one per run and only writes it to the log — so `admin`/`admin`
silently stops working and the reason is buried in the startup output. `dev-run.sh` always
passes the dev credentials, and *fails loudly* if a password gets generated anyway.

The credentials are passed as environment at run time on purpose. Do not move them into
`application.yml`: that would bake a default password into the repository.

`--files` switches on the pod file browser (`kweblens.files.enabled`), which is off by
default because a container's disk holds mounted Secrets and its service-account token.
`--files=ro` keeps `kweblens.files.writable=false`, which is what to use when only the
refusal paths matter. Without the flag the Files tab correctly reports that the feature is
switched off, which looks like a bug and is not one.

`--ai` reads an Anthropic key from `ANTHROPIC_API_KEY`, falling back to
`VANTAGE_ANTHROPIC_API_KEY`. **The key is never written to a file** — the flag refuses to
start without one rather than booting a build whose AI silently does nothing. Only the prose
summary on `GET /api/v1/clusters/{id}/diagnose` depends on it; the findings themselves, the
remediation proposals and the server-side dry run are all deterministic and need no key.

## Checking the UI

Both browser scripts drive a running instance, so start one first. They use the
**account-wide Playwright install** — never `npm i playwright` into this repo:

```bash
scripts/dev-run.sh
export NODE_PATH=$HOME/.local/lib/playwright/node_modules

node scripts/contrast-check.mjs                        # the default watchlist
node scripts/contrast-check.mjs '.leaf.active' '.badge'
PORT=8085 node scripts/contrast-check.mjs
PREPARE='press:Control+k;fill:.palette-input=pod' node scripts/contrast-check.mjs '.palette-row.active'

node scripts/perf-sweep.mjs                            # needs a cluster or --sim
```

`contrast-check.mjs` exists because eyeballing colour has failed here repeatedly — the
StatusBadge tag shipped at 1.93:1 (#169), and the command palette's first two stylings
measured 3.02:1 and 3.80:1 while looking perfectly fine (#200). It composites translucent
backgrounds over their real backdrop, which is the step hand-calculation gets wrong, reads
the active theme from the DOM rather than assuming a toggle order, and **exits non-zero**
when anything is under its floor, so it can gate a change.

A selector that is not currently on screen reports `not present` rather than passing —
use `PREPARE` to open the thing first. Treat a screenful of `not present` as a failed run,
not a clean one.

`PREPARE` steps are `press:` / `click:` / `fill:<sel>=<text>` / `upload:<sel>=<path>` /
`wait:<ms>`, and a step prefixed with `?` is skipped when its selector is not on screen.
The `?` matters for anything behind the login: `PREPARE` runs once per theme, so a sign-in
that only applies to the first pass would otherwise stall the second one until it times
out. `upload:` reaches UI that only exists once a file has been picked. Measuring the pod
file browser's save confirmation, for instance, means signing in and walking to a file:

```bash
PREPARE='?click:.linkbtn:has-text("Sign in");?fill:.n-modal input[type=password]=admin;…' \
  node scripts/contrast-check.mjs '.files-saved'
```

One limitation to know: the backdrop is composited one level up, so a nested element
inside a translucent panel is measured against that panel's colour at full opacity and can
read far darker than it is. Measure the panel — its text-bearing children are sampled with
it — rather than a bare descendant selector.

## Watching CI

```bash
scripts/pr-watch.sh 200            # print each check as it lands, exit 1 on any failure
scripts/pr-watch.sh                # the PR for the current branch
scripts/pr-watch.sh 200 --merge    # squash-merge, but only if everything passed
```

## Deploying

See [`docs/deployment.md`](../docs/deployment.md). `deploy-k8s.sh` is argument-driven and
carries no environment defaults; the lab-specific values live in the private deploy
overlay, never here.

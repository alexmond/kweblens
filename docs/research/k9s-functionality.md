# k9s: what it actually does, and what a `kweblens-tui` would owe it

Issue: GH#143 (epic). Date: 2026-08-13. k9s version studied: **v0.51.0** (commit `558caafe`, built
2026-06-06), source at `github.com/derailed/k9s`.

Everything below marked *observed* was produced by driving the real binary under a pty against a
live k3s cluster (context `k3stest`, read-only navigation only — no delete, drain, cordon, scale,
restart, edit or exec was ever pressed). Everything marked *read* comes from the v0.51.0 source.
Anything I could not check either way is marked **unverified** rather than smoothed over.

## Answers to the epic's three open questions, up front

The epic left three questions open. They are answered here so the sub-issues do not carry them
forward.

| Question | Answer |
|---|---|
| Direct via `kweblens-core`, or via a running kweblens server? | **Direct, as the primary.** A server transport is a *second* adapter behind the same port, later and optional. Reasons in [Direct or via the server](#10-direct-or-via-the-server). |
| How much of the SPA's per-kind catalog is shareable vs inherently visual? | Of **87** column entries across **28** kinds: **49** are plain path reads (shareable for free), **31** are computed (shareable as *values*, which is GH#148 option (b)), **7** already read a server-computed field. The inherently-visual part is not the catalog at all — it is three cell *renderers* in `ResourceTable.vue`, and k9s ships a text equivalent for each. See [The per-kind catalog](#9-the-per-kind-catalog-and-the-slice-of-gh148-the-tui-actually-needs). |
| Live updates: what is the TUI analogue of the rAF rule? | **Buffer + fixed-period flush**, and it must be gated by a test the way `useResourceData.test.ts` gates the SPA. k9s does *not* solve this the way the SPA does, and the difference matters. See [The live-update model](#6-the-live-update-model). |

## Recommendation, up front

Build the TUI **directly on `kweblens-core`**, not against a running kweblens server, and treat the
server as a later second transport behind a narrow data-source port.

The single most-quoted blocker — GH#148, the per-kind catalog — is **mostly not a blocker**. The
part the TUI genuinely needs is one small, nameable slice: **the per-kind state field is computed in
`kweblens-core` but attached at the web boundary**, so a direct consumer of `ResourceService.listRaw`
gets objects with no verdict on them. That is a real block and it is small. "Build the whole catalog
first" is more than is required, and sequencing the epic behind it would park the module for no
gain.

The TUI library pick is **TamboUI 0.4.0 on the JLine 3.30.16 line**, with JLine's
`ScreenTerminal` for the exec pane and **Lanterna 3.1.5** as the fallback — because a k9s-like
tool is a stateful selectable table and TamboUI is the only candidate whose primary widget is one.
It is also a **0.4.0 library**, so that recommendation is a bet, and it should be settled by a spike
before a module exists rather than after. Reasoning, versions and the measured evidence are in
[JVM terminal-UI libraries](#11-jvm-terminal-ui-libraries).

---

## 1. The shape of k9s

k9s is a single Go binary, ~132 MB, that talks straight to the API server with `client-go`. It has
no server component and no daemon. It renders with **`derailed/tview` v0.8.5** over
**`derailed/tcell/v2` v2.3.1-rc.4** — both are the author's own forks of `rivo/tview` and
`gdamore/tcell`. It vendors real Kubernetes machinery rather than reimplementing it: `k8s.io/client-go
v0.35.3`, `k8s.io/apiextensions-apiserver`, `k8s.io/cli-runtime`, and — this one matters — **`k8s.io/kubectl`**.

Two consequences of that last dependency are load-bearing for anything trying to be k9s on the JVM:

- **`describe` is `kubectl`'s describe.** `internal/dao/describe.go` imports `k8s.io/kubectl/pkg/describe`
  and hands the object to the stock describer (*read*, and the output *observed* — the `Describe`
  view is byte-for-byte kubectl's layout, down to `Replicas: 1 desired | 1 updated | 1 total | 1
  available | 0 unavailable`). Rollout-restart and rollback likewise go through
  `k8s.io/kubectl/pkg/polymorphichelpers` (`internal/dao/dp.go:427`, `internal/dao/rs.go:107`).
- **Some actions are not implemented at all — they shell out to `kubectl`.** `internal/view/exec.go`'s
  `runK()` does `exec.LookPath("kubectl")`, appends `--context`, `--kubeconfig`, `--as`,
  `--as-group`, suspends the tview application, and runs it as a child process. Shell (`s`), attach
  (`a`), transfer (`t`, i.e. `kubectl cp`) and edit (`e`, via `$K9S_EDITOR` / `$KUBE_EDITOR` /
  `$EDITOR`) all go this way. **k9s without `kubectl` on `PATH` loses shell, attach, copy and edit.**

There is no JVM port of `kubectl`'s describers or of `polymorphichelpers`. A JVM TUI cannot buy
`describe` for the price of an import, and suspending the JVM to shell out to `kubectl` would be a
strictly worse version of what kweblens already has in-process (`ExecService` bridges a real
`ExecWatch` to an `OutputStream` today). This is the first concrete argument that a `kweblens-tui`
is **not** a port of k9s: the two projects have opposite relationships with the `kubectl` codebase,
and kweblens's side of that trade is already paid for.

---

## 2. Navigation and the command line

### The command line

`:` opens a prompt. The grammar is small and fully specified by
`internal/view/cmd/{interpreter,args,types}.go` (*read*):

```
:<cmd> [namespace] [/filter] [-f fuzzy] [@context] ['label=selector']
```

- `cmd` is lowercased and matched against the alias table.
- **`/…`** sets a filter, **`-f …`** a fuzzy filter, **`@…`** a context, and a bare token is the
  namespace — except after `ctx` (where it is the context), after `dir`/`ls` (a path), and after
  `xray` (the first bare token is the GVR, the second the namespace).
- Anything containing `=`, `==`, `!=`, ` in ` or ` notin ` is taken as a **label selector**, parsed
  by `k8s.io/apimachinery`'s `labels.Parse`. Single quotes group a selector containing spaces; an
  unmatched quote logs an error and **discards the rest of the line** rather than guessing.
- A handful of commands are hard-coded rather than aliased: `ctx|context|contexts`,
  `ns|namespace|namespaces`, `dir|dirs|d|ls`, `q|q!|qa|Q|quit|exit`, `?|h|help`, `a|alias|aliases`,
  `x|xr|xray`, `can`, and `cow` (an easter egg). `can` has its own regex, `^can\s+([ugs]):\s*([\w-:]+)\s*$`.

*Observed*: typing `:pods` shows an **inline suggestion** completing to a fully-qualified GVR
(`pods.v1beta1.metrics.k8s.io` on this cluster) — completion is over the whole discovered alias set,
not a fixed list.

### Aliases are discovery-driven, not curated

`:aliases` *observed* **125 rows** on a plain k3s cluster with Traefik installed. The table is
`RESOURCE / GROUP / VERSION / COMMAND`, and every discovered resource — including every CRD — gets a
row with its singular, plural, short names and fully-qualified forms all bound as commands
automatically. The hand-written `aliases.yaml` template ships only **eight** entries (`dp`, `sec`,
`jo`, `cr`, `crb`, `ro`, `rb`, `np`); the other ~117 come from API discovery.

This is a genuine architectural difference from kweblens, and it cuts both ways. kweblens's
`NavCatalog` is a curated registry — **7 static categories, 39 built-in kinds** (*verified by reading
the file*, not by trusting the docs) — plus CRDs discovered by `ClusterNavService` and grouped by API
group, plus a Gateway category promoted when those CRDs exist. Curation is why kweblens's left menu
reads as a product and k9s's alias list reads as a dump. Discovery is why k9s can address a resource
kweblens has never heard of, immediately, with no code change.

**A TUI wants both**, and kweblens can have both cheaply: `NavCatalog` for the ordered menu, plus a
`:` line that resolves *any* discovered GVR the way k9s does. That is a ticket, not a research
question.

### The view stack, breadcrumbs and `<esc>`

*Observed*, drilling `deployments` → `<enter>` → `pods` → `<enter>` → `containers`:

- The bottom line grows a crumb per level: `<deployment>`, then `<deployment>   <pod>`, then
  `<deployment>   <pod>   <containers>`.
- The frame title carries kind, scope and count: `deployments(kube-system)[4]`, and with a filter
  active it appends the filter — `pods(all)[1] </coredns>`.
- **Drill-down is implemented as a filter, and says so.** Entering a Deployment produces
  `pods(kube-system/coredns)[1] </k8s-app=kube-dns>` — the title shows the owner path *and* the
  label selector it derived. The relationship is not a hidden join; it is a visible, editable
  filter. That is a good idea worth stealing outright.

`<esc>` is overloaded and the order matters (*read*, `internal/view/browser.go:152`): in a table it
is bound to **Filter Reset**; the page-stack pop happens when there is no filter to clear. In a
detail view (`details.go:140`, `live_view.go:148`) it is **Back**. `q` is bound to the same handler
as `<esc>` in every one of those places.

History is separate from the stack: `[` previous command, `]` next command, `-` last view
(`internal/view/app.go:259-261`). So the stack is spatial and the history is temporal, and both are
navigable — kweblens's shell has neither.

### Namespace switching

*Observed*: number keys are bound to a **most-recently-used favourites list** — `<0> all`, then
`<1>`, `<2>`… filled from `Config.FavNamespaces()` in MRU order. Starting in `kube-system` and then
visiting `default` produced `<1> kube-system  <2> default`. `w` warps to a namespace picker; `:ns`
opens the Namespaces table where `u` sets the active one.

---

## 3. Filtering and search

### k9s's grammar

`/` opens the filter line. What happens next is decided by `internal/helpers.go` and
`internal/model1/table_data.go` (*read*), and every branch below was *observed* against `pods` on
`k3stest`:

| Input | Branch | Semantics |
|---|---|---|
| `coredns` | regex | `regexp.Compile("(?i)(" + q + ")")`, matched against the **visible filterable columns** joined by a spacer. Case-insensitive. → 1 of 7 rows |
| `!coredns` | inverse regex | leading `!`, same regex, complement. → 6 of 7 rows |
| `-f crdns` | fuzzy | `sahilm/fuzzy` over the row **ID** (`namespace/name`) only — *not* over other columns. → matched `coredns-8b64bcf7c-tgwnc` from `crdns` |
| `k8s-app=kube-dns` | label selector | `labels.Parse`, applied at the informer's `Lister().List(selector)`, i.e. **server-shaped**, not a row scan. → 1 of 7 rows |
| `Running Completed` | **no-op** | `rxFilter` opens with `if strings.Contains(q, " ") { return t.rowEvents, nil }` — a filter containing a space silently matches everything. *Observed*: the title showed `pods(all)[7] </Running Completed>`, i.e. filter displayed, zero rows removed |

That last row is not a bug report, it is a design note: k9s has **no conjunction**. There is one
term, and a space is how it tells you so — silently. There is also no field-scoped term (no
`name:`, no `ns:`); scoping is done by *navigating* (change namespace, change kind) rather than by
qualifying the query.

`<esc>` clears the filter and restores the full list (*observed*). `ctrl-z` toggles a "faults only"
filter that keeps rows whose `VALID` column is non-empty (`filterToast`, *read*). Fuzzy filtering is
also available inside the log, YAML, describe and Helm-values viewers, where the same `-f` prefix
switches `fuzzy.Find` in for the regex path (`internal/model/{log,yaml,describe,rev_values}.go`).

### How kweblens's `objectFilter.ts` compares

kweblens's grammar is a strict superset in expressive power and a subset in reach.

kweblens supports: whitespace-separated **terms combined with AND** (`filter.terms.every(...)`), any
term negatable with a leading `-`; bare words and `"quoted"` strings as case-insensitive substrings
over name ∪ namespace ∪ kind; `/regex/`; field-scoped `name:` / `ns:` / `namespace:` / `kind:` (each
of which can itself take a bare, quoted or regex value); `status:` as a **whole-label exact match**
against kweblens's own state vocabulary; and the full apimachinery label-requirement set — `k=v`,
`k==v`, `k!=v`, `k in (a,b)`, `k notin (a)`, plus `label:k` / `-label:k` for presence and absence.
A query that does not parse **matches everything** and reports the parse error, because *"no pods
match" and "your pattern is broken" are different claims*.

Feature-by-feature:

| | k9s | kweblens |
|---|---|---|
| Combine terms | **no** — a space disables the filter | AND, any number of terms |
| Negation | `!` on the whole filter | `-` on any individual term |
| Regex | default, case-insensitive, implicit | explicit `/…/`, case-insensitive |
| Substring | via the implicit regex | explicit, and the default |
| Fuzzy | **yes** (`-f`), over `ns/name` | **no** |
| Field-scoped | **no** | `name:`, `ns:`, `namespace:`, `kind:` |
| Status/state | via the implicit regex over the STATUS cell | `status:` against the server's state vocabulary, exact |
| Label selectors | full apimachinery, **pushed to the lister** | full apimachinery, evaluated client-side |
| Bad input | regex compile failure logged, filter ignored | named error surfaced, filter matches everything |

Two real gaps in kweblens's favour-of-k9s column: **fuzzy matching** is genuinely nice for pod names
with hash suffixes and kweblens has nothing like it; and k9s's label selectors reach the **API
server**, so a label filter over a huge namespace is cheap, whereas kweblens's is a client-side scan
over an already-fetched list. The second is deliberate — kweblens's header says so, because a filter
over a truncated page reports "no matches" for an object that exists — but the reasoning would want
revisiting if a TUI ever pages.

**For the TUI: reuse the grammar, not the implementation.** `objectFilter.ts` is 614 lines of
TypeScript with 60 tests; a Java port is a real cost and a real drift risk. The alternative — lift
the grammar into `kweblens-core` and have the SPA call it — is a much bigger change than this epic
should carry. The honest v1 answer is a **Java parser for the same documented grammar, with the same
test corpus ported**, and `FILTER_HELP` (13 rows, already exported so the UI help cannot drift from
the parser) as the shared specification. That is a ticket with a verifiable "done when".

---

## 4. Per-resource actions and their keystrokes

Read from the `bindKeys` / `refreshActions` functions across `internal/view/`, and cross-checked
against the *observed* hint bar, which k9s renders live from the same `KeyActions` map
(`HydrateMenu(b.Hints())`) — so the menu cannot drift from the bindings. That self-documenting
property is itself worth copying.

**Global** (`app.go`): `?` help · `ctrl-a` aliases · `[` back · `]` forward · `-` last view ·
`ctrl-e` toggle header · `ctrl-g` toggle crumbs · `ctrl-c` quit · `:` command · `<enter>` goto.

**Any table** (`view/table.go`, `ui/table.go`): `/` filter · `<space>` mark · `ctrl-<space>` mark
range · `ctrl-\` clear marks · `ctrl-s` save to disk · `ctrl-z` toggle faults · `ctrl-w` toggle wide
columns · `shift-n` sort name · `shift-a` sort age · `shift-s` sort status · `shift-o` sort the
selected column · `shift-p` sort namespace (**appears only in all-namespaces mode** — `doUpdate`
adds and deletes that binding as the scope changes).

**Any resource** (`browser.go:refreshActions`): `<enter>` view/drill · `c` copy name ·
`ctrl-r` refresh · `y` YAML · `d` describe · `n` copy namespace · `w` warp to namespace · `0`–`9`
namespace favourites · `e` **edit** · `ctrl-d` **delete**.

The last two are conditional in a way worth naming: `client.Can(b.meta.Verbs, "edit"/"delete")` —
k9s reads the **discovery `Verbs`** for the resource type and hides the action if the API does not
declare the verb. It also calls a real SSAR (`CanI`) before offering the all-namespaces key. Under
`--readonly` every "dangerous" binding is dropped wholesale. *Observed*: the header reads `[RW]`
normally and `[R]` under `--readonly`, and in `[R]` mode Edit, Delete, Kill, Shell, Attach,
Transfer, Sanitize, Restart and Scale all vanish from the hint bar — while **Port-Forward stays**,
i.e. k9s does not classify port-forward as a write.

**Pods** (`pod.go`): `o` show node · `ctrl-k` kill · `s` shell · `a` attach · `t` transfer
(`kubectl cp`) · `z` **sanitize** — bulk-deletes every completed/errored pod in the namespace behind
a dialog that makes you type the literal string `Yes Please!` (`magicPrompt`).

**Extenders**, mixed into the kinds that support them: `l` logs, `p` logs-previous
(`logs_extender.go`) · `s` scale (`scale_extender.go`) · `r` restart (`restart_extender.go`) ·
`f` show port-forwards, `shift-f` create one (`pf_extender.go`) · `shift-j` jump to owner
(`owner_extender.go`) · `i` set image (`image_extender.go`).

**Nodes** (`node.go`): `c` cordon · `u` uncordon · `r` **drain** · `y` YAML · `s` **node shell** —
gated behind `FeatureGates.NodeShell` and a configured `shellPod`, which k9s launches as a
privileged pod with `hostPathVolume` (default image `busybox:1.37.0`).

**CronJobs** (`cronjob.go`): `t` trigger · `s` suspend/resume.
**ConfigMaps/Secrets** (`cm.go`): `u` used-by. **Namespaces** (`ns.go`): `u` use.
**Contexts** (`context.go`): `r` rename. **Port-forwards** (`pf.go`): `b` benchmark run/stop,
`<enter>` view benchmark results, `ctrl-d` delete the forward.

**Detail viewers** (`details.go`, `live_view.go`, `logger.go`): `f` toggle full-screen · `c` copy ·
`n` / `shift-n` next / previous match · `/` filter · `ctrl-s` save · `r` toggle auto-refresh ·
`x` toggle decode (Secrets) · `<delete>` erase.

### Marks and multi-select

`<space>` marks a row, `ctrl-<space>` marks a range, `ctrl-\` clears. Marked rows become the target
set for `ctrl-d` (delete) — so bulk delete is a first-class flow. kweblens has no multi-select
anywhere. Whether it should is a product question that ADR-001 does not settle, but a TUI without
marks will feel wrong to a k9s user, and marks + bulk delete is the single most dangerous thing in
k9s's key map. If it lands, it lands behind kweblens's existing confirm discipline, not k9s's.

---

## 5. The sub-views

- **`:xray <kind> [ns]`** — *observed* against `dp kube-system`. A collapsible tree that walks
  Deployment → namespace → ReplicaSet → Pod → its Containers, ConfigMaps, Secrets and
  ServiceAccounts, with per-node counts and ready ratios (`coredns(1) [1/1/0]`,
  `coredns-8b64bcf7c-tgwnc(4) [1/1]`). Keys: `<space>` expand/collapse, `x` expand all, `<enter>`
  goto. Implemented in `internal/xray/` (~2,700 lines) with a hand-written traversal per kind
  (`pod.go`, `dp.go`, `rs.go`, `sts.go`, `ds.go`, `svc.go`, `sa.go`, `ns.go`, `container.go`,
  `generic.go`).
  **kweblens has the data for this already** — `RelationService` computes 12 relation keys across
  12 kinds server-side, and its javadoc says outright that doing the joins there means "one
  implementation serves the SPA, **a future TUI** and the agent tool surface". An xray-equivalent is
  a rendering job over an existing service, not a new traversal.
- **`:pulses`** — *observed*. A grid of live gauges, one card per kind (Nodes, Namespaces, Services,
  Events, Pods, Deployments, StatefulSets, DaemonSets, Jobs, CronJobs, PVs, PVCs, HPAs, Ingresses,
  NetworkPolicies, ServiceAccounts…), drawn with `internal/tchart` (dot-matrix digits, gauges,
  sparklines). It is a dashboard, and kweblens's Cluster overview is the same idea with better
  typography.
- **`:popeye`** — **gone.** *Observed*: `:popeye` produced `Ruroh? 'popeye' command not found` in an
  ASCII-cow error dialog. `grep -ri popeye internal/` returns nothing outside `change_logs/`. It was
  removed at 0.5.0, reinstated at 0.19.0, and has been removed again by 0.51.0. **Any plan or doc
  that lists popeye as a k9s feature is out of date** — including the framing in this epic. The
  nearest surviving thing is `ctrl-z` "toggle faults" and the pod `z` sanitize action, neither of
  which is a linter.
- **Benchmarking** — still present. `internal/perf/benchmark.go` wraps `rakyll/hey`; `b` on a
  port-forward starts/stops a run, and `:benchmarks` lists saved results from
  `~/.local/state/k9s/benchmarks`. Config in `benchmarks.yaml` (`concurrency`, `requests`).
  Unverified end-to-end — I did not start a port-forward against the live cluster.
- **Image vulnerability scanning** — `internal/vul/`, backed by `anchore/grype` + `anchore/syft`
  linked into the binary. Off by default (`imageScans.enable: false`, *observed* in the generated
  config). Adds a `VS` column and an `:imagescans`-style view with sort keys for library, severity,
  fixed-in and vulnerability. This is a large capability kweblens does not have and, given it
  embeds a whole SBOM/vuln stack, is not a small thing to add.
- **RBAC** — `:can u:<user>` / `g:<group>` / `s:<sa>` opens a rules view; `:rbac`, `:users`,
  `:groups` and pressing `<enter>` on any Role/ClusterRole/binding show the resolved rule table.
  *Observed*: `:can u:system:anonymous` opened a `<rules>` view and reported
  `Synchronizing policy in "kube-system" namespace...`. kweblens has nothing here, and under ADR-001
  it deliberately treats SSAR as a UI affordance rather than a gate — but *displaying* who can do
  what is not an authorization gate, so an RBAC viewer would not conflict with the ADR.
- **Node shell** — a privileged pod on the node, gated and configured (`shellPod`). kweblens has no
  equivalent and, given the pod file browser is already off by default, adding one would need its
  own gate and its own argument.
- **Screen dumps** — `ctrl-s` in most views writes the current content to
  `~/.local/state/k9s/screen-dumps`, and `:screendumps` browses them.
- **Directory mode** — `:dir <path>` / `:ls` browses local manifests, with `a` apply, `e` edit,
  `d` delete, `y` YAML. A local-file surface inside the cluster browser.
- **Config files** — six of them, all YAML with published JSON schemas
  (`internal/config/json/schemas/`): `config.yaml`, `aliases.yaml`, `hotkeys.yaml`, `plugins.yaml`,
  `views.yaml`, `jumps.yaml`, plus `skins/*.yaml` and per-context state under
  `~/.local/share/k9s/clusters`. With `ui.reactive: true` k9s watches config, skins, custom views
  and jumps with `fsnotify` and hot-reloads them (`app.go:348`); with it false (**the default**,
  *observed*) they are read once at view init.
- **Skins** — a full colour theme file; the stock skin sets ~40 named colours across body, prompt,
  help, frame, table, views, dialog and xray. Truecolor names and hex both work.
- **Hotkeys** — `shortCut` → `command` + `description`, i.e. bind any key to any `:` command.
- **Plugins** — `shortCut`, `scopes` (which views it appears in), `command` + `args`, `confirm`,
  `background`, `dangerous`, `pipes`, `overwriteOutput`, and `inputs`. A k9s plugin is **an external
  binary invoked with the selected row's context substituted in**. This is worth putting next to
  `docs/research/plugin-framework.md`: kweblens's rejected-for-now plugin design was an in-process
  extension API with a large published surface; k9s's is a *shell-out with a keybinding*, whose
  entire public API is a handful of substitution variables. If kweblens ever wants plugins, k9s's
  model is dramatically cheaper and would suit a TUI better than the SPA.
- **Custom columns** — `views.yaml` maps a GVR to `columns: [...]` and a `sortColumn`. The column
  spec grammar (`internal/render/cust_col.go`) is:

  ```
  NAME:<jsonpath-or-jq-expression>|<flags>       flags ⊂ {N number, T age, W wide, S show, L/R align, H hide}
  ```

  Paths go through `kubectl`'s own `get.RelaxedJSONPathExpression`; jq expressions are evaluated with
  `itchyny/gojq`. **This is GH#148's option (c), shipped** — and note what it is *not*: k9s pairs it
  with **48 hand-written per-kind renderer files** in `internal/render/` for everything JSONPath
  cannot express. So the real-world data point for #148's design fork is *"built-in renderers per
  kind, **plus** JSONPath/jq for user extras"* — i.e. (a)+(c). k9s never needed (b), server-computed
  values, because it has no server. kweblens does, which is why (b) is available to it and is the
  right answer for the computed columns.

---

## 6. The live-update model

This is the part of k9s that is most often described wrongly, so it is worth being exact.

**k9s does not render on watch events.** Three layers, all *read*:

1. **`internal/watch/factory.go`** builds a `dynamicinformer.DynamicSharedInformerFactory` per
   namespace, `defaultResync = 10 * time.Minute`. The informer consumes the watch stream and
   maintains a local cache. Nothing above it ever sees an individual event.
2. **`internal/model/table.go`** runs a `updater()` goroutine that ticks on `time.After(rate)`,
   calls `refresh()`, which lists the whole collection **out of the informer's lister**, re-renders
   every row, and fires `TableDataChanged` to its listeners. The first tick is at
   `initRefreshRate = 300ms`; every subsequent tick is at the configured `refreshRate`. Failures go
   through `cenkalti/backoff` exponential retry, and a run of failures fires `TableLoadFailed` and
   exits the reconciler.
3. **Overrun protection** is a compare-and-swap, not a queue:
   ```go
   if !atomic.CompareAndSwapInt32(&t.inUpdate, 0, 1) {
       slog.Debug("Dropping update...")
       return nil
   }
   ```
   If a refresh is still running when the next tick arrives, **the tick is dropped**. There is no
   backlog and no catch-up.

**`refreshRate` has a hard floor of 2.0 s.** `internal/config/flags.go` sets
`DefaultRefreshRate float32 = 2.0`, and `K9s.GetRefreshRate()` clamps anything below it back up,
warning once. So `refreshRate: 1` in `config.yaml` — or `--refresh 1` — **does nothing**; you get 2 s.
That is checkable and surprising, and it is the honest answer to "what does its refresh rate config
actually do".

The one place k9s *is* event-driven is logs, and there it does exactly what the SPA does. In
`internal/model/log.go`, incoming lines are appended to a buffer and the view is notified only when
either the buffer overflows `logOptions.Lines` **or** a timer fires:

```go
case <-time.After(l.flushTimeout):
    l.Notify()
```

with `defaultFlushTimeout = 50 * time.Millisecond` (`internal/view/log.go:36`). Fifty milliseconds
is 20 flushes per second; `requestAnimationFrame` is ~16.7 ms and 60. **That is the direct
analogue** of kweblens's rAF batching, arrived at independently and set three times slower because a
terminal repaint is more expensive than a Vue patch.

### What this means for `kweblens-tui`

kweblens's gate says a burst must coalesce to **≤1 `objects` update per animation frame**, and
`useResourceData.test.ts` proves it by firing 157 `ADDED` events — the real ReplicaSet count that
once froze a tab — and asserting `updates === 1` after one frame, then `2` after the next.

The TUI analogue is **≤1 screen repaint per flush tick**, where the tick is a wall-clock period
rather than a frame. Concretely:

- **Keep the event-driven watch.** `ResourceService.watchRaw(clusterId, descriptor, namespace,
  BiConsumer<String, GenericKubernetesResource>)` already exists in core and already returns a
  fabric8 `Watch`. Do **not** copy k9s's informer-cache-plus-poll: kweblens has no informer cache,
  adding one would duplicate `ResourceService`, and polling a cache you had to build first is
  strictly more machinery than buffering the events you already receive.
- **Coalesce into a map keyed by `namespace/name`**, exactly as `useResourceData`'s `flush()` does,
  so an ADD followed by a MODIFY followed by a DELETE inside one tick collapses to nothing rather
  than three repaints.
- **Flush on a fixed timer**, and take k9s's `inUpdate` CAS: if a repaint is still running, drop the
  tick rather than queueing it.
- **Gate it the same way.** A JUnit test that pushes a 157-event burst at the coalescer with a fake
  clock and asserts one repaint, mirroring `useResourceData.test.ts`, is the TUI's half of the
  standing rule. Without it the rule silently applies to only one of the two surfaces.

One thing kweblens has that k9s does not, and that a direct TUI would lose: `SseKeepAlive`. A
departed SSE subscriber is invisible until a failed write, so kweblens writes `:keepalive` every
15 s and closes the cluster-side watch when that write fails — measured at *22 open API-server
watches for one live subscriber* before it existed. A **direct** TUI has no network between the
watch and the renderer, so the failure mode does not arise: process exit closes the watch. A
**via-server** TUI inherits the SSE plumbing and the keep-alive with it. Neither is a reason to pick
one, but it is a thing not to reimplement by accident.

---

## 7. What k9s does that kweblens does not

Ordered roughly by how much a k9s user would miss it.

1. **Fuzzy filtering** (`-f`) over names, in lists *and* inside log/YAML/describe viewers.
2. **Marks and multi-select** (`<space>`, `ctrl-<space>`, `ctrl-\`) feeding bulk delete.
3. **Command history and a view stack you can walk** — `[`, `]`, `-`, breadcrumbs.
4. **Universal addressability**: any discovered GVR is reachable by name from the command line,
   with completion, without being in a curated catalog.
5. **`kubectl describe` output**, as a first-class view with search and auto-refresh.
6. **Node shell** and **`kubectl cp`-style transfer**.
7. **Image vulnerability scanning** (grype/syft, embedded).
8. **RBAC/subject views** — `can`, `users`, `groups`, resolved rules.
9. **Benchmarking** a port-forwarded endpoint (`hey`) with saved results.
10. **Sanitize** — bulk-delete completed/errored pods.
11. **Set image** on a workload from the UI.
12. **Local manifest browsing and apply** (`:dir`).
13. **Skins, hotkeys, custom columns, plugins** — four user-extensible config surfaces.
14. **Label selectors pushed to the API server** rather than evaluated client-side.
15. **Screen dumps** as saved artefacts.
16. **Cordon / uncordon / drain** on nodes.

## 8. What kweblens does that k9s does not

This list decides whether the TUI is parity work or a new surface, and it is longer than the epic
implies.

1. **Helm, properly.** k9s's Helm support is list, describe, values, history, rollback and uninstall
   — `internal/dao/helm_chart.go` and `helm_history.go`, and that is the whole of it. There is **no
   install, no upgrade, no repo browsing and no dry-run**. kweblens has install/upgrade/rollback
   through jhelm with a **real `dryRun` gating the Apply button**, a chart-repo index cache, and a
   values library.
2. **A diagnosis surface.** `DiagnoseService` runs deterministic checks and returns findings;
   `analyse()` is the only path that ever calls a model, is auth-gated and audited, and its cache is
   keyed by a SHA-256 of the finding list so a changed cluster is a miss rather than a stale verdict.
   k9s has no equivalent now that popeye is gone.
3. **Suggested remediation with a real preview.** Four actions, each gated on a precondition,
   `scale-up` and `rollout-restart` previewed with a genuine server-side `dryRun=All`, `restart-pod`
   and `rollback` returning `notChecked` **naming why they cannot be previewed**, and every apply
   requiring `confirm=true` and writing an audit entry. k9s's dangerous actions have a typed-phrase
   dialog and nothing else.
4. **An audit trail that survives a restart** — every entry to the `kweblens.audit` logger as well
   as an in-memory ring, values quoted and stripped of control characters so a target cannot forge a
   second line.
5. **An MCP server** — 15 read-only tools with output redacted at the boundary by `ToolRedaction`.
   k9s has no machine-facing surface at all.
6. **Global search across kinds** with a ranking function, cancellation of a superseded query, and
   an explicit `skippedKinds` report. k9s's `:` line addresses one kind at a time.
7. **A YAML editor with two diffs** — the edit against what was loaded, and live against what
   `dryRun=All` says the cluster would store, sharing `apply`'s normalisation so the preview
   describes the same request. k9s's `e` shells out to `$EDITOR` and `kubectl edit`.
8. **Prometheus-backed metrics and graphs**, with PromQL built server-side from a fixed target enum
   and names sanitised — never raw PromQL from the client. k9s shows metrics-server numbers inline
   in columns (*observed*: `CPU %CPU/R %CPU/L MEM %MEM/R %MEM/L`) and nothing historical.
9. **A pod file browser** with read/write/upload behind three separate gates.
10. **A server-side per-kind state vocabulary** — `StatusVocabulary` covers 13 kinds and attaches a
    `(label, tone)` verdict to each row, and an uncovered kind gets **no field at all** rather than a
    null. k9s's status is per-renderer text plus a colorer function, with no shared vocabulary.
11. **Server-side relational joins** — 12 relation keys, bounded at 100 items, never throwing,
    reporting `truncated` / `error` / `notPermitted` explicitly.
12. **Multi-cluster at once.** `ClusterRegistry` holds a client per registered cluster and the UI
    switches without restarting. k9s has one active context; `:ctx` switches it wholesale.
13. **In-process exec and port-forward** — no `kubectl` on `PATH` required for anything.

Read together: **a `kweblens-tui` is not parity work.** k9s's advantages are almost entirely
*interaction* — filtering, keys, marks, history, addressability. kweblens's advantages are almost
entirely *analysis and safety* — verdicts, relations, previews, audit, Helm. The TUI's value is not
"k9s on the JVM"; it is **k9s's interaction model over kweblens's analysis layer**, which is a thing
that does not currently exist. That is a much better reason to build it than parity, and it should
be the epic's framing.

---

## 9. The per-kind catalog, and the slice of GH#148 the TUI actually needs

The epic and GH#148 both quote **"71 render functions, ~25 of them computed"**. I counted, because
the brief said to.

`kweblens-ui/src/columns.ts` is **572 lines** and holds:

| | count |
|---|---|
| Kinds covered | **28** |
| Column entries across all kinds | **87** |
| Distinct `ColumnDef` literals | **81** (80 inline + one shared `serverState` reused by 7 kinds) |
| Entries with a `render:` | **87** — `render` is required by the interface, so this is not a discriminating number |
| **Computed** renders | **31** |
| Plain path reads | **49** |
| Reading a **server-computed** field (`serverState`) | **7** |

Counting method, reproducible: slice between `const COLUMNS: Record<string, ColumnDef[]> = {` and
`export function columnsFor`; `/^  ([a-z]+): \[/gm` → 28 kinds; `/\{\s*key: '/g` → 80 inline
literals; `/^\s*serverState,\s*$/gm` → 7 shared references. 80 + 7 = 87. "Computed" = the render does
anything beyond reading one path and stringifying it: ratios (5), array aggregation — count / sum /
filter / join / find (15), counts of a top-level array (2), conditionals (5), formatting or joining
(4).

So **"71 render functions" is wrong in both directions**: there are 87 entries, not 71, and the
number that matters — the ones carrying logic — is 31, not 71. The distribution is also lopsided:
**Nodes alone is 16 of the 87**. Ten of the 28 kinds have exactly one column.

`kweblens-ui/src/components/relations.ts` is **not** "a second client-side mirror", and CLAUDE.md
saying so should be corrected. It is 171 lines of *rendering* over a server-computed map; its own
header says it "projects the detail endpoint's relations (GH#136)". All 12 relations are computed in
`RelationService` in core. Its `PROJECTIONS` registry covers 3 of the 12 keys with rich row shapes;
the other 9 fall through to a generic Name/Kind/Namespace table. A TUI reimplementing it would
rewrite ~170 lines of table shaping and get all 12 joins for free.

### The slice the TUI needs

Against that inventory, "build the whole per-kind catalog first" is not what the TUI is waiting on.

**Genuinely blocking — one thing, and it is small.**
`StatusVocabulary` lives in `kweblens-core/health` and is already server-side, but the only thing
that *calls* it is `web/api/ListProjection`, which attaches `kweblensState` at the **web boundary**.
A direct TUI calling `ResourceService.listRaw` therefore gets objects with **no verdict on them** —
and the verdict is the single most valuable per-kind thing kweblens has, the thing that makes the
TUI more than a `kubectl get` with colours. The fix is to expose the state through a core-level entry
point (including the `StatusContext` the four context-carrying kinds need), leaving `ListProjection`
as one caller among two. **This is the whole of GH#148 that blocks the TUI's first useful screen.**

**Not blocking at all.**
- The **49 plain path reads** need nothing from #148. A dotted path is a dotted path; evaluating it
  in Java against a `GenericKubernetesResource` is not drift, because there is no logic to drift.
  #148's own recommendation already concedes (c)-style paths for exactly these.
- **Relations** are done. `RelationService` serves all 12 keys from core today.
- **CRD columns** are done to the same degree as the SPA: `CrdService.printerColumns` is in core and
  returns `(name, jsonPath, type)`. Only the *evaluation* is client-side, and the SPA's evaluator is
  itself a documented subset — a Java JSONPath evaluator is a smaller job than the TypeScript one.
- **YAML, events, logs, exec, port-forward, metrics, Helm** all have core services with non-web
  signatures.

**Blocking only the polished version, and worth doing incrementally.**
The **31 computed columns** are the real drift risk and the right target for #148's option (b),
server-computed values — but *per kind, in the order a TUI opens them*, not all at once. The first
five kinds a TUI user hits are pods, deployments, nodes, services and events: 32 of the 87 entries,
of which roughly a dozen are computed. That is the honest first tranche, and it is motivated by a
consumer, which is exactly the condition #148 says must hold before the work starts.

**Explicitly out of scope for the TUI.**
Column widths, ordering, `defaultHidden`, and the drawer's per-kind field/section registry
(`overview.ts`, 1,065 lines). A terminal computes its own widths from the data; and the TUI's detail
pane should be YAML + relations + events — all server-side already — not a port of `overview.ts`.

### Answering "shareable versus inherently visual"

The inherently-visual parts are not in the catalog. They are three cell renderers in
`ResourceTable.vue`, and the seam between data and rendering already exists. k9s ships a text
equivalent for each, *observed*:

| SPA rendering | k9s's text equivalent |
|---|---|
| usage bars | numeric columns: `CPU  %CPU/R  %CPU/L  MEM  %MEM/R  %MEM/L` |
| container squares | a container table with `IDX` (`M1` for the first main container), `READY`, `STATE`, `PROBES(L:R:S)` |
| status pills | one coloured word in a `STATUS` column, coloured by a per-kind `ColorerFunc` |

None of those needs new server data. They need a text renderer in the TUI, which is TUI work.

---

## 10. Direct or via the server

The epic's biggest open question. **Recommendation: direct via `kweblens-core` as the primary, with
a server transport as a later second adapter behind the same port interface.**

### Why direct

**The access layer is already shaped for it, and this is not an accident.** A grep of the whole of
`kweblens-core/src/main/java` for `org.springframework.web|jakarta.servlet|SseEmitter|WebSocket|
ResponseEntity|HttpServlet` returns **exactly one hit, and it is a javadoc word**. The streaming APIs
hand back the primitives a terminal wants:

```java
Watch    watchRaw(String clusterId, ResourceDescriptor d, String ns,
                  BiConsumer<String, GenericKubernetesResource> onEvent);
LogWatch watch(String clusterId, String ns, String pod, String container);   // + release(LogWatch)
ExecWatch exec(String clusterId, String ns, String pod, String container,
               OutputStream output, ExecListener listener);
```

`watchRaw` takes a plain `BiConsumer`. `LogWatch` is an `InputStream`. `ExecService.exec` takes a raw
`OutputStream` — the same `OutputStream` the web module bridges to a WebSocket. A TUI would hand it
the terminal instead. Going via the server means the web layer **frames** those into SSE and
WebSocket and the TUI **unframes** them again, for no gain.

**The dependency story is clean.** `kweblens-core` declares five compile dependencies — the *plain*
`spring-boot-starter` (not `-web`), fabric8, Lombok, `spring-boot-starter-cache` and Caffeine — and
`dependency:tree` confirms the closure contains no `spring-web`, no `spring-webmvc`, no Tomcat/Jetty/
Undertow and no `jakarta.servlet-api`. A `kweblens-tui` on core alone gets DI and configuration
binding and **no servlet container**. Boot it `WebApplicationType.NONE` the way `kweblens-cli`
already does.

**It is the standing rule, one module over.** "Each surface is a slice over a `kweblens-core` access
service — never cluster access reimplemented in the surface" produced every Freelens-parity surface.
A TUI over core is that rule; a TUI over HTTP is a *client of a surface*, which is a different and
weaker relationship. `RelationService`'s javadoc already names the TUI as an intended consumer of the
core service.

**It is bounded by the operator's own RBAC.** This is the argument that decides it. Under ADR-001
there is one shared trusted operator and one in-memory admin credential; that is accepted, not a gap.
But a **direct** TUI does not need that credential at all — it uses the operator's kubeconfig, so it
is bounded by that person's actual RBAC, and an audit line naming them would be meaningful. Routing
the TUI through the server would take a surface that could have per-operator bounds and give it the
single god credential instead. Saying it plainly, as the brief asks: **via-server means every TUI
user carries the one admin password, and every action they take is attributed to "admin".** Direct
avoids that for free, and it is the only kweblens surface where that is true.

**It matches what a k9s user expects.** `kweblens-tui` plus a kubeconfig, nothing else running. A
tool that requires you to first start a web server is not the tool they are asking for.

### What via-server would actually buy, honestly

- **`ListProjection`.** Real, but its job is a *network boundary*: strip `managedFields`, null out
  ConfigMap/Secret values, keep the keys. A direct TUI is a local terminal reading with the
  operator's own credentials — the same threat model as `kubectl get -o yaml`, which nobody redacts.
  What *does* transfer is the **memory** half of the argument: the TUI must use
  `listRawChunked`, not `listRaw`, for the same reason the web layer does.
- **Audit.** `AuditService` is in `web/security`. A direct TUI would not write to it. But under
  ADR-001 every entry already names "admin" and no person, so routing through the server buys a log
  line, not accountability. A direct TUI can log to the same `kweblens.audit` logger; making that
  shared is a small, separable ticket if it is wanted.
- **"Works where the operator has no kubeconfig."** This is the one genuinely good reason, and it is
  a real deployment: kweblens running in-cluster with a service account, an operator with a browser
  and no cluster credentials. That is a **second transport**, and it is worth keeping possible.

### The consequence for the design

Put the TUI's data access behind a narrow port from the first commit — something like a
`ClusterDataSource` with list / watch / get / logs / exec — and implement exactly one adapter
(`CoreClusterDataSource`, straight onto the core services) in v1. Do **not** build the HTTP adapter
now; the point of the port is that the second adapter is possible later without a rewrite, and
building both up front doubles the surface before anyone has asked for the second.

---

## 11. JVM terminal-UI libraries

The constraint set: a full-screen double-buffered grid; `SIGWINCH` re-layout; decoded function and
modifier keys; at least 256 colours; **a scrollable, selectable table**, because a k9s-like tool is
a table with a command line attached; and a way to render an interactive `exec` pane.
`kweblens-cli` uses **picocli 4.7.7** for argument parsing, which covers `--context`-style flags and
none of the above — complementary, not sufficient, exactly as the epic says.

Versions below were read from `maven-metadata.xml` on `repo1.maven.org` and GitHub's API, not from
search or memory. Items marked *[ran]* were compiled and executed on this box's OpenJDK 21.0.11
under a real pty at 120×40 with `TERM=xterm-256color`.

### Two corrections to the epic's framing

**JLine is at 4.x, not 3.x** — `org.jline:jline` **4.3.1** (2026-06-30). But the 3.x line is
maintained *in parallel*: **3.30.16** shipped 2026-07-21, i.e. **after** 4.3.1. Central's `<latest>`
reports 4.3.1 only because of version ordering. Both are live and you must pick one, because —
critically — **JLine 3 and JLine 4 use the same package names**. `org/jline/terminal/Terminal.class`
exists in both; they cannot coexist on a classpath.

**Spring Shell's TUI moved.** In 3.x it was `spring-shell-core` /
`org.springframework.shell.component.view`. In **4.0 it is in `spring-shell-jline`** under
`org.springframework.shell.jline.tui.component.view` — `spring-shell-core-4.0.3.jar` contains
**zero** `component/view` classes. Any guidance naming the old package is describing 3.4.x.

### The candidates

| | latest | date | licence | full-screen | table widget | notes |
|---|---|---|---|---|---|---|
| **JLine** | 4.3.1 / 3.30.16 | 2026-06-30 / 07-21 | BSD-3 | diff-buffered `Display` | **none** | the substrate |
| **Lanterna** | **3.1.5** | 2026-03-15 | **LGPL-3.0** | `TerminalScreen` | **yes, mature** | see the version trap below |
| **Spring Shell TUI** | 4.0.3 | 2026-06-11 | Apache-2.0 | yes, over JLine 3 | **no** | self-declared experimental |
| **TamboUI** | 0.4.0 | 2026-06-18 | MIT | `Buffer` + diff | **yes, stateful** | ratatui port; 8 months old |
| **Casciian** | 1.6.0 | 2026-07-14 | Apache-2.0 | yes | yes | JExer fork; 49★, 3 contributors |

**Lanterna has a version trap worth naming**: Central's `<latest>` *and* `<release>` both report
**3.2.0-alpha1**, whose POM is dated **2020-08-15**. It is an abandoned alpha that merely sorts
highest. **3.1.5 is current**, and there is no 3.2 or 4.0 in development.

**JLine** *[ran]*: `Terminal.Signal.WINCH` fires and reports the new size (from `ioctl(TIOCGWINSZ)`,
so nothing can spoof it); `max_colors` = 16,777,216 and `AttributedStyle.foreground(r,g,b)` emits
`ESC[38;2;…`; mouse and focus tracking present; 154 `key_*` capabilities, and **JLine 4 adds
`KeyEvent`/`KeyParser`** which 3.30.16 lacks. It selects `JniUnixSysTerminal` with bundled natives —
**no JNA and no `--enable-native-access` warning**. Note `jline-terminal-ffm` targets **Java 22** and
is therefore unusable on 21. It has **no widget layer**: `org.jline:jline-curses` exists only on the
3.x line and is gone from 4.x, with an `archive/jline-curses` branch in the repo. Dead end.

**Lanterna** *[ran]*: alternate screen, truecolor, mouse capture and a resize event all work — but
two caveats. Its size comes from an **escape-sequence round-trip** (`ESC[5001;5001H ESC[6n`), not an
ioctl, so a terminal that does not answer silently yields 80×24; and SIGWINCH is registered
**reflectively through `sun.misc.Signal` and `java.lang.reflect.Proxy`**, which works on 21 and is on
the JDK's long-term chopping block. No bracketed paste. Its `gui2` package is the richest widget set
here by a distance: a real scrollable `Table` with header renderer and cell selection, `TextBox`,
`ComboBox`, `MenuBar`, three layout managers, a window manager and a full dialog set. Zero runtime
dependencies. Releases are bursty — 3.1.1 (Jan 2021) → 3.1.2 (Feb 2024) is a three-year gap.

**Spring Shell 4's TUI** *[ran]* genuinely works — it entered the alternate screen, drew a bordered
`ListView`, a menubar, a statusbar and a modal `DialogView`, and needs **no Spring context**
(`new TerminalUI(terminal)` standalone). Its docs carry, verbatim: *"This feature is **experimental**
and is subject to breaking changes."* Boot 4 compatible (`spring-shell-starter:4.0.3` →
`spring-boot-starter:4.0.7`). **But it has no table.** `GridView` is a layout container and the
`tui/table` package is the old render-once string formatter, not an interactive grid.

**TamboUI** *[ran]*: a bordered table with a `NAME/STATUS/READY` header, three pod rows and a
highlighted selection, at 120×40, in about fifteen lines. `Table` + `TableState` with
`selectNext/Previous`, `scrollToSelected` and a per-row style resolver; `ListWidget`, `TextInput`,
`TextArea`, `Tree`, `Scrollbar`, `Tabs`, `Gauge`, `Sparkline`, `Paragraph`, `Clear`; constraint
layout via a cassowary solver. *[ran]* mouse emitted both 1003h and SGR 1006h, and `bracketedPaste`
emitted 2004h — the only candidate here measured doing bracketed paste. It also ships
`export/{html,svg,text}`, which would give a TUI screenshot harness comparable to the Playwright
scripts. It pins JLine 3.25.1, which wants force-managing forward.

### The exec pane is a solved problem, and not where you think

Casciian is the only candidate with a shell pane built in — but its PTY spawn is
`setsid script -fqe /dev/null` or `ptypipe`, and `script` is **not installed on this box**, let alone
in a slim container.

**kweblens does not need a local PTY at all.** Its exec is *remote*: fabric8 over a WebSocket to the
API server, already returning a raw `OutputStream`. What is needed is a **VT emulator to render a
remote byte stream**, and JLine ships one: `ScreenTerminal` — feed it bytes, read back a cell grid.
*[ran]* it correctly parsed `ESC[2J`, `ESC[H`, SGR 31, cursor addressing and 24-bit
`38;2;10;200;90`, preserving colour. `jline-builtins` ships `Tmux` and `WebTerminal` as working proof,
and *[ran]* it behaves identically on the 3.x line (as `org.jline.builtins.ScreenTerminal`).

### Recommendation

**Primary: TamboUI 0.4.0 for the widget layer, on the JLine 3.30.16 line, with
`org.jline.builtins.ScreenTerminal` for the exec pane.** Pin `jline-terminal` and `jline-builtins` to
3.30.16 in `dependencyManagement`, overriding TamboUI's stale 3.25.1.

The reason is narrow and it is the right reason: **a k9s-like tool is a stateful, selectable,
scrollable table**, and TamboUI is the only candidate whose primary widget is exactly that, with
measured mouse and bracketed paste, MIT-licensed, Java 8 bytecode, on a JLine line that also gives
the exec pane.

**The trade, stated plainly: it is 0.4.0, eight months old, 613 stars, 100 open issues, with APIs
its own authors declare unstable.** That is a bet. Two mitigations, both of which this repo's habits
already argue for: keep rendering behind a thin interface of our own — the same instinct as "logic in
`.ts`, rendering in `.vue`" — and settle the bet with a spike before the module exists, not after.

**Fallback: Lanterna 3.1.5**, which hands you the Table, the window manager and the full dialog set
*today* with a decade of production use and zero dependencies. Three costs, and the first is not a
footnote: **LGPL-3.0**, against `kweblens-core`/`kweblens-cli`'s Apache-2.0 and a shipped container
image — a deliberate call for the maintainer, not a detail. Then size-by-escape-round-trip, SIGWINCH
via reflective `sun.misc.Signal`, and no bracketed paste.

**Not Spring Shell**, despite being the emotionally obvious pick in a Boot codebase. Experimental,
and decisively **no table** — you would hand-build the one widget that matters inside an unstable
API, and *[ran]* confirms `TerminalUI` needs no Spring context, so "it's a Spring project" buys
nothing structural. Worth revisiting if a `TableView` lands.

**Ruled out**: JExer (moved to Codeberg, no commits since 2026-03-12, PRs closed — Casciian is its
live fork); **mordant** 3.0.2 (no full-screen layer at all — `alternateScreen` appears nowhere in the
source; its `Animation` redraws inline below the cursor); **mosaic** (needs the Kotlin Compose
compiler plugin, not consumable from Java/Maven); **text-io** and **consoleui** (abandoned; the
latter's successor `jline-console-ui` is *itself* deprecated); **jansi** (ANSI writer, no screen
model, and JLine 4 has its own natives); **JNA** (not needed — JLine uses JNI); **zircon** (renders
to Swing/libGDX); **kotter**, **tui-scala** (wrong languages). `pty4j` is the right answer if a real
*local* PTY is ever needed, which on current evidence it is not.

### What the pick does not give you

The library choice covers the screen, the keyboard and the terminal. It does **not** cover:

1. **Modal dialogs** — TamboUI has no dialog widget; you compose `Clear` + `Block` with your own
   focus trap and key routing. Lanterna and Spring Shell both hand you this free.
2. **The exec pane above the emulator** — `ScreenTerminal` turns the remote byte stream into a cell
   grid, which is the hard half. The bridge from `ExecService`'s `OutputStream`, the blit into the
   widget buffer, keystroke encoding back, and **terminal-resize propagation to the container** (a
   distinct API call — a local `TIOCSWINSZ` does not reach a pod) are all ours.
3. **A log pager** — ring buffer, follow mode, wrap, search, and the coalescing discussed in §6.
4. **Everything above the widgets** — the nav registry, the command line's parsing and completion,
   sorting, filtering, and the colour semantics for health.
5. **A contrast gate.** There is no `contrast-check.mjs` equivalent for a terminal. TamboUI's
   `export/{html,svg}` is the lever if one is wanted.
6. **A stability guarantee.** 0.4.0 means breaking changes between minors.

### Coexisting with picocli

No conflict. picocli parses `argv` and exits; the TUI owns the screen after that, so the seam is one
subcommand whose `call()` constructs the runner. Two conveniences worth checking before writing glue:
**`dev.tamboui:tamboui-picocli:0.4.0`** exists as a first-party integration, and
**`info.picocli:picocli-shell-jline3:4.7.7`** already binds picocli to JLine 3 — the line this
recommendation puts you on — if an interactive command line *inside* the TUI ever wants picocli-backed
completion.

One caveat: `kweblens-cli` is described as "dependency-light", and TamboUI + JLine is not nothing.
That is a reason for the TUI to be its own module rather than growing the CLI.

---

## 12. Confidence and gaps

Verified by running the real binary against a live cluster: the filter grammar's five branches
including the space no-op; `<esc>` semantics; breadcrumbs and the view stack; drill-down rendering as
a visible label filter; namespace favourites as an MRU; `--readonly` stripping the dangerous key set
while keeping port-forward; the hint bar being generated from the bindings; `:aliases` at 125 rows on
a plain cluster; `:xray`; `:pulses`; `:ctx`; `:can`; `:popeye` being gone; `describe` being kubectl's;
`SIGWINCH` re-layout at 30×100.

Verified by reading v0.51.0 source: the command grammar; every keybinding table; the informer +
poll + CAS refresh model and the 2 s clamp; the 50 ms log flush; the custom-column spec grammar; the
plugin, hotkey, skin, view and shell-pod config shapes; the Helm operation set; the shell-out to
`kubectl`.

**Unverified**, and marked as such rather than smoothed over:

- **Benchmarking end-to-end.** I did not start a port-forward against the live cluster, so `hey`
  integration is read-only-from-source.
- **Image vulnerability scanning.** Off by default and I did not enable it; the grype/syft
  integration is read from `go.mod` and `internal/vul/`, not exercised.
- **Node shell.** Creating a privileged pod is a write; not attempted.
- **Every destructive keystroke.** The `kind-*` contexts on this box were all down
  (`connection refused`), so the only reachable cluster was the live `k3stest`. Delete, drain,
  cordon, scale, restart, edit, exec and transfer were therefore never pressed, in any context. What
  is reported about them comes from source and from the hint bar, not from having run them.
- **Mouse support.** `ui.enableMouse` defaults to `false` (*observed* in the generated config) and I
  did not turn it on; whether tview's mouse handling is adequate for a table is unverified.
- **`ui.reactive` hot-reload.** Read from `app.go`, not exercised.

On the library side (§11), verified by compiling and running on OpenJDK 21.0.11 under a pty: JLine's
WINCH handler, truecolor output, mouse support and `ScreenTerminal`'s VT parsing on both the 3.x and
4.x lines; Lanterna's alternate screen, truecolor, mouse capture and resize event; Spring Shell 4's
`TerminalUI` running without a Spring context and drawing a list, menubar, statusbar and modal;
TamboUI's table with selection, its 1003h/1006h mouse and its 2004h bracketed paste. **Unverified**
there: TamboUI's live resize event and its 24-bit colour output (the config surface was measured, not
those two); Lanterna's behaviour against a real emulator beyond a CPR-answering harness; Casciian at
runtime (resolved and its Java 21 bytecode confirmed, but not executed); and whether Spring Shell's
TUI classes carry an `@Experimental` *annotation* — the documentation banner is confirmed, the
annotation is not.

One instrument note worth recording, since this repo has a rule about it: the first Lanterna run
reported 80×24 and was nearly written up as a defect. It was the harness not answering
cursor-position reports, not Lanterna. The positive control that caught it was `stty size` inside the
same pty returning `40 120`. Suspect the instrument before the code.

Two incidental findings from the repo side, recorded here because they came out of this work and
should not be lost:

- **`kweblens-cli` does not start.** `KweblensCliApplication` injects `picocli.CommandLine.IFactory`
  and no bean of that type exists anywhere in the repo — the module depends on plain
  `info.picocli:picocli`, not `picocli-spring-boot-starter`. Running the fat jar fails with
  *"Parameter 0 of constructor … required a bean of type 'picocli.CommandLine$IFactory' that could
  not be found"*. CI is green because `KweblensCommandTest` never boots a context; it calls
  `new CommandLine(new KweblensCommand()).execute("--help")` directly. The CLI is also the closest
  thing to a template for a TUI module's bootstrap, so this wants fixing before it is copied.
- **CLAUDE.md's description of `components/relations.ts` as "a second client-side mirror" is wrong**,
  as is the "71 render functions" figure repeated in GH#143 and GH#148. Both are corrected above.

# kweblens-ui

The React single-page app served at `/ui`. Built with Vite; bundled into
`kweblens-web` by the `frontend-maven-plugin` (so `scripts/dev-verify.sh` builds it too).

```bash
npm install       # once
npm run dev       # Vite dev server (proxies /api to :8080)
npm run build     # type-check + production bundle into dist/
```

## Extending the dashboard (data-driven)

The three "staff-like" surfaces — the left nav tree, table columns, and per-row
action menus — are each driven by a single declarative list. Adding to any of them
is **one entry**, not a spread of edits.

### Add a menu entry / list page

The left tree comes from the backend and drives both the menu **and** the resource
route. Add one line to `NavCatalog` (`kweblens-web/.../web/nav/NavCatalog.java`):

```java
ResourceDescriptor.namespaced("ingresses", "Ingresses", "Ingress", "networking.k8s.io", "v1", "ingresses")
```

`id` (route), display label, `kind`, API group/version, and the plural resource. The
nav badge counts, the list table, YAML view, and row menu all work off this — no SPA
change needed for a standard kind. (CRDs are discovered dynamically and grouped under
**Custom Resources**.)

Chain `.asExpandable()` on the descriptor to let its list rows disclose owned pods (as
the workload kinds do); the flag rides through to the SPA `NavItem` and drives the
row's tree-toggle — no per-kind list in the table code.

### Add a table column

Columns live in `src/columns.ts` — a `COLUMNS: Record<resourceId, ColumnDef[]>` map.
Add an entry to a kind's array:

```ts
deployments: [
  { key: 'ready', header: 'Ready', render: (o) => `${num(status(o).readyReplicas)}/${num(spec(o).replicas)}` },
  // add here ↓
  { key: 'strategy', header: 'Strategy', render: (o) => dash(str(spec(o).strategy?.type)) },
],
```

Name / Namespace / Age are rendered by the table framework; `COLUMNS` is just the
middle columns. `render` returns display content; add `sortText` when it returns an
element rather than a string. CRD list pages derive their columns automatically from
the CRD's `additionalPrinterColumns`.

### Add a row action (kebab menu)

Actions live in one `ROW_ACTIONS` registry in `src/App.tsx`. Add one entry:

```ts
{
  id: 'restart',
  label: 'Restart',
  section: 'main',                              // 'main' | 'lifecycle' (divider between)
  applies: (c) => RESTARTABLE.includes(c.kind), // which kinds show it
  run: (c) => c.confirmRun(() => api.restart(c.cluster, c.resourceId, c.ns, c.name), `Rolling-restart ${c.name}?`),
}
```

`RowMenu` renders whichever entries `applies({ kind, suspended })`, and the dashboard
dispatches `run(ctx)` — no `switch`, no per-kind markup. `run` gets a context with the
cluster/resource identity plus capabilities (`dialog`, `openDock`, `setForward`,
`setDetail`, `confirmRun`, `removeObject`, …). Flags: `danger` (red), `containerScoped`
(per-container submenu on multi-container pods), `requiresAuth: false` (readable
signed-out, e.g. Logs).

### Add a detail-drawer field or section

The drawer's Overview tab is driven by two registries in `src/App.tsx`:
`OVERVIEW_FIELDS` (the summary key/value rows) and `OVERVIEW_SECTIONS` (the collapsible
accordions). Both are presence-driven — a field whose `get` returns `null`, or a section
whose `applies` is false, is simply omitted, so a new kind's data appears automatically.

```ts
// a summary row
{ label: 'Cluster IP', mono: true, get: (c) => (ovSpec(c.obj).clusterIP as string) || null }

// a section
{
  title: 'Conditions',
  applies: (o) => ovArr(ovStatus(o).conditions).length > 0,
  count: (o) => ovArr(ovStatus(o).conditions).length,
  body: (c) => (/* table JSX built from c.obj */),
}
```

`ovMeta/ovSpec/ovStatus/ovArr/ovMap` are the small defensive accessors; `get`/`body`
receive `{ obj, onNavigate, onHelmRelease }`. Adding a row or section is one entry.

### Overview pages (Cluster / Workloads)

Both overview dashboards build their stat tiles from the shared `StatCard`
(`{ value, label, danger? }`). The Workloads cards come from `WORKLOAD_KINDS`, where each
entry carries its own health predicate:

```ts
{ id: 'deployments', label: 'Deployments', healthy: replicasReady }
```

So adding a workload tile is one entry — no separate health `switch` to keep in sync.

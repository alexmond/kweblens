# Freelens reference — information architecture

Reference capture of the Freelens desktop UI, to guide kweblens's web dashboard. Screenshots:

- `freelens-jobs-view.png` — a resource-list view (Workloads → Jobs), showing the shell.
- `freelens-nav-scrolled.png` — the left category nav scrolled down (Config tail → Access Control).

This is the target IA; kweblens mirrors it as a web app.

## Shell layout

```
┌──────┬────────────────────────┬────────────────────────────────────────────┐
│ clu- │  category nav          │  ┌ per-category tab bar ───────────────────┐ │
│ ster │  (collapsible groups)  │  │ Overview Pods Deployments … Jobs …       │ │
│ rail │                        │  └──────────────────────────────────────────┘ │
│ (ic- │                        │  ┌ resource list ───────────────────────────┐ │
│ ons) │                        │  │ title · N items · [ns filter][search][⚙] │ │
│      │                        │  │ ☐ Name  Namespace  Status … Age  ⋮        │ │
│      │                        │  └──────────────────────────────────────────┘ │
│      │                        │  ┌ dockable Terminal panel (tabs, ⛶, ⌄) ─────┐ │
└──────┴────────────────────────┴────────────────────────────────────────────┘
```

- **Cluster rail** (far left, icon tiles): switch between connected clusters; a current-cluster
  avatar ("DE / default") at top with a settings gear. → kweblens: drives multi-cluster (#7).
- **Category nav** (second column): collapsible groups; a resource kind can be pinned (📌) into
  Favorites. → the subject of this note and issue #12.
- **Per-category tab bar** (top of content): the kinds within the active category as tabs.
- **Resource list** (main): one reusable component for every kind (see below).
- **Terminal panel** (docked bottom): tabbed shells. → kweblens: exec / node-shell (#6).

## Left menu — complete category tree

Model this as a **declarative nav registry** (category → items; each item = label, icon, kind,
list-route, optional pin). One registry drives both the menu and the routes, so adding a kind is
one entry. Cluster-scoped kinds hide the namespace filter.

- **Favorites** — user-pinned kinds (e.g. Overview, Network Policies, Runtime Classes)
- **Cluster** (single item — cluster overview)
- **Nodes**
- **Workloads**
  - Overview · Pods · Deployments · Daemon Sets · Stateful Sets · Replica Sets ·
    Replication Controllers · Jobs · Cron Jobs
- **Config**
  - Config Maps · Secrets · Resource Quotas · Limit Ranges · Horizontal Pod Autoscalers ·
    Vertical Pod Autoscalers · Pod Disruption Budgets · Priority Classes · Runtime Classes ·
    Leases · Mutating Webhook Configs · Validating Webhook Configs ·
    Validating Admission Policies · Validating Admission Policy Bindings
- **Network**
  - Services · Endpoint Slices · Endpoints · Ingresses · Ingress Classes · Network Policies ·
    Port Forwarding
- **Storage**
  - Persistent Volume Claims · Persistent Volumes · Storage Classes
- **Namespaces** (single item)
- **Events** (single item) → #4
- **Helm**
  - Charts · Releases → built on jhelm (#1)
- **Access Control**
  - Service Accounts · Cluster Roles · Roles · Cluster Role Bindings · Role Bindings
- **Custom Resources** — **dynamic, discovered from the cluster's CRDs**
  - **Definitions** (the CRD list itself)
  - then one expandable sub-group **per API group**, each listing that group's kinds. Observed in
    the capture: `acme.cert-manager.io`, `autoscaling.k8s.io`, `cert-manager.io`,
    `externaldns.k8s.io`, `gateway.networking.k8s.io`, `helm.cattle.io`, `hub.traefik.io`,
    `k3s.cattle.io`, `metallb.io`, `operator.victoriametrics.com`, `traefik.io`, …
  - This part of the nav is **not static**: build it by listing CRDs from the cluster and grouping
    by `spec.group`. The static registry ends at Access Control; Custom Resources is generated.

## Reusable resource-list component

Every kind renders through one component:

- Header: **title**, **item count**, **namespace filter** (hidden for cluster-scoped kinds),
  **search**, **column-visibility toggle**.
- Table: multi-select **checkbox** column, sortable columns, **status/phase pills** (green
  "Complete"/"True"), **namespace as a link**, per-row **⋮ actions** menu.
- Columns vary by kind (Jobs: Name, Namespace, Resumed, Status, Succeeded, Completions,
  Parallelism, Duration, Age) — declare per kind in the nav registry.

## Mapping to issues

| Freelens element | kweblens issue |
|---|---|
| Left category nav + shell + resource-list component | #12 (this) |
| Cluster rail (multi-cluster switch) | #7 |
| Terminal panel | #6 |
| Helm (Charts/Releases) | #1 |
| Events | #4 |
| Live metrics on Nodes/Pods | #5 |
| YAML on a resource detail | #3 |

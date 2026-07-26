# Freelens → kweblens: page descriptions & gap analysis

Reference review of all 57 Freelens cluster views (captured headlessly), each described
and compared against kweblens's current capabilities. Structure/columns only — no lab
data values. The consolidated gap analysis is at the bottom.

Baseline — kweblens today: one **generic table (Kind / Namespace / Name / Status / Age)**
for every kind; namespace filter; live watch; row → drawer (Overview = those 5 fields +
read-only YAML/copy); write actions (scale / restart / cordon / delete); separate Helm
releases, Metrics (numbers), AI Diagnose, pod Logs, Exec, Audit — not in the Navigator.

---

## Consolidated gap analysis (prioritized)

The single biggest finding: **kweblens shows one generic 5-column table for everything,
while Freelens tailors columns and detail to each kind.** Roughly two-thirds of the value
in Freelens's views lives in data kweblens cannot currently surface.

**Tier 1 — foundational data model (unlocks most of the parity)**
1. **Per-kind column registry.** Replace the fixed Kind/Name/Namespace/Status/Age table
   with per-kind column sets: Pods (Containers/Restarts/CPU/Mem/Node/Controlled-By),
   Deployments/DaemonSets/StatefulSets/ReplicaSets (Ready/Desired/Current/Updated/Available/
   Conditions), Jobs (Completions/Parallelism/Duration), CronJobs (Schedule/Active/Last),
   Nodes (CPU/Mem/Roles/Taints/Version/Internal-IP/Conditions), Services (Type/ClusterIP/
   Ports), Ingresses (LoadBalancers/Rules), PVC/PV/StorageClass (storage class/size/
   capacity/claim/provisioner/reclaim/default), Secrets (Type/Keys), Events (Type/Message/
   Involved-Object/Source/Count/Last-Seen), RBAC bindings (Role/Subjects). Drop the Kind
   column (redundant per-view) and the meaningless Status column on kinds that lack one.
2. **CRD `additionalPrinterColumns`.** Custom-resource lists should render the columns the
   CRD declares (what `kubectl get <cr>` shows) — currently all CRs collapse to the generic 5.
3. **Rich per-object detail.** Expand the drawer beyond 5 fields + YAML to labels,
   annotations, conditions, containers, owner/related resources, and per-object events.
4. **Scope-aware tables.** Drop the Namespace column + namespace filter for cluster-scoped
   kinds (currently shown empty/irrelevant).

**Tier 2 — missing kinds & surfaces (~20 kinds absent)**
5. **Add missing kinds to the nav:** ReplicationControllers; ResourceQuotas, LimitRanges,
   HPA, VPA, PodDisruptionBudgets, PriorityClasses, RuntimeClasses, Leases, Mutating/
   ValidatingWebhookConfigurations, ValidatingAdmissionPolicies (+Bindings); EndpointSlices,
   Endpoints, IngressClasses, NetworkPolicies; a **CRD Definitions** page
   (Resource/Group/Version/Short-Names/Scope + "All groups" filter).
6. **Overview dashboards:** a Cluster overview (metrics graphs w/ Master-Worker + time-range,
   cluster-wide Warnings roll-up) and a Workloads overview (per-kind status donuts + counts +
   embedded events).
7. **Metrics graphs:** in-list CPU/Memory usage bars (Nodes/Pods) and panel graphs (needs a
   Prometheus source) — kweblens has numbers only.

**Tier 3 — interactive features & UX parity**
8. **Helm Charts browser** + install/upgrade/rollback (kweblens has deployed releases only);
   put Helm in the nav with Charts|Releases tabs and add release columns (Chart/Revision/
   Version/App-Version/Updated).
9. **Port forwarding** (interactive pod/service → local port) — absent entirely.
10. **Terminal dock** (persistent, multi-tab) — kweblens Exec is per-pod only.
11. **Create/edit + bulk actions:** create (+) buttons, row checkboxes for bulk ops,
    per-row ⋮ action menus.
12. **List UX:** clickable cross-references (namespace, owner/Controlled-By, involved object,
    role, storage class, claim, ingress rules), per-view search, sort controls, column-
    visibility toggle, item counts, secret value-masking toggle.
13. **Navigation IA:** category tab-bars (Workloads/Config/Network/Storage/Access as tabbed
    sub-surfaces), Favorites/pinning, and the Definitions anchor in the Custom Resources section.

Note on effort leverage: **Tier 1.1 (per-kind columns) + 1.2 (CRD printer columns) are the
highest-leverage items** — they turn ~40 of the 57 views from "name + age only" into useful
lists, and everything downstream (detail, cross-links) builds on the same per-kind model.

---

## A. Cluster, Nodes & Workloads (01–12)

### 01 Favorites
- **Freelens shows:** Pinned "Favorites" group atop the Navigator; main area = Cluster overview: two metrics panels (Master/Worker toggle, "1 hour" time-range dropdown, CPU/Memory toggle; Prometheus-not-configured placeholder), a **Warnings: N** table (Message, Object, Type, Age), and a bottom multi-tab **Terminal** dock (+new, expand/collapse).
- **kweblens:** MISSING — no Favorites/pinning, no landing board, no cluster metrics graph panels (metrics are numbers-only), no cluster-wide Warnings roll-up, no terminal dock (exec is per-pod).

### 02 Cluster overview
- **Freelens shows:** Same layout — two metric panels (Master/Worker, time-range, CPU/Memory) + **Warnings** table (Message/Object/Type/Age, sortable) + terminal dock.
- **kweblens:** MISSING — no cluster overview/dashboard at all; no metrics graphs, time-range selector, Master/Worker split, or Warnings roll-up.

### 03 Nodes
- **Freelens shows:** Nodes list. Toolbar: item count, Search box, column-visibility toggle. Columns: **Name, CPU (usage bar), Memory (usage bar), Disk, Roles, Taints, Version, Internal IP, Age, Schedulable, Conditions**; per-row ⋮ menu. CPU/Mem as sparkbars.
- **kweblens:** PARTIAL — has Nodes + cordon/uncordon, but generic 5 columns only. LACKS CPU/Memory usage bars, Disk, Roles, Taints, Version, Internal IP, Schedulable, Conditions; no inline row menu, no column toggle.

### 04 Workloads Overview
- **Freelens shows:** Workloads tab-bar (Overview, Pods, Deployments, Daemon Sets, Stateful Sets, Replica Sets, Replication Controllers, Jobs, Cron Jobs). Overview: All-namespaces filter; row of per-kind status **donut charts** with counts + Running/Succeeded legend; embedded **Events** panel (Type, Message, Namespace, Involved Object, Source, Count, Age, Last Seen) + per-row ⋮.
- **kweblens:** MISSING — no Workloads Overview, no status donuts/aggregate counts, no workload tab-bar, no richly-columned embedded events panel.

### 05 Pods
- **Freelens shows:** Pods list. Toolbar: count, All-namespaces, Search, column toggle. Columns: select-all + row checkboxes, **Name**, warning-icon, **Namespace (link), Containers (per-container status squares), CPU, Memory, Restarts, Controlled By (owner link), Age, Status**; logs/quick-action icon + per-row ⋮.
- **kweblens:** PARTIAL — Pods + detail drawer + Logs/Exec exist, but list is generic 5 columns. LACKS bulk-select, warning indicator, per-container status squares, live CPU/Memory, Restarts, "Controlled By" owner link, inline actions.

### 06 Deployments
- **Freelens shows:** Columns: select, **Name, Namespace (link), Ready, Desired, Updated, Available, Age, Conditions** (Available/Progressing pills); per-row ⋮.
- **kweblens:** PARTIAL — has Deployments + scale/restart. LACKS Ready/Desired/Updated/Available columns and Conditions pills; no bulk-select.

### 07 Daemon Sets
- **Freelens shows:** Columns: select, **Name, Namespace (link), Desired, Current, Ready, Updated, Available, Node Selector (chips), Age**; per-row ⋮.
- **kweblens:** PARTIAL — LACKS Desired/Current/Ready/Updated/Available and Node Selector.

### 08 Stateful Sets
- **Freelens shows:** Columns: select, **Name, Namespace (link), Ready, Desired, Age**; per-row ⋮.
- **kweblens:** PARTIAL — LACKS Ready/Desired.

### 09 Replica Sets
- **Freelens shows:** Columns: select, **Name, Namespace (link), Desired, Current, Ready, Age**; per-row ⋮.
- **kweblens:** PARTIAL — LACKS Desired/Current/Ready.

### 10 Replication Controllers
- **Freelens shows:** (empty list). Headers: select, **Name, Namespace, Desired, Current, Ready, Selector**.
- **kweblens:** MISSING — kind not in kweblens's Navigator; would also lack Desired/Current/Ready/Selector.

### 11 Jobs
- **Freelens shows:** Columns: select, **Name, Namespace (link), Resumed, Status (Complete pill), Succeeded, Completions, Parallelism, Duration, Age**; per-row ⋮.
- **kweblens:** PARTIAL — LACKS Resumed/Succeeded/Completions/Parallelism/Duration + status pill.

### 12 Cron Jobs
- **Freelens shows:** (empty list). Headers: select, **Name, Namespace, Schedule, Timezone, Resumed, Active, Last schedule, Age**.
- **kweblens:** PARTIAL — LACKS Schedule/Timezone/Resumed/Active/Last schedule.

**Batch A gaps:** no Cluster/Workloads overview dashboards (metrics graphs, status donuts, warnings); generic 5-column table vs kind-specific columns everywhere (replica counts, conditions, node selector, job/cronjob fields, node CPU/Mem/Roles/Taints/Version/IP); no in-list resource-usage columns; missing list UX (bulk-select, per-row ⋮ menus, warning column, column-visibility toggle, owner/namespace links); no Favorites/pinning; no terminal dock; Replication Controllers kind absent.

## B. Config (13–26)

- **13 Config Maps** — Freelens: **Name / Namespace / Keys / Age** + Config tab-bar, mask toggle, ⋮ menus. kweblens PARTIAL: has kind, lacks **Keys** column, tab-bar, menus.
- **14 Secrets** — Freelens: **Name / Namespace / Keys / Type / Age** + create (+), mask toggle. kweblens PARTIAL: lacks **Keys** and **Type** (Opaque/tls/sa-token…) — can't distinguish secret types.
- **15 Resource Quotas** — Freelens: **Name / Namespace / Age** + create (+). kweblens **MISSING** (kind not in nav).
- **16 Limit Ranges** — Freelens: **Name / Namespace / Age**. kweblens **MISSING** (kind not in nav).
- **17 Horizontal Pod Autoscalers** — Freelens: **Name / Namespace / Metrics / Min Pods / Max Pods / Replicas / Age / Conditions**. kweblens **MISSING** (kind + all columns).
- **18 Vertical Pod Autoscalers** — Freelens: **Name / Namespace / Mode / Age / Conditions**. kweblens MISSING as a Config view (only reachable as a raw CRD under autoscaling.k8s.io; lacks Mode/Conditions).
- **19 Pod Disruption Budgets** — Freelens: **Name / Namespace / Min Available / Max Unavailable / Current Healthy / Desired Healthy / Age**. kweblens **MISSING**.
- **20 Priority Classes** — Freelens (cluster-scoped): **Name / Value / Global Default / Age**. kweblens **MISSING**.
- **21 Runtime Classes** — Freelens (cluster-scoped): **Name / Handler / Age**. kweblens **MISSING**.
- **22 Leases** — Freelens: **Name / Namespace / Holder / Age**. kweblens **MISSING** (lacks Holder — key for leader-election debugging).
- **23 Mutating Webhook Configs** — Freelens (cluster-scoped): **Name / Webhooks / Age**. kweblens **MISSING**.
- **24 Validating Webhook Configs** — Freelens (cluster-scoped): **Name / Webhooks / Age**. kweblens **MISSING**.
- **25 Validating Admission Policies** — Freelens (cluster-scoped): **Name / Validations / Age**. kweblens **MISSING**.
- **26 Validating Admission Policy Bindings** — Freelens (cluster-scoped): **Name / Policy / Actions / Age**. kweblens **MISSING**.

**Batch B gaps:** kweblens's Config category has only **2 of 14** kinds (ConfigMaps, Secrets); missing ResourceQuotas, LimitRanges, HPA, VPA, PDB, PriorityClasses, RuntimeClasses, Leases, 2× WebhookConfigs, 2× AdmissionPolicy. No admission-control/policy governance surface. No autoscaling visibility. Even present kinds lack discriminating columns (Secrets Type/Keys). Missing: Config tab-bar, value-masking toggle, create (+), ⋮ menus, namespace links.

## C. Network, Storage, Namespaces & Events (27–38)

- **27 Services** — Freelens: **Name / Namespace / Type / Cluster IP / External IP / Ports / Age / Status** + Network tab-bar. kweblens PARTIAL: lacks **Type / Cluster IP / External IP / Ports**.
- **28 Endpoint Slices** — Freelens: **Name / Namespace / Address Type / Ports / Endpoints / Age**. kweblens **MISSING** (kind absent).
- **29 Endpoints** — Freelens: **Name / Namespace / Endpoints / Age**. kweblens **MISSING** (kind absent).
- **30 Ingresses** — Freelens: **Name / Namespace / LoadBalancers / Rules / Age**. kweblens PARTIAL: lacks **LoadBalancers / Rules** (host/path routing).
- **31 Ingress Classes** — Freelens: **Name / Controller / API Group / Scope / Kind / Age** (+ default-star). kweblens **MISSING** (kind absent).
- **32 Network Policies** — Freelens: **Name / Namespace / Policy Types / Age**. kweblens **MISSING** (kind absent).
- **33 Port Forwarding** — Freelens: **Name / Namespace / Kind / Pod Port / Local Port / Protocol / Address / Status** — an interactive forward feature. kweblens **MISSING** (feature absent entirely).
- **34 Persistent Volume Claims** — Freelens: **Name / Namespace / Storage class / Size / Pods / Age / Status**. kweblens PARTIAL: lacks **Storage class / Size / Pods**.
- **35 Persistent Volumes** — Freelens (cluster-scoped): **Name / Storage Class / Capacity / Claim / Age / Status**. kweblens PARTIAL: lacks **Storage Class / Capacity / Claim**.
- **36 Storage Classes** — Freelens (cluster-scoped): **Name / Provisioner / Reclaim Policy / Default / Age**. kweblens PARTIAL: lacks **Provisioner / Reclaim Policy / Default**.
- **37 Namespaces** — Freelens: **Name / Labels / Age / Status** + create (+). kweblens PARTIAL: lacks **Labels** chips, create (+).
- **38 Events** — Freelens: **Type / Message / Namespace / Involved Object / Source / Count / Age / Last Seen**. kweblens PARTIAL (worst fit): lacks **Type / Message / Involved Object / Source / Count / Last Seen** — nearly all of an event's data.

**Batch C gaps:** 4 Network kinds absent (EndpointSlices, Endpoints, IngressClasses, NetworkPolicies); **Port Forwarding** feature absent; kind-specific columns missing everywhere (Service networking, Ingress rules, PVC/PV/StorageClass storage fields, Namespace labels); **Events** is the worst generic-table fit; no clickable cross-references (namespace, storage class, claim, involved object, ingress rules); no bulk-select/⋮/create.

## D. Helm & Access Control (39–45)

- **39 Helm Charts** — Freelens: a **chart browser** — **Name / Description / Version / App Version / Repository** + repo search; rows are installable. kweblens **MISSING** entirely (no charts browser, no repo search, no install/upgrade UI; Helm not in nav).
- **40 Helm Releases** — Freelens: **Name / Namespace / Chart / Revision / Version / App Version / Status / Updated** (Charts|Releases tabs). kweblens PARTIAL: has releases+history (jhelm) but not in nav; lacks **Chart / Revision / Version / App Version / Updated** columns and upgrade/rollback UI.
- **41 Service Accounts** — Freelens: **Name / Namespace / Age** + Access-Control tab-bar, create (+). kweblens PARTIAL: columns roughly match; lacks create (+), tabbed grouping; shows irrelevant Status.
- **42 Cluster Roles** — Freelens (cluster-scoped): **Name / Age**. kweblens PARTIAL: shows empty Namespace + meaningless Status; no create (+).
- **43 Roles** — Freelens: **Name / Namespace / Age**. kweblens PARTIAL: as above.
- **44 Cluster Role Bindings** — Freelens: **Name / Cluster Role / Types / Bindings / Age**. kweblens **MISSING (data)**: generic table has no **Cluster Role / Types / Bindings** — can't show what/whom is bound.
- **45 Role Bindings** — Freelens: **Name / Namespace / Role / Types / Bindings / Age**. kweblens **MISSING (data)**: lacks **Role / Types / Bindings**.

**Batch D gaps:** no Helm **charts browser** / install-upgrade (only deployed releases); Helm Releases missing kind-specific columns; **RoleBinding/ClusterRoleBinding data unrepresentable** in the 5-column table (bound role + subject types/names absent); no tabbed Access-Control grouping; generic table doesn't adapt to scope (empty Namespace + meaningless Status on cluster-scoped/RBAC kinds); no create/multi-select/cross-links.

## E. Custom Resources (46–57)

> Capture note: clicking a custom-resource **group** in Freelens expands that group in the
> Navigator rather than navigating the main pane, so shots 47–57 all show the same
> **Definitions** table with the nav progressively expanded. Descriptions below cover the
> Definitions page (46) and what each group's per-kind page would show.

- **46 Custom Resource Definitions** — Freelens: a dedicated **Definitions** list — **Resource (Kind) / Group / Version / Short Names / Scope / Age** + an "All groups" filter and search; checkboxes + ⋮. kweblens **MISSING** — no CRD Definitions page at all; none of these columns (Group/Version/Short Names/Scope) exist anywhere.
- **47–57 Per-group pages** (acme.cert-manager.io, autoscaling.k8s.io, cert-manager.io, externaldns.k8s.io, gateway.networking.k8s.io, helm.cattle.io, hub.traefik.io, k3s.cattle.io, metallb.io, operator.victoriametrics.com, traefik.io) — Freelens renders each kind's **CRD-declared `additionalPrinterColumns`** (operator-specific columns, e.g. Certificate readiness, Gateway address/programmed, HTTPRoute hostnames, MetalLB pools, VictoriaMetrics status) on top of Name/Namespace/Age. kweblens **PARTIAL** — the dynamic per-group nav categories and kind lists match Freelens's structure, but every custom resource opens the same generic **Kind/Namespace/Name/Status/Age** table with **no `additionalPrinterColumns`**, and there's no Definitions anchor/filter.

**Batch E gaps:** no CRD **Definitions** page (Group/Version/Short Names/Scope); no CRD **`additionalPrinterColumns`** (the whole value of an operator's CR list); no per-CRD metadata (served version maturity, short names, scope); no Definitions anchor + "All groups" filter in the CR nav section.

## F. Resource detail drawer — accordions & actions (audit 2026-07)

Most of A–E (per-kind list columns, overviews, Helm, port-forward, terminal, list UX) has
since shipped. This section audits the **detail drawer** itself — Freelens's collapsible
accordion sections and per-kind actions vs kweblens today.

**kweblens detail drawer today:** tabs Overview · YAML · Events · Metrics. Overview =
Kind/Namespace/Name/Status/Created, Managed By (Helm), Controlled By, Labels, Annotations,
Containers (name/image/ready/restarts), Conditions — all flat (not collapsible). Actions:
Scale, Restart, Logs, Terminal, Forward, Cordon/Uncordon, Delete, Force Delete, Edit-YAML.

### F.1 Accordion behaviour
- **Freelens:** one scroll of **collapsible** sections; kweblens's Overview sections are flat.
- **Missing kind-specific sections:**
  - **Pod** — Init Containers; per-container detail (image, command/args, ports, env,
    resource requests/limits, liveness/readiness probes, volume mounts, state); Pod IP /
    Host IP / QoS class; Tolerations; Node Selector; Affinities; Volumes.
  - **Node** — Info (OS, kernel, container runtime, kubelet version); Capacity/Allocatable;
    Taints; Addresses; Pods scheduled here.
  - **Deployment/StatefulSet/ReplicaSet** — Selector, Strategy, replica breakdown.
  - **Service** — Selector, Ports, Endpoints (backing pods).
  - **Secret** — per-key reveal/decode (only base64-in-YAML today).
  - **ConfigMap** — Data keys; **Ingress** — Rules.

### F.2 Missing actions
| Action | Kinds | Backend |
|---|---|---|
| Drain | Node | yes (cordon + evict) |
| Trigger (run now) | CronJob | yes (Job from template) |
| Suspend / Resume | CronJob, Job | yes (patch spec.suspend) |
| Rollback (revision) | Deployment/STS | yes |
| Attach | Pod | yes (WS, like exec) |
| Reveal/decode | Secret | no (client-side) |

Already covered: Scale, Restart, Logs, Exec/Terminal, Port-forward, Cordon/Uncordon,
Delete, Force-Delete, Edit-YAML.

**Build order:** (1) collapsible accordions + kind sections (Pod/Node/Service/Secret) —
**done**; (2) Drain, CronJob Trigger + Suspend/Resume, Job Suspend/Resume — **done**;
(3) Rollback, Attach — pending.

**Shipped (1):** Overview sections are now collapsible `Accordion`s with count badges.
Header KV gained kind-specific rows (Pod: Node/Pod IP/Host IP/QoS; Service: Type/Cluster IP;
Node: Internal IP/Kubelet; Secret: Type). New per-kind sections: Containers (now with
Ports + resource requests), Service Ports, Selector, Ingress Rules + TLS, ConfigMap Data,
Secret Data (masked, per-key Reveal/decode), Pod Node Selector/Tolerations/Volumes, Node
Info/Capacity+Allocatable/Taints, Conditions.

**Shipped (2):** `ResourceService.setSuspended` / `triggerCronJob` / `drainNode`; web routes
`POST …/{ns}/{name}/suspend|trigger` and `…/nodes/{name}/drain`; SPA drawer buttons
(CronJob Trigger + Suspend/Resume, Job Suspend/Resume, Node Drain), all auth-gated + audited.

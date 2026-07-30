# Failure taxonomy: what breaks, and where the evidence is

Issue: GH#142. Date: 2026-07-30.

This exists to decide **what the agent's tools must be able to fetch**, before any prompt is
written. A diagnosis is only as good as the evidence the tool surface can reach, and the
recurring mistake is to expose "list pods" and expect a model to infer the rest.

Every path below was **walked against a live cluster**, using the `kwfx-*` fixture
namespaces, and the quoted fields are what those objects actually contained — not what the
API reference says they should. Two of them are not where you would guess.

## The two findings that change the tool list

**1. A pod's current state usually does not say why it is broken. Its *previous* state does.**

`fx-crashloop`'s container status reads:

```
state    : waiting → "back-off 5m0s restarting failed container=crasher …"
lastState: terminated → exitCode 137, reason "Error"
restarts : 52
```

"CrashLoopBackOff" is a *symptom name*, and it is all the current state carries. The
diagnosis — exit code, OOMKill, when it last ran — is in `lastState.terminated`, plus the
previous container's log. A tool that returns only a summary status hides exactly the field
that matters, so the surface needs the **raw object** and **`--previous` logs**.

**2. Some failures have no evidence on the object at all — only in events.**

`fx-pvc-pending`'s PersistentVolumeClaim says `Pending` and nothing else. The actual cause
is an event:

```
Warning  ProvisioningFailed  (x922)  persistentvolume-controller
         storageclass.storage.k8s.io "kweblens-fixture-no-such-class" not found
```

No amount of reading the PVC produces that sentence. **Events scoped to one object** are
therefore not a nice-to-have; for a whole class of failures they are the only evidence.

## The taxonomy

| Failure | Symptom | Where the evidence actually is | Tool needed |
|---|---|---|---|
| **CrashLoopBackOff** | pod restarting | `status.containerStatuses[].lastState.terminated` — `exitCode` (137 = SIGKILL/OOM, 1 = app error), `reason`; **plus the previous container's log** | describe + logs(previous) |
| **OOMKilled** | restarts, exit 137 | `lastState.terminated.reason == "OOMKilled"`; compare `spec.containers[].resources.limits.memory` with observed usage | describe + metrics |
| **ImagePullBackOff** | pod not starting | `state.waiting.message` — carries the registry, tag and underlying error (`ErrImagePull: failed to pull and unpack …`) | describe |
| **Pending — unschedulable** | pod never scheduled | `status.conditions[type=PodScheduled].message` — the scheduler's own verdict, e.g. `0/4 nodes are available: 4 node(s) didn't match Pod's node affinity/selector` | describe |
| **Pending — PVC unbound** | pod never scheduled | the pod says little; the **PVC's events** carry the cause (missing StorageClass, no provisioner) | describe + events(PVC) + storage check |
| **Init container failure** | `Init:Error`, pod Pending | `status.initContainerStatuses[].{state,lastState}` — the *app* containers only say `PodInitializing`, which is misleading | describe + logs(init container, previous) |
| **Readiness probe failing** | pod Running, never Ready | `conditions[type=Ready]` false while `ContainersReady` false; probe failures appear as **events**; the app log usually explains it | describe + events + logs |
| **Service with no ready endpoints** | DNS resolves, nothing answers | the Service object looks perfect; the answer is the **Endpoints** object — `subsets[].addresses` vs `notReadyAddresses` distinguishes "selector matches nothing" from "matched but not ready" | network check + describe |
| **Job backoff limit exceeded** | job never completes | `status.conditions[type=Failed]`, `status.failed`; the **pod** logs say why | workload health + logs |
| **CronJob not running** | nothing happens | `spec.suspend`, `status.lastScheduleTime`; a failed run surfaces as a failed **Job**, not on the CronJob | workload health |
| **Replica shortfall** | fewer ready than desired | `status.readyReplicas` vs `spec.replicas`; the *reason* is in the pods, not the Deployment | workload health + describe(pod) |
| **Resource quota exhaustion** | creates rejected | rejection is an **event** on the controller (ReplicaSet), naming the quota | events |
| **Admission webhook rejection** | apply/create fails | the API error message names the webhook; on controller-driven creates it is an **event** | events |
| **HPA cannot scale** | no scaling | `status.conditions` on the HPA (`ScalingActive`, `AbleToScale`) — commonly "missing request for cpu" or no metrics source | describe + metrics |
| **Unreferenced / missing config** | container fails to start | `state.waiting.reason` is `CreateContainerConfigError`; the message names the missing ConfigMap/Secret key | describe + config check |
| **Helm release failed** | release in a bad state | release status and history | Helm status |

## What the tool surface must therefore provide

Derived from the right-hand column, nothing speculative:

1. **`describeResource`** — the **raw** object of any kind. Almost every row needs a field a
   summary projection drops. This is the single most important tool and the reason a
   "list"-only surface cannot diagnose.
2. **`getEvents`** — cluster- or namespace-wide, **and scoped to one object**. The only
   evidence for PVC provisioning, quota, webhook and probe failures.
3. **`getPodLogs`** — with `container`, `tailLines` and **`previous`**. Without `previous`,
   crashloops are undiagnosable: the current log starts at the new process.
4. **`listResources`** — objects of any kind, so the agent can navigate from a workload to
   its pods, its ReplicaSets, its PVCs.
5. **`checkHealth`** — the server-side, reason-carrying checks (workloads, network, storage,
   config). These already encode the joins for replica shortfall, endpoints and unbound
   claims, so the agent does not reimplement them and cannot disagree with the dashboard.
6. **Metrics** — for OOM and HPA questions.
7. **Helm status** — release state and history.

## Constraints that apply to all of them

- **Secret values must never appear in tool output.** A raw `describeResource` on a Secret
  would otherwise hand every credential in the cluster to a model and to whatever logs its
  traffic. Redaction belongs at the tool boundary, because the tool surface is the thing
  with the different rule — the SPA deliberately shows an operator their own Secrets.
- **The surface is read-only.** Mutating tools stay off; remediation keeps its
  suggest → dry-run/diff → explicit approve → apply → audit path. Per ADR-001 this is
  unchanged by the single-operator decision: "one trusted operator" is a statement about
  people, not about autonomous processes.
- **It inherits the single-identity model.** An agent can read anything kweblens can, which
  under ADR-001 is everything. That is accepted and disclosed, not solved here.
- **Bounded output.** Logs and lists are capped, and truncation is stated in the result
  rather than silently applied — a model told "these are all the pods" will reason as though
  they are.

// ---- Detail drawer Overview: declarative field + section registries ----
// Adding a summary row or a whole section is one entry below; Overview.vue just maps
// over them. Rows/sections are presence-driven — a `get` that returns null (or an
// `applies` that returns false) simply omits it, so a new kind's data shows up
// automatically.
//
// Because Vue has no JSX, each `get`/`body` returns a *descriptor* (a plain data
// value) that Overview.vue's template renders — the registry itself stays pure data,
// exactly as the React `OVERVIEW_FIELDS` / `OVERVIEW_SECTIONS` did.
import { age } from '../columns';
import { objSpec, objStatus } from '../kube';
import type { KubeObject } from '../types';

const ovMeta = (o: KubeObject): NonNullable<KubeObject['metadata']> => o.metadata ?? {};
const ovSpec = objSpec;
const ovStatus = objStatus;
const ovArr = (v: unknown): Record<string, unknown>[] => (Array.isArray(v) ? (v as Record<string, unknown>[]) : []);
const ovMap = (v: unknown): Record<string, string> => (v && typeof v === 'object' ? (v as Record<string, string>) : {});
/** Same, for the data maps whose values may be `null` when the row came from a list (GH#276). */
const ovDataMap = (v: unknown): Record<string, string | null> =>
  v && typeof v === 'object' ? (v as Record<string, string | null>) : {};

/**
 * The POD SPEC behind an object — its own for a Pod, otherwise the pod template it creates.
 *
 * Load-bearing. Every container/volume/scheduling section used to read `spec.*` directly,
 * which is only correct for a Pod: a Deployment's containers live at
 * `spec.template.spec.containers`, so those sections silently found nothing and the detail
 * drawer was effectively Pod-and-Node-only. Resolving the template here makes the existing
 * sections light up for Deployments, StatefulSets, DaemonSets, ReplicaSets, Jobs and
 * CronJobs without duplicating a single one of them.
 *
 * CronJob nests one level deeper (jobTemplate → template), which is why this is a lookup
 * rather than a single optional chain.
 */
function ovPodSpec(o: KubeObject): Record<string, unknown> {
  const spec = ovSpec(o);
  const nested = (v: unknown): Record<string, unknown> | null =>
    v && typeof v === 'object' ? (v as Record<string, unknown>) : null;
  // CronJob: spec.jobTemplate.spec.template.spec
  const jobTemplate = nested(spec.jobTemplate);
  if (jobTemplate) {
    const jobSpec = nested(jobTemplate.spec);
    const inner = jobSpec && nested(jobSpec.template);
    const innerSpec = inner && nested(inner.spec);
    if (innerSpec) {
      return innerSpec;
    }
  }
  // Deployment / StatefulSet / DaemonSet / ReplicaSet / Job: spec.template.spec
  const template = nested(spec.template);
  const templateSpec = template && nested(template.spec);
  return templateSpec ?? spec;
}

// ---- Field descriptors (a key/value row's rendered value) ----
/** What a summary row renders. Exported because OverviewField.vue is the one that renders it. */
export type OvValue =
  | { kind: 'text'; text: string }
  | { kind: 'nav'; text: string; navKind: string; navNs?: string }
  | { kind: 'helm'; rel: string; rns: string }
  | { kind: 'owners'; owners: { kind: string; name: string; ns?: string }[] };

/**
 * Whether a row or section is the object's own SUBSTANCE or bookkeeping ABOUT it (#231).
 *
 * - `primary` — what the object is and does: its spec, ports, selector, containers, status,
 *   and the relation tables. This is what a reader opened the drawer for.
 * - `secondary` — provenance: labels, annotations, when it was created, what manages it.
 *   True of every object, rarely the question being asked.
 *
 * It lives here, next to the registries, rather than in the template, so a section carries
 * its own answer and a consumer (#232 moves the secondary set into a sidebar when the pane
 * is wide) does not re-derive it from titles. Everything is primary unless it says
 * otherwise — the safe default, since a mis-defaulted section is merely in the main column.
 * Read it through {@link rankOf}, never off `.rank`, so the default lives in one place.
 */
export type SectionRank = 'primary' | 'secondary';

/** The rank of a field, section or relation section. Unmarked means `primary`. */
export function rankOf(entry: { rank?: SectionRank }): SectionRank {
  return entry.rank ?? 'primary';
}

/**
 * The two columns of the wide drawer (#232): `main` keeps the object's substance, `aside`
 * takes provenance and bookkeeping.
 *
 * One generic split for summary rows, sections and relation sections alike, because all
 * three answer {@link rankOf} — which is the point of the rank living on the registry entry.
 * `entry` is how a caller points at the rank-bearing value when the list it holds wraps it
 * (`{ field, value }` pairs), so nothing has to be re-derived from a section title.
 *
 * Order is preserved within each column: the wide layout MOVES sections, it does not
 * reorder them, and at narrow the same DOM renders as one stacked column.
 */
export function splitByRank<T>(items: T[], entry: (item: T) => { rank?: SectionRank }): { main: T[]; aside: T[] } {
  const main: T[] = [];
  const aside: T[] = [];
  for (const item of items) {
    (rankOf(entry(item)) === 'secondary' ? aside : main).push(item);
  }
  return { main, aside };
}

// A summary key/value row. `get` returns null to hide the row.
export interface OverviewField {
  label: string;
  mono?: boolean;
  /** See {@link SectionRank}. Omit for primary. */
  rank?: SectionRank;
  get: (o: KubeObject) => OvValue | null;
}

const text = (s: string): OvValue => ({ kind: 'text', text: s });

export const OVERVIEW_FIELDS: OverviewField[] = [
  { label: 'Kind', get: (o) => text(o.kind ?? '—') },
  { label: 'Namespace', get: (o) => (ovMeta(o).namespace ? text(ovMeta(o).namespace as string) : null) },
  { label: 'Name', get: (o) => text(ovMeta(o).name ?? '—') },
  {
    label: 'Status',
    get: (o) => (typeof ovStatus(o).phase === 'string' ? text(ovStatus(o).phase as string) : null),
  },
  {
    label: 'Node',
    get: (o) => {
      const n = ovSpec(o).nodeName as string | undefined;
      return n ? { kind: 'nav', text: n, navKind: 'Node' } : null;
    },
  },
  {
    label: 'Pod IP',
    mono: true,
    get: (o) => ((ovStatus(o).podIP as string) ? text(ovStatus(o).podIP as string) : null),
  },
  {
    label: 'Host IP',
    mono: true,
    get: (o) => ((ovStatus(o).hostIP as string) ? text(ovStatus(o).hostIP as string) : null),
  },
  {
    label: 'QoS Class',
    get: (o) => ((ovStatus(o).qosClass as string) ? text(ovStatus(o).qosClass as string) : null),
  },
  { label: 'Type', get: (o) => ((ovSpec(o).type as string) ? text(ovSpec(o).type as string) : null) },
  {
    label: 'Cluster IP',
    mono: true,
    get: (o) => ((ovSpec(o).clusterIP as string) ? text(ovSpec(o).clusterIP as string) : null),
  },
  {
    label: 'Internal IP',
    mono: true,
    get: (o) => {
      const addrs = (ovStatus(o).addresses as { type: string; address: string }[] | undefined) ?? [];
      const ip = addrs.find((a) => a.type === 'InternalIP')?.address;
      return ip ? text(ip) : null;
    },
  },
  {
    label: 'Kubelet',
    get: (o) => {
      const v = (ovStatus(o).nodeInfo as Record<string, string> | undefined)?.kubeletVersion;
      return v ? text(v) : null;
    },
  },
  {
    // Where a LoadBalancer Service actually answers. Empty while the LB is still being
    // provisioned, which is itself the answer to "why can't I reach it yet".
    label: 'External',
    mono: true,
    get: (o) => {
      const lb = (ovStatus(o).loadBalancer as Record<string, unknown> | undefined)?.ingress;
      const addrs = ovArr(lb)
        .map((i) => str(i.ip) || str(i.hostname))
        .filter(Boolean);
      const external = ((ovSpec(o).externalIPs as string[] | undefined) ?? []).filter(Boolean);
      const all = [...addrs, ...external];
      return all.length > 0 ? text(all.join(', ')) : null;
    },
  },
  {
    label: 'Session Affinity',
    get: (o) => {
      const a = ovSpec(o).sessionAffinity as string | undefined;
      // "None" is the default and says nothing; only a real affinity is worth a row.
      return a && a !== 'None' ? text(a) : null;
    },
  },
  {
    label: 'Restart Policy',
    get: (o) => {
      const p = ovPodSpec(o).restartPolicy as string | undefined;
      return p ? text(p) : null;
    },
  },
  {
    label: 'Service Account',
    get: (o) => {
      const sa = (ovPodSpec(o).serviceAccountName ?? ovPodSpec(o).serviceAccount) as string | undefined;
      return sa ? { kind: 'nav', text: sa, navKind: 'ServiceAccount', navNs: ovMeta(o).namespace } : null;
    },
  },
  {
    label: 'Priority Class',
    get: (o) => {
      const pc = ovPodSpec(o).priorityClassName as string | undefined;
      return pc ? text(pc) : null;
    },
  },
  { label: 'Secret Type', mono: true, get: (o) => ((o.type as string) ? text(o.type as string) : null) },
  {
    label: 'Created',
    rank: 'secondary',
    get: (o) => {
      const t = ovMeta(o).creationTimestamp;
      return text(age(t) + (t ? ` · ${t}` : ''));
    },
  },
  {
    // Which Helm release owns this object — provenance, not what the object does. `Controlled
    // By` below stays primary by contrast: an owner reference is a navigable part of the
    // object's own story ("this pod belongs to that ReplicaSet"), not a stamp on it.
    label: 'Managed By',
    rank: 'secondary',
    get: (o) => {
      const a = ovMeta(o).annotations ?? {};
      const rel = a['meta.helm.sh/release-name'];
      if (!rel) {
        return null;
      }
      const rns = a['meta.helm.sh/release-namespace'] ?? ovMeta(o).namespace ?? '';
      return { kind: 'helm', rel, rns };
    },
  },
  {
    label: 'Controlled By',
    get: (o) => {
      const owners = ovMeta(o).ownerReferences ?? [];
      if (owners.length === 0) {
        return null;
      }
      const ns = ovMeta(o).namespace;
      return { kind: 'owners', owners: owners.map((ow) => ({ kind: ow.kind, name: ow.name, ns })) };
    },
  },
];

// ---- Section descriptors (a collapsible section's rendered body) ----
interface OvCell {
  text: string;
  mono?: boolean;
}

/** What a section renders. Exported because OverviewSection.vue is the one that renders it. */
export type OvBody =
  | { type: 'chips'; map: Record<string, string> }
  | { type: 'annotations'; map: Record<string, string> }
  | { type: 'secret'; data: Record<string, string | null> }
  | { type: 'kv'; pairs: [string, string][] }
  | { type: 'table'; headers: string[]; rows: OvCell[][]; tls?: string[]; note?: string };

// A collapsible section. `applies` decides visibility; `body` renders its content.
export interface OverviewSection {
  title: string;
  defaultOpen?: boolean;
  /** See {@link SectionRank}. Omit for primary. */
  rank?: SectionRank;
  count?: (o: KubeObject) => number;
  applies: (o: KubeObject) => boolean;
  body: (o: KubeObject) => OvBody;
}

/** Defensive stringify (cluster objects vary; undefined/null render as empty). */
const str = (v: unknown): string => (v === undefined || v === null ? '' : String(v));

const plain = (s: string): OvCell => ({ text: s });
const mono = (s: string): OvCell => ({ text: s, mono: true });

// The service/workload selector (Service is flat; workloads nest under matchLabels).
function ovSelectorEntries(o: KubeObject): [string, string][] {
  const raw = ovSpec(o).selector as Record<string, unknown> | undefined;
  const sel =
    (raw?.matchLabels as Record<string, string> | undefined) ?? (raw as Record<string, string> | undefined) ?? {};
  return Object.entries(sel).filter(([, v]) => typeof v === 'string') as [string, string][];
}

/**
 * NOT APPLICABLE vs KNOWN FALSE — the distinction #248 turned on.
 *
 * `Ready` and `Restarts` are per-POD runtime facts, read off `status.containerStatuses`. Only a
 * Pod has those. Every other kind the Containers section applies to (Deployment, StatefulSet,
 * DaemonSet, ReplicaSet, Job, CronJob) resolves its containers from the pod TEMPLATE — a spec,
 * which by definition has no runtime state — so the old code's `st?.ready ? 'Yes' : 'No'` and
 * `st?.restartCount ?? 0` printed `No` / `0` for every one of them. That is not a missing value
 * rendered conservatively; it is an INVENTED one, and it read as an alarm: a healthy Deployment
 * said `Ready: No` four lines above a Rollout section saying `Desired 1 / Ready 1`.
 *
 * Two rules follow, and both are needed — one alone leaves a hole:
 *
 *   1. {@link hasContainerRuntime} — when the object carries no container statuses at all, the
 *      two columns are DROPPED, not blanked, and the table says why. A column of em dashes is
 *      still a column: it invites "why is this empty?" about a question that was never asked
 *      here, and costs width in a drawer that is 520px by default. Dropping it says
 *      structurally what is true — this object has a template, not a status.
 *   2. {@link readyText} / {@link restartsText} — a Pod DOES have the columns, but may not yet
 *      have a status for a given container (one still being created, an ephemeral container
 *      just attached). That cell is `—`: absent, not false.
 */
export function hasContainerRuntime(o: KubeObject): boolean {
  return ovArr(ovStatus(o).containerStatuses).length > 0;
}

/** Readiness as the object actually states it — `—` when it does not state it. */
export function readyText(st: Record<string, unknown> | undefined): string {
  if (st?.ready === undefined) {
    return '—';
  }
  return st.ready ? 'Yes' : 'No';
}

/** Restart count as the object actually states it — `—` when it does not state it. */
export function restartsText(st: Record<string, unknown> | undefined): string {
  return st?.restartCount === undefined ? '—' : String(Number(st.restartCount));
}

/**
 * Whether these containers came from a pod TEMPLATE rather than the object's own spec. Asked
 * of the SPEC rather than by comparing object identity with {@link ovPodSpec}, whose fallback
 * returns a fresh `{}` for a spec-less object and would make identity say "template".
 */
export function fromPodTemplate(o: KubeObject): boolean {
  const spec = ovSpec(o);
  return !Array.isArray(spec.containers) && (!!spec.template || !!spec.jobTemplate);
}

/**
 * Said out loud under the table, so a reader is not left to notice two absent columns — and
 * said DIFFERENTLY for the two ways of having no runtime status, because they are different
 * facts. A Deployment will never have one; a Pending pod does not have one YET.
 */
function containersNote(o: KubeObject): string {
  if (fromPodTemplate(o)) {
    return (
      'Ready and restart counts are per-pod runtime state. This object carries a pod template, ' +
      'so it has none of its own — the pods it creates do.'
    );
  }
  return 'This pod has not reported any container status yet, so readiness and restart counts are not available.';
}

const CONTAINER_SPEC_HEADERS = ['Name', 'Image', 'Ports', 'Requests'];

function containersBody(o: KubeObject): OvBody {
  const containers = ovArr(ovPodSpec(o).containers);
  const cs = ovArr(ovStatus(o).containerStatuses);
  const runtime = hasContainerRuntime(o);
  const statusFor = (n: string) => cs.find((s) => s.name === n);
  const ports = (cc: Record<string, unknown>) =>
    ovArr(cc.ports)
      .map((p) => `${p.containerPort}${p.protocol && p.protocol !== 'TCP' ? '/' + p.protocol : ''}`)
      .join(', ');
  const resources = (cc: Record<string, unknown>) => {
    const req = (cc.resources as Record<string, unknown> | undefined)?.requests as Record<string, string> | undefined;
    if (!req) {
      return '—';
    }
    return [req.cpu && `cpu ${req.cpu}`, req.memory && `mem ${req.memory}`].filter(Boolean).join(', ') || '—';
  };
  const rows = containers.map((cc) => {
    const cn = String(cc.name ?? '');
    const st = statusFor(cn);
    const spec = [plain(cn), mono(String(cc.image ?? '')), plain(ports(cc) || '—'), plain(resources(cc))];
    return runtime ? [...spec, plain(readyText(st)), plain(restartsText(st))] : spec;
  });
  if (!runtime) {
    return { type: 'table', headers: CONTAINER_SPEC_HEADERS, rows, note: containersNote(o) };
  }
  return { type: 'table', headers: [...CONTAINER_SPEC_HEADERS, 'Ready', 'Restarts'], rows };
}

function portsBody(o: KubeObject): OvBody {
  const rows = ovArr(ovSpec(o).ports).map((p) => [
    plain(String(p.name ?? '—')),
    plain(String(p.port ?? '')),
    plain(String(p.targetPort ?? '')),
    plain(String(p.protocol ?? 'TCP')),
    plain(String(p.nodePort ?? '—')),
  ]);
  return { type: 'table', headers: ['Name', 'Port', 'Target', 'Protocol', 'Node Port'], rows };
}

function rulesBody(o: KubeObject): OvBody {
  const rules = ovArr(ovSpec(o).rules);
  const tls = ovArr(ovSpec(o).tls);
  const rows: OvCell[][] = [];
  for (const r of rules) {
    const host = String(r.host ?? '*');
    const paths = ovArr((r.http as Record<string, unknown>)?.paths);
    if (paths.length === 0) {
      rows.push([plain(host), plain('—'), plain('—')]);
      continue;
    }
    for (const p of paths) {
      const svc = (p.backend as Record<string, unknown>)?.service as Record<string, unknown> | undefined;
      const port = (svc?.port as Record<string, unknown> | undefined)?.number;
      rows.push([plain(host), mono(String(p.path ?? '/')), mono(svc ? `${svc.name}${port ? ':' + port : ''}` : '—')]);
    }
  }
  const tlsHosts = tls.flatMap((t) => (t.hosts as string[] | undefined) ?? []);
  return { type: 'table', headers: ['Host', 'Path', 'Backend'], rows, tls: tlsHosts };
}

/**
 * A ConfigMap/Secret value that came from a LIST payload is `null` — the keys are shipped, the
 * values are not (GH#276), and the drawer fetches the whole object to fill them in. Until that
 * answer arrives (or if it fails) the cell must say "not loaded", NOT render an empty string:
 * an empty cell is indistinguishable from a key whose value genuinely IS empty, which would be
 * an invented fact of exactly the kind #248 was about.
 */
export const dataValueText = (v: unknown): string => {
  if (typeof v !== 'string') {
    return '—';
  }
  return v.length > 200 ? v.slice(0, 200) + '…' : v;
};

/**
 * A Secret cell: masked, revealed (base64-decoded), or "not loaded" — see
 * {@link dataValueText} for why the third state is not rendered as an empty value.
 */
export const secretValueText = (v: string | null | undefined, revealed: boolean): string => {
  if (typeof v !== 'string') {
    return '—';
  }
  if (!revealed) {
    return '••••••••';
  }
  try {
    return atob(v);
  } catch {
    return '‹binary›';
  }
};

function configMapDataBody(o: KubeObject): OvBody {
  const rows = Object.entries(ovDataMap(o.data)).map(([k, v]) => [mono(k), mono(dataValueText(v))]);
  return { type: 'table', headers: ['Key', 'Value'], rows };
}

function tolerationsBody(o: KubeObject): OvBody {
  const rows = ovArr(ovPodSpec(o).tolerations).map((t) => [
    mono(String(t.key ?? '*')),
    plain(String(t.operator ?? '')),
    plain(String(t.value ?? '—')),
    plain(String(t.effect ?? 'All')),
  ]);
  return { type: 'table', headers: ['Key', 'Operator', 'Value', 'Effect'], rows };
}

function volumesBody(o: KubeObject): OvBody {
  const rows = ovArr(ovPodSpec(o).volumes).map((v) => {
    const type = Object.keys(v).find((key) => key !== 'name') ?? '—';
    return [plain(String(v.name ?? '')), mono(type)];
  });
  return { type: 'table', headers: ['Name', 'Type'], rows };
}

function taintsBody(o: KubeObject): OvBody {
  const rows = ovArr(ovSpec(o).taints).map((t) => [
    mono(String(t.key ?? '')),
    plain(String(t.value ?? '—')),
    plain(String(t.effect ?? '')),
  ]);
  return { type: 'table', headers: ['Key', 'Value', 'Effect'], rows };
}

/**
 * Container environment: every container's `env` entries plus its whole-map `envFrom`
 * imports. Debugging a pod almost always needs this and it was previously only reachable by
 * reading the YAML.
 *
 * Values sourced via `valueFrom` are shown as the REFERENCE (e.g. `secret my-creds/password`)
 * — kweblens never resolves a secret's value here. Literal values ARE shown, deliberately:
 * they live in the pod spec, which the YAML tab already displays in full, so masking them
 * here would be theatre rather than protection. (Secret OBJECT values stay masked behind
 * Reveal in the Secret drawer, where the value really is the secret.)
 */
function envBody(o: KubeObject): OvBody {
  const rows: OvCell[][] = [];
  for (const cc of envContainers(o)) {
    const cname = str(cc.name);
    for (const e of ovArr(cc.env)) {
      rows.push([plain(cname), mono(str(e.name)), envValueCell(e)]);
    }
    for (const f of ovArr(cc.envFrom)) {
      const ref = envFromRef(f);
      if (ref) {
        rows.push([plain(cname), mono('(all keys)'), plain(ref)]);
      }
    }
  }
  return { type: 'table', headers: ['Container', 'Name', 'Value / Source'], rows };
}

/** Containers whose env we surface: regular + init + ephemeral, in that order. */
function envContainers(o: KubeObject): Record<string, unknown>[] {
  const ps = ovPodSpec(o);
  return [...ovArr(ps.containers), ...ovArr(ps.initContainers), ...ovArr(ps.ephemeralContainers)];
}

function envValueCell(e: Record<string, unknown>): OvCell {
  if (e.value !== undefined) {
    return mono(str(e.value));
  }
  const from = e.valueFrom as Record<string, unknown> | undefined;
  if (!from) {
    return plain('—');
  }
  const keyRef = (k: string, label: string) => {
    const r = from[k] as Record<string, unknown> | undefined;
    return r ? `${label} ${str(r.name)}/${str(r.key)}` : null;
  };
  const fieldRef = (from.fieldRef as Record<string, unknown> | undefined)?.fieldPath;
  const resRef = from.resourceFieldRef as Record<string, unknown> | undefined;
  return plain(
    keyRef('secretKeyRef', 'secret') ??
      keyRef('configMapKeyRef', 'configMap') ??
      (fieldRef ? `field ${str(fieldRef)}` : null) ??
      (resRef ? `resource ${str(resRef.resource)}` : null) ??
      '—',
  );
}

function envFromRef(f: Record<string, unknown>): string | null {
  const cm = f.configMapRef as Record<string, unknown> | undefined;
  const sec = f.secretRef as Record<string, unknown> | undefined;
  if (cm) {
    return `configMap ${str(cm.name)}`;
  }
  return sec ? `secret ${str(sec.name)}` : null;
}

/**
 * Per-container runtime state — the "why did it die" section. lastState.terminated carries
 * the reason and EXIT CODE of the previous run, which is the first thing you want for a
 * CrashLoopBackOff and is not visible anywhere else in the UI.
 */
function containerStateBody(o: KubeObject): OvBody {
  const rows: OvCell[][] = [];
  const all = [...ovArr(ovStatus(o).containerStatuses), ...ovArr(ovStatus(o).initContainerStatuses)];
  for (const cs of all) {
    // Same rule as the Containers table: a status that does not say `ready` is `—`, not `No`.
    rows.push([
      plain(str(cs.name)),
      plain(readyText(cs)),
      plain(restartsText(cs)),
      plain(stateText(cs.state)),
      plain(stateText(cs.lastState)),
    ]);
  }
  return { type: 'table', headers: ['Container', 'Ready', 'Restarts', 'State', 'Last State'], rows };
}

/** "Running since …", or "Terminated: OOMKilled (exit 137)" — the diagnostic bit. */
function stateText(state: unknown): string {
  const st = state as Record<string, unknown> | undefined;
  if (!st) {
    return '—';
  }
  const term = st.terminated as Record<string, unknown> | undefined;
  if (term) {
    const reason = str(term.reason) || 'Terminated';
    return `${reason} (exit ${str(term.exitCode)})`;
  }
  const waiting = st.waiting as Record<string, unknown> | undefined;
  if (waiting) {
    return str(waiting.reason) || 'Waiting';
  }
  const running = st.running as Record<string, unknown> | undefined;
  if (running) {
    return running.startedAt ? `Running since ${str(running.startedAt)}` : 'Running';
  }
  return '—';
}

function nodeInfoBody(o: KubeObject): OvBody {
  const nodeInfo = (ovStatus(o).nodeInfo as Record<string, string> | undefined) ?? {};
  const pairs = (
    [
      ['OS Image', nodeInfo.osImage],
      ['Architecture', nodeInfo.architecture],
      ['Kernel', nodeInfo.kernelVersion],
      ['Container Runtime', nodeInfo.containerRuntimeVersion],
      ['Kube-Proxy', nodeInfo.kubeProxyVersion],
    ] as [string, string | undefined][]
  ).filter(([, v]) => v) as [string, string][];
  return { type: 'kv', pairs };
}

function capacityBody(o: KubeObject): OvBody {
  const capacity = ovMap(ovStatus(o).capacity);
  const allocatable = ovMap(ovStatus(o).allocatable);
  const rows = Array.from(new Set([...Object.keys(capacity), ...Object.keys(allocatable)])).map((r) => [
    mono(r),
    plain(capacity[r] ?? '—'),
    plain(allocatable[r] ?? '—'),
  ]);
  return { type: 'table', headers: ['Resource', 'Capacity', 'Allocatable'], rows };
}

function conditionsBody(o: KubeObject): OvBody {
  const rows = ovArr(ovStatus(o).conditions).map((cond) => [
    plain(String(cond.type ?? '')),
    plain(String(cond.status ?? '')),
    plain(String(cond.reason ?? '')),
  ]);
  return { type: 'table', headers: ['Type', 'Status', 'Reason'], rows };
}

// ---- Sections added by the #24 audit ----

/** Every container of the resolved pod spec, init containers included and labelled. */
function ovAllContainers(o: KubeObject): { c: Record<string, unknown>; init: boolean }[] {
  const ps = ovPodSpec(o);
  return [
    ...ovArr(ps.containers).map((c) => ({ c, init: false })),
    ...ovArr(ps.initContainers).map((c) => ({ c, init: true })),
  ];
}

const ovName = (c: Record<string, unknown>, init: boolean) => String(c.name ?? '') + (init ? ' (init)' : '');

/**
 * Requests vs limits per container — the first thing to check for an OOMKill (limit too
 * low), CPU throttling (limit too low), or a Pending pod (requests unschedulable). Shown
 * side by side because the gap between them is the interesting part.
 */
function resourcesBody(o: KubeObject): OvBody {
  const cell = (res: Record<string, unknown> | undefined, side: string, key: string) => {
    const v = ((res?.[side] as Record<string, string> | undefined) ?? {})[key];
    return v ? mono(v) : plain('—');
  };
  const rows = ovAllContainers(o).map(({ c, init }) => {
    const res = c.resources as Record<string, unknown> | undefined;
    return [
      plain(ovName(c, init)),
      cell(res, 'requests', 'cpu'),
      cell(res, 'limits', 'cpu'),
      cell(res, 'requests', 'memory'),
      cell(res, 'limits', 'memory'),
    ];
  });
  return { type: 'table', headers: ['Container', 'CPU req', 'CPU limit', 'Mem req', 'Mem limit'], rows };
}

const ovHasResources = (o: KubeObject) =>
  ovAllContainers(o).some(({ c }) => {
    const res = c.resources as Record<string, unknown> | undefined;
    return !!res && (!!res.requests || !!res.limits);
  });

/**
 * Probes, one row per configured probe. A failing readiness probe is why a pod is Running
 * but not Ready, and an over-aggressive liveness probe is a common cause of a restart loop
 * that looks like an application crash — so the thresholds matter, not just the target.
 */
function probesBody(o: KubeObject): OvBody {
  const target = (p: Record<string, unknown>): string => {
    const http = p.httpGet as Record<string, unknown> | undefined;
    if (http) {
      const scheme = String(http.scheme ?? 'HTTP').toLowerCase();
      return `${scheme}://:${str(http.port)}${str(http.path)}`;
    }
    const tcp = p.tcpSocket as Record<string, unknown> | undefined;
    if (tcp) {
      return `tcp :${str(tcp.port)}`;
    }
    const exec = p.exec as Record<string, unknown> | undefined;
    if (exec) {
      return ((exec.command as string[] | undefined) ?? []).join(' ');
    }
    const grpc = p.grpc as Record<string, unknown> | undefined;
    return grpc ? `grpc :${str(grpc.port)}` : '—';
  };
  const timing = (p: Record<string, unknown>) =>
    [
      p.initialDelaySeconds !== undefined && `delay ${str(p.initialDelaySeconds)}s`,
      p.periodSeconds !== undefined && `every ${str(p.periodSeconds)}s`,
      p.timeoutSeconds !== undefined && `timeout ${str(p.timeoutSeconds)}s`,
      p.failureThreshold !== undefined && `fail x${str(p.failureThreshold)}`,
    ]
      .filter(Boolean)
      .join(', ') || '—';
  const rows: OvCell[][] = [];
  for (const { c, init } of ovAllContainers(o)) {
    for (const [label, key] of [
      ['liveness', 'livenessProbe'],
      ['readiness', 'readinessProbe'],
      ['startup', 'startupProbe'],
    ] as const) {
      const p = c[key] as Record<string, unknown> | undefined;
      if (p) {
        rows.push([plain(ovName(c, init)), plain(label), mono(target(p)), plain(timing(p))]);
      }
    }
  }
  return { type: 'table', headers: ['Container', 'Probe', 'Target', 'Timing'], rows };
}

const ovProbeCount = (o: KubeObject) =>
  ovAllContainers(o).reduce(
    (n, { c }) =>
      n + ['livenessProbe', 'readinessProbe', 'startupProbe'].filter((k) => !!c[k as keyof typeof c]).length,
    0,
  );

/**
 * Mounts joined to the volumes they resolve to — the ticket's explicit ask. On its own a
 * mount path says nothing about what is actually mounted there; the join is what turns
 * "/etc/config" into "the app-config ConfigMap", which is the question being asked.
 */
function mountsBody(o: KubeObject): OvBody {
  const volumes = ovArr(ovPodSpec(o).volumes);
  const sourceOf = (name: string): string => {
    const v = volumes.find((vol) => vol.name === name);
    if (!v) {
      return '—';
    }
    for (const [key, label] of [
      ['configMap', 'configMap'],
      ['secret', 'secret'],
      ['persistentVolumeClaim', 'pvc'],
      ['projected', 'projected'],
      ['emptyDir', 'emptyDir'],
      ['hostPath', 'hostPath'],
      ['downwardAPI', 'downwardAPI'],
      ['csi', 'csi'],
    ] as const) {
      const src = v[key] as Record<string, unknown> | undefined;
      if (src) {
        const ref = src.name ?? src.secretName ?? src.claimName ?? src.path ?? src.driver;
        return ref ? `${label} ${str(ref)}` : label;
      }
    }
    return '—';
  };
  const rows: OvCell[][] = [];
  for (const { c, init } of ovAllContainers(o)) {
    for (const m of ovArr(c.volumeMounts)) {
      rows.push([
        plain(ovName(c, init)),
        mono(str(m.mountPath)),
        plain(m.readOnly ? 'ro' : 'rw'),
        mono(sourceOf(String(m.name ?? ''))),
      ]);
    }
  }
  return { type: 'table', headers: ['Container', 'Path', 'Mode', 'Source'], rows };
}

const ovMountCount = (o: KubeObject) => ovAllContainers(o).reduce((n, { c }) => n + ovArr(c.volumeMounts).length, 0);

/** Rollout strategy plus the replica breakdown — "is this rollout finished, and if not, why". */
function rolloutBody(o: KubeObject): OvBody {
  const spec = ovSpec(o);
  const st = ovStatus(o);
  const strategy = (spec.strategy ?? spec.updateStrategy) as Record<string, unknown> | undefined;
  const rolling = (strategy?.rollingUpdate ?? {}) as Record<string, unknown>;
  const pairs: [string, string][] = [];
  if (strategy?.type) {
    pairs.push(['Strategy', str(strategy.type)]);
  }
  if (rolling.maxSurge !== undefined) {
    pairs.push(['Max surge', str(rolling.maxSurge)]);
  }
  if (rolling.maxUnavailable !== undefined) {
    pairs.push(['Max unavailable', str(rolling.maxUnavailable)]);
  }
  // Named to match kubectl's columns so the numbers are recognisable.
  for (const [label, key] of [
    ['Desired', 'replicas'],
    ['Updated', 'updatedReplicas'],
    ['Ready', 'readyReplicas'],
    ['Available', 'availableReplicas'],
    ['Unavailable', 'unavailableReplicas'],
    ['Current revision', 'currentRevision'],
    ['Update revision', 'updateRevision'],
    // DaemonSet uses its own vocabulary.
    ['Desired (nodes)', 'desiredNumberScheduled'],
    ['Ready (nodes)', 'numberReady'],
    ['Misscheduled', 'numberMisscheduled'],
  ] as const) {
    const v = key === 'replicas' && st[key] === undefined ? spec[key] : st[key];
    if (v !== undefined) {
      pairs.push([label, str(v)]);
    }
  }
  if (spec.revisionHistoryLimit !== undefined) {
    pairs.push(['Revision history', str(spec.revisionHistoryLimit)]);
  }
  return { type: 'kv', pairs };
}

const ovIsRolloutKind = (o: KubeObject) =>
  ['Deployment', 'StatefulSet', 'DaemonSet', 'ReplicaSet'].includes(o.kind ?? '');

/** Schedule and run history for CronJob/Job — did it run, did it finish, did it fail. */
function scheduleBody(o: KubeObject): OvBody {
  const spec = ovSpec(o);
  const st = ovStatus(o);
  const pairs: [string, string][] = [];
  const add = (label: string, v: unknown) => {
    if (v !== undefined && v !== null) {
      pairs.push([label, str(v)]);
    }
  };
  add('Schedule', spec.schedule);
  add('Time zone', spec.timeZone);
  add('Suspended', spec.suspend === undefined ? undefined : spec.suspend ? 'Yes' : 'No');
  add('Concurrency', spec.concurrencyPolicy);
  add('Starting deadline', spec.startingDeadlineSeconds && `${str(spec.startingDeadlineSeconds)}s`);
  add('Last schedule', st.lastScheduleTime);
  add('Last successful', st.lastSuccessfulTime);
  // Job-side counters.
  add('Completions', spec.completions);
  add('Parallelism', spec.parallelism);
  add('Backoff limit', spec.backoffLimit);
  add('Active deadline', spec.activeDeadlineSeconds && `${str(spec.activeDeadlineSeconds)}s`);
  add('Active', st.active !== undefined && !Array.isArray(st.active) ? st.active : undefined);
  add('Running now', Array.isArray(st.active) ? st.active.length : undefined);
  add('Succeeded', st.succeeded);
  add('Failed', st.failed);
  add('Started', st.startTime);
  add('Completed', st.completionTime);
  return { type: 'kv', pairs };
}

/** Storage details for a PVC or PV: what it binds to, its class, modes and capacity. */
function storageBody(o: KubeObject): OvBody {
  const spec = ovSpec(o);
  const st = ovStatus(o);
  const pairs: [string, string][] = [];
  const add = (label: string, v: unknown) => {
    if (v !== undefined && v !== null && v !== '') {
      pairs.push([label, str(v)]);
    }
  };
  add('Phase', st.phase);
  add('Storage class', spec.storageClassName);
  add('Volume mode', spec.volumeMode);
  add('Access modes', ((spec.accessModes as string[] | undefined) ?? []).join(', '));
  add(
    'Requested',
    ((spec.resources as Record<string, unknown> | undefined)?.requests as Record<string, string>)?.storage,
  );
  add(
    'Capacity',
    (st.capacity as Record<string, string> | undefined)?.storage ??
      (spec.capacity as Record<string, string> | undefined)?.storage,
  );
  // PVC → PV, and PV → the claim that owns it: the binding is the thing you came to check.
  add('Bound volume', spec.volumeName);
  const claim = spec.claimRef as Record<string, unknown> | undefined;
  add('Bound claim', claim ? `${str(claim.namespace)}/${str(claim.name)}` : undefined);
  add('Reclaim policy', spec.persistentVolumeReclaimPolicy);
  return { type: 'kv', pairs };
}

/**
 * RBAC rules. Roles and ClusterRoles carry `rules` at the TOP level, not under `spec` —
 * which is why the existing (Ingress-shaped) Rules section never matched them and RBAC
 * detail was blank.
 */
function rbacRulesBody(o: KubeObject): OvBody {
  const rows = ovArr((o as Record<string, unknown>).rules).map((r) => [
    mono(((r.apiGroups as string[] | undefined) ?? ['']).map((g) => g || 'core').join(', ')),
    mono(((r.resources as string[] | undefined) ?? []).join(', ') || '—'),
    mono(((r.verbs as string[] | undefined) ?? []).join(', ') || '—'),
  ]);
  return { type: 'table', headers: ['API groups', 'Resources', 'Verbs'], rows };
}

const ovRbacRules = (o: KubeObject) => ovArr((o as Record<string, unknown>).rules);

/** Who a RoleBinding/ClusterRoleBinding grants to, and which role it grants. */
function bindingBody(o: KubeObject): OvBody {
  const roleRef = (o as Record<string, unknown>).roleRef as Record<string, unknown> | undefined;
  const rows = ovArr((o as Record<string, unknown>).subjects).map((s) => [
    plain(str(s.kind)),
    mono(str(s.name)),
    plain(str(s.namespace) || '—'),
  ]);
  const headers = ['Subject kind', 'Name', 'Namespace'];
  if (roleRef) {
    // The role being granted leads, since it is what the binding actually confers.
    rows.unshift([plain(`→ ${str(roleRef.kind)}`), mono(str(roleRef.name)), plain('—')]);
  }
  return { type: 'table', headers, rows };
}

export const OVERVIEW_SECTIONS: OverviewSection[] = [
  // Labels and Annotations are the two sections every object has and few readers came for —
  // the secondary set #231 names, and the sidebar #232 builds.
  {
    title: 'Labels',
    defaultOpen: false,
    rank: 'secondary',
    applies: (o) => Object.keys(ovMeta(o).labels ?? {}).length > 0,
    count: (o) => Object.keys(ovMeta(o).labels ?? {}).length,
    body: (o) => ({ type: 'chips', map: ovMeta(o).labels ?? {} }),
  },
  {
    title: 'Annotations',
    defaultOpen: false,
    rank: 'secondary',
    applies: (o) => Object.keys(ovMeta(o).annotations ?? {}).length > 0,
    count: (o) => Object.keys(ovMeta(o).annotations ?? {}).length,
    body: (o) => ({ type: 'annotations', map: ovMeta(o).annotations ?? {} }),
  },
  {
    title: 'Containers',
    applies: (o) => ovArr(ovPodSpec(o).containers).length > 0,
    count: (o) => ovArr(ovPodSpec(o).containers).length,
    body: containersBody,
  },
  {
    title: 'Environment',
    applies: (o) => envContainers(o).some((c) => ovArr(c.env).length > 0 || ovArr(c.envFrom).length > 0),
    count: (o) => envContainers(o).reduce((n, c) => n + ovArr(c.env).length + ovArr(c.envFrom).length, 0),
    body: envBody,
  },
  {
    title: 'Container Status',
    applies: (o) =>
      ovArr(ovStatus(o).containerStatuses).length > 0 || ovArr(ovStatus(o).initContainerStatuses).length > 0,
    count: (o) => ovArr(ovStatus(o).containerStatuses).length + ovArr(ovStatus(o).initContainerStatuses).length,
    body: containerStateBody,
  },
  // Ordered most-diagnostic-first (the ticket's ask): after "what is it doing" (Container
  // Status) comes "why" — resources, then probes. Both open by default because they are the
  // usual answer to an OOMKill, a throttle, or a Running-but-not-Ready pod.
  {
    title: 'Resources',
    applies: ovHasResources,
    count: (o) => ovAllContainers(o).length,
    body: resourcesBody,
  },
  {
    title: 'Probes',
    applies: (o) => ovProbeCount(o) > 0,
    count: ovProbeCount,
    body: probesBody,
  },
  {
    title: 'Rollout',
    applies: (o) => ovIsRolloutKind(o) && Object.keys(ovSpec(o)).length > 0,
    body: rolloutBody,
  },
  {
    title: 'Schedule & Runs',
    applies: (o) => ['CronJob', 'Job'].includes(o.kind ?? ''),
    body: scheduleBody,
  },
  {
    title: 'Storage',
    applies: (o) => ['PersistentVolumeClaim', 'PersistentVolume'].includes(o.kind ?? ''),
    body: storageBody,
  },
  {
    title: 'RBAC Rules',
    applies: (o) => ovRbacRules(o).length > 0,
    count: (o) => ovRbacRules(o).length,
    body: rbacRulesBody,
  },
  {
    title: 'Subjects',
    applies: (o) => ovArr((o as Record<string, unknown>).subjects).length > 0,
    count: (o) => ovArr((o as Record<string, unknown>).subjects).length,
    body: bindingBody,
  },
  {
    title: 'Ports',
    applies: (o) => ovArr(ovSpec(o).ports).length > 0,
    count: (o) => ovArr(ovSpec(o).ports).length,
    body: portsBody,
  },
  {
    title: 'Selector',
    applies: (o) => ovSelectorEntries(o).length > 0,
    count: (o) => ovSelectorEntries(o).length,
    body: (o) => ({ type: 'chips', map: Object.fromEntries(ovSelectorEntries(o)) }),
  },
  {
    title: 'Rules',
    applies: (o) => ovArr(ovSpec(o).rules).length > 0,
    count: (o) => ovArr(ovSpec(o).rules).length,
    body: rulesBody,
  },
  {
    title: 'Data',
    applies: (o) => o.kind === 'ConfigMap' && Object.keys(ovDataMap(o.data)).length > 0,
    count: (o) => Object.keys(ovDataMap(o.data)).length,
    body: configMapDataBody,
  },
  {
    title: 'Data',
    applies: (o) => o.kind === 'Secret' && Object.keys(ovDataMap(o.data)).length > 0,
    count: (o) => Object.keys(ovDataMap(o.data)).length,
    body: (o) => ({ type: 'secret', data: ovDataMap(o.data) }),
  },
  {
    title: 'Node Selector',
    defaultOpen: false,
    applies: (o) => Object.keys(ovMap(ovPodSpec(o).nodeSelector)).length > 0,
    count: (o) => Object.keys(ovMap(ovPodSpec(o).nodeSelector)).length,
    body: (o) => ({ type: 'chips', map: ovMap(ovPodSpec(o).nodeSelector) }),
  },
  {
    title: 'Tolerations',
    defaultOpen: false,
    applies: (o) => ovArr(ovPodSpec(o).tolerations).length > 0,
    count: (o) => ovArr(ovPodSpec(o).tolerations).length,
    body: tolerationsBody,
  },
  {
    title: 'Volume Mounts',
    defaultOpen: false,
    applies: (o) => ovMountCount(o) > 0,
    count: ovMountCount,
    body: mountsBody,
  },
  {
    title: 'Volumes',
    defaultOpen: false,
    applies: (o) => ovArr(ovPodSpec(o).volumes).length > 0,
    count: (o) => ovArr(ovPodSpec(o).volumes).length,
    body: volumesBody,
  },
  {
    title: 'Taints',
    applies: (o) => ovArr(ovSpec(o).taints).length > 0,
    count: (o) => ovArr(ovSpec(o).taints).length,
    body: taintsBody,
  },
  {
    title: 'Node Info',
    defaultOpen: false,
    applies: (o) => !!ovStatus(o).nodeInfo,
    body: nodeInfoBody,
  },
  {
    title: 'Capacity',
    defaultOpen: false,
    applies: (o) => !!ovStatus(o).capacity || !!ovStatus(o).allocatable,
    body: capacityBody,
  },
  {
    title: 'Conditions',
    applies: (o) => ovArr(ovStatus(o).conditions).length > 0,
    count: (o) => ovArr(ovStatus(o).conditions).length,
    body: conditionsBody,
  },
];

import { gib, objSpec as spec, objStatus as status, parseMemBytes, toNum as num } from './kube';
import type { KubeObject, PrinterColumn } from './types';

// A kind-specific column: the "middle" columns between Name/Namespace and Age
// (those three are rendered by the table framework). render() returns a display string.
// A few kinds render richer cells (usage bars, container squares) in the table component
// keyed off the column `key`; those still return sortable text here. Provide sortText when
// the displayed value differs from its natural sort order.
export interface ColumnDef {
  key: string;
  header: string;
  render: (o: KubeObject) => string;
  sortText?: (o: KubeObject) => string;
  /** Fixed pixel width for columns whose values are always short (counts, flags, versions). */
  width?: number;
  /**
   * Right-align with tabular figures. For columns of magnitudes rather than labels: digits
   * then line up by place value, so 1625 among a column of 5s reads as large at a glance
   * instead of being just another string.
   */
  numeric?: boolean;
  /**
   * Start hidden (still listed in the Columns ▾ picker). For detail-rich kinds like Nodes
   * that offer more columns than fit comfortably — the same default other Kubernetes IDEs
   * use, showing the common set and leaving the rest opt-in.
   */
  defaultHidden?: boolean;
}

/** Keys a kind's table starts with hidden — seeds the list view's hidden-column set. */
export function defaultHiddenCols(resourceId: string | undefined): Set<string> {
  if (!resourceId) {
    return new Set();
  }
  return new Set(
    columnsFor(resourceId)
      .filter((c) => c.defaultHidden)
      .map((c) => c.key),
  );
}

// ---- small accessors (defensive: cluster objects vary) ----
// spec/status/num come from ./kube (shared with App.tsx); str/dash are column-only.
type Any = Record<string, unknown>;
const str = (v: unknown): string => (v === undefined || v === null ? '' : String(v));
const dash = (s: string): string => (s === '' ? '—' : s);

// Classify a status/phase string into a health tone for colouring. Unknown → '' (no pill).
export type StatusTone = 'ok' | 'warn' | 'err' | '';

const STATUS_ERR = [
  'failed',
  'error',
  'crashloop',
  'imagepull',
  'errimage',
  'evicted',
  'unschedulable',
  'backoff',
  'oomkill',
  'notready',
  'lost',
  'unavailable',
  'outofsync',
  'invalid',
  'denied',
];
const STATUS_WARN = [
  'pending',
  'creating',
  'terminating',
  'progressing',
  'provisioning',
  'updating',
  'waiting',
  'released',
  'unknown',
  'degraded',
  'paused',
  'notbound',
  'init',
];
const STATUS_OK = [
  'running',
  'ready',
  'active',
  'bound',
  'succeeded',
  'complete',
  'available',
  'healthy',
  'deployed',
  'established',
  'synced',
  'normal',
  'valid',
  'ok',
];

export function statusTone(value: string): StatusTone {
  const t = value.trim().toLowerCase();
  if (!t || t === '—') {
    return '';
  }
  if (STATUS_ERR.some((k) => t.includes(k))) {
    return 'err';
  }
  if (STATUS_WARN.some((k) => t.includes(k))) {
    return 'warn';
  }
  if (STATUS_OK.some((k) => t.includes(k))) {
    return 'ok';
  }
  return '';
}

// Colour a "ready/total" readiness value: all ready → green, none → red, partial → amber,
// scaled-to-zero (0/0) and non-ratio values → neutral (no pill).
/**
 * Tone for a Kubernetes event's `type`, which has exactly two values.
 *
 * A Warning is amber, not red: it means "something notable happened", and plenty of them are
 * routine (an image pull backing off once, a probe failing during startup). Colouring them as
 * errors would put the loudest tone on the most common non-Normal row, which is how a list
 * stops being scannable.
 *
 * Normal returns no tone deliberately. On a busy cluster nearly every row is Normal, so giving
 * it a colour would decorate the whole table and leave the warnings no more visible than before
 * — the problem this is meant to solve.
 */
export function eventTypeTone(value: string): StatusTone {
  return value.trim().toLowerCase() === 'warning' ? 'warn' : '';
}

export function readyTone(value: string): StatusTone {
  const m = /^(\d+)\s*\/\s*(\d+)$/.exec(value.trim());
  if (!m) {
    return '';
  }
  const ready = Number(m[1]);
  const total = Number(m[2]);
  if (total === 0) {
    return '';
  }
  if (ready >= total) {
    return 'ok';
  }
  return ready === 0 ? 'err' : 'warn';
}

export function age(iso: string | undefined): string {
  if (!iso) {
    return '—';
  }
  const then = Date.parse(iso);
  if (Number.isNaN(then)) {
    return '—';
  }
  const s = Math.max(0, Math.floor((Date.now() - then) / 1000));
  if (s >= 86400) {
    return Math.floor(s / 86400) + 'd';
  }
  if (s >= 3600) {
    return Math.floor(s / 3600) + 'h';
  }
  if (s >= 60) {
    return Math.floor(s / 60) + 'm';
  }
  return s + 's';
}

function podReady(o: KubeObject): string {
  const cs = (status(o).containerStatuses as Any[]) ?? [];
  const ready = cs.filter((c) => (c as Any).ready).length;
  return `${ready}/${cs.length}`;
}
function podRestarts(o: KubeObject): string {
  const cs = (status(o).containerStatuses as Any[]) ?? [];
  return String(cs.reduce((n, c) => n + num((c as Any).restartCount), 0));
}
function ports(o: KubeObject): string {
  const ps = (spec(o).ports as Any[]) ?? [];
  return dash(ps.map((p) => `${str((p as Any).port)}/${str((p as Any).protocol) || 'TCP'}`).join(', '));
}
function nodeRoles(o: KubeObject): string {
  const labels = o.metadata?.labels ?? {};
  const roles = Object.keys(labels)
    .filter((k) => k.startsWith('node-role.kubernetes.io/'))
    .map((k) => k.slice('node-role.kubernetes.io/'.length))
    .filter(Boolean);
  return dash(roles.join(', '));
}
function nodeReady(o: KubeObject): string {
  const conds = (status(o).conditions as Any[]) ?? [];
  const ready = conds.find((c) => (c as Any).type === 'Ready');
  return ready ? ((ready as Any).status === 'True' ? 'Ready' : 'NotReady') : '—';
}
function nodeAddress(o: KubeObject, type: string): string {
  const addrs = (status(o).addresses as Any[]) ?? [];
  const hit = addrs.find((a) => (a as Any).type === type);
  return hit ? str((hit as Any).address) : '—';
}
function nodeInternalIp(o: KubeObject): string {
  return nodeAddress(o, 'InternalIP');
}
function nodeExternalIp(o: KubeObject): string {
  return nodeAddress(o, 'ExternalIP');
}
/** Taint count (0 when none) — the at-a-glance "is anything repelling pods here". */
function nodeTaints(o: KubeObject): string {
  return String(((spec(o).taints as Any[]) ?? []).length);
}
/** Every condition currently True (Ready, EtcdIsVoter, …) — pressure conditions show here too. */
function nodeConditions(o: KubeObject): string {
  const conds = (status(o).conditions as Any[]) ?? [];
  return dash(
    conds
      .filter((c) => str((c as Any).status) === 'True')
      .map((c) => str((c as Any).type))
      .join(', '),
  );
}
/** kubectl's "SchedulingDisabled": spec.unschedulable is what cordon flips. */
function nodeSchedulable(o: KubeObject): string {
  return spec(o).unschedulable ? 'False' : 'True';
}
function nodeInfo(o: KubeObject, field: string): string {
  return dash(str(((status(o).nodeInfo as Any) ?? {})[field]));
}
function nodeLabel(o: KubeObject, ...names: string[]): string {
  const labels = o.metadata?.labels ?? {};
  for (const n of names) {
    if (labels[n]) {
      return labels[n];
    }
  }
  return '—';
}
const nodeCapacity = (o: KubeObject): Record<string, string> => (status(o).capacity as Record<string, string>) ?? {};
function keys(o: KubeObject): string {
  const data = (o.data as Any) ?? {};
  return String(Object.keys(data).length);
}
function involvedObject(o: KubeObject): string {
  const io = (o.involvedObject as Any) ?? {};
  return dash([str(io.kind), str(io.name)].filter(Boolean).join('/'));
}

// resourceId -> the kind-specific middle columns
const COLUMNS: Record<string, ColumnDef[]> = {
  pods: [
    { key: 'ready', header: 'Ready', render: podReady, width: 90 },
    { key: 'status', header: 'Status', render: (o) => dash(str(status(o).phase)) },
    { key: 'restarts', header: 'Restarts', render: podRestarts, width: 100 },
    { key: 'node', header: 'Node', render: (o) => dash(str(spec(o).nodeName)) },
  ],
  deployments: [
    { key: 'ready', header: 'Ready', render: (o) => `${num(status(o).readyReplicas)}/${num(spec(o).replicas)}` },
    { key: 'uptodate', header: 'Up-to-date', render: (o) => String(num(status(o).updatedReplicas)) },
    { key: 'available', header: 'Available', render: (o) => String(num(status(o).availableReplicas)) },
  ],
  statefulsets: [
    { key: 'ready', header: 'Ready', render: (o) => `${num(status(o).readyReplicas)}/${num(spec(o).replicas)}` },
  ],
  daemonsets: [
    { key: 'desired', header: 'Desired', render: (o) => String(num(status(o).desiredNumberScheduled)) },
    { key: 'current', header: 'Current', render: (o) => String(num(status(o).currentNumberScheduled)) },
    { key: 'ready', header: 'Ready', render: (o) => String(num(status(o).numberReady)) },
  ],
  replicasets: [
    { key: 'desired', header: 'Desired', render: (o) => String(num(spec(o).replicas)) },
    { key: 'current', header: 'Current', render: (o) => String(num(status(o).replicas)) },
    { key: 'ready', header: 'Ready', render: (o) => String(num(status(o).readyReplicas)) },
  ],
  jobs: [
    {
      key: 'completions',
      header: 'Completions',
      render: (o) => `${num(status(o).succeeded)}/${num(spec(o).completions)}`,
    },
    {
      key: 'status',
      header: 'Status',
      render: (o) => (num(status(o).succeeded) > 0 ? 'Complete' : dash(str(status(o).active ? 'Running' : ''))),
    },
  ],
  cronjobs: [
    { key: 'schedule', header: 'Schedule', render: (o) => dash(str(spec(o).schedule)) },
    { key: 'suspend', header: 'Suspend', render: (o) => (spec(o).suspend ? 'Yes' : 'No') },
    { key: 'active', header: 'Active', render: (o) => String(((status(o).active as Any[]) ?? []).length) },
    { key: 'last', header: 'Last schedule', render: (o) => age(str(status(o).lastScheduleTime) || undefined) },
  ],
  // Nodes carry far more useful detail than fits at once, so the common set is shown and the
  // rest is opt-in via the Columns ▾ picker (defaultHidden) — the CPU/Memory/Disk usage bars
  // are appended separately in table.ts from live metrics.
  nodes: [
    { key: 'status', header: 'Status', render: nodeReady, width: 110 },
    { key: 'roles', header: 'Roles', render: nodeRoles },
    { key: 'taints', header: 'Taints', render: nodeTaints, width: 90 },
    { key: 'version', header: 'Version', render: (o) => dash(str((status(o).nodeInfo as Any)?.kubeletVersion)) },
    { key: 'ip', header: 'Internal IP', render: nodeInternalIp },
    { key: 'schedulable', header: 'Schedulable', render: nodeSchedulable, width: 120 },
    { key: 'conditions', header: 'Conditions', render: nodeConditions },
    { key: 'ext-ip', header: 'External IP', render: nodeExternalIp, defaultHidden: true },
    {
      key: 'pod-capacity',
      header: 'Pod Capacity',
      render: (o) => dash(str(nodeCapacity(o).pods)),
      defaultHidden: true,
    },
    {
      key: 'capacity',
      header: 'Capacity',
      render: (o) => {
        const cap = nodeCapacity(o);
        const mem = parseMemBytes(cap.memory);
        return dash([cap.cpu ? `${cap.cpu} CPU` : '', mem ? gib(mem) : ''].filter(Boolean).join(', '));
      },
      defaultHidden: true,
    },
    {
      key: 'instance-type',
      header: 'Instance Type',
      render: (o) => nodeLabel(o, 'node.kubernetes.io/instance-type', 'beta.kubernetes.io/instance-type'),
      defaultHidden: true,
    },
    {
      key: 'zone',
      header: 'Zone',
      render: (o) => nodeLabel(o, 'topology.kubernetes.io/zone', 'failure-domain.beta.kubernetes.io/zone'),
      defaultHidden: true,
    },
    { key: 'os-image', header: 'OS Image', render: (o) => nodeInfo(o, 'osImage'), defaultHidden: true },
    { key: 'kernel', header: 'Kernel', render: (o) => nodeInfo(o, 'kernelVersion'), defaultHidden: true },
    {
      key: 'runtime',
      header: 'Container Runtime',
      render: (o) => nodeInfo(o, 'containerRuntimeVersion'),
      defaultHidden: true,
    },
    { key: 'arch', header: 'Architecture', render: (o) => nodeInfo(o, 'architecture'), defaultHidden: true },
  ],
  services: [
    { key: 'type', header: 'Type', render: (o) => dash(str(spec(o).type)) },
    { key: 'clusterip', header: 'Cluster IP', render: (o) => dash(str(spec(o).clusterIP)) },
    { key: 'ports', header: 'Ports', render: ports },
  ],
  ingresses: [
    { key: 'class', header: 'Class', render: (o) => dash(str(spec(o).ingressClassName)) },
    {
      key: 'hosts',
      header: 'Hosts',
      render: (o) =>
        dash(
          ((spec(o).rules as Any[]) ?? [])
            .map((r) => str((r as Any).host))
            .filter(Boolean)
            .join(', '),
        ),
    },
  ],
  configmaps: [{ key: 'keys', header: 'Keys', render: keys }],
  secrets: [
    { key: 'type', header: 'Type', render: (o) => dash(str(o.type)) },
    { key: 'keys', header: 'Keys', render: keys },
  ],
  namespaces: [{ key: 'status', header: 'Status', render: (o) => dash(str(status(o).phase)) }],
  persistentvolumeclaims: [
    { key: 'status', header: 'Status', render: (o) => dash(str(status(o).phase)) },
    { key: 'volume', header: 'Volume', render: (o) => dash(str(spec(o).volumeName)) },
    { key: 'capacity', header: 'Capacity', render: (o) => dash(str((status(o).capacity as Any)?.storage)) },
    { key: 'sc', header: 'Storage Class', render: (o) => dash(str(spec(o).storageClassName)) },
  ],
  persistentvolumes: [
    { key: 'capacity', header: 'Capacity', render: (o) => dash(str((spec(o).capacity as Any)?.storage)) },
    { key: 'status', header: 'Status', render: (o) => dash(str(status(o).phase)) },
    { key: 'claim', header: 'Claim', render: (o) => dash(str((spec(o).claimRef as Any)?.name)) },
    { key: 'sc', header: 'Storage Class', render: (o) => dash(str(spec(o).storageClassName)) },
  ],
  storageclasses: [
    { key: 'provisioner', header: 'Provisioner', render: (o) => dash(str(o.provisioner)) },
    { key: 'reclaim', header: 'Reclaim Policy', render: (o) => dash(str(o.reclaimPolicy)) },
  ],
  horizontalpodautoscalers: [
    { key: 'min', header: 'Min', render: (o) => String(num(spec(o).minReplicas)) },
    { key: 'max', header: 'Max', render: (o) => String(num(spec(o).maxReplicas)) },
    { key: 'replicas', header: 'Replicas', render: (o) => String(num(status(o).currentReplicas)) },
  ],
  poddisruptionbudgets: [
    { key: 'minavail', header: 'Min Available', render: (o) => dash(str(spec(o).minAvailable)) },
    { key: 'maxunavail', header: 'Max Unavailable', render: (o) => dash(str(spec(o).maxUnavailable)) },
    { key: 'current', header: 'Current Healthy', render: (o) => String(num(status(o).currentHealthy)) },
    { key: 'desired', header: 'Desired Healthy', render: (o) => String(num(status(o).desiredHealthy)) },
  ],
  priorityclasses: [
    { key: 'value', header: 'Value', render: (o) => (o.value === undefined ? '—' : String(o.value)) },
    { key: 'default', header: 'Global Default', render: (o) => (o.globalDefault ? 'Yes' : 'No') },
  ],
  runtimeclasses: [{ key: 'handler', header: 'Handler', render: (o) => dash(str(o.handler)) }],
  leases: [{ key: 'holder', header: 'Holder', render: (o) => dash(str(spec(o).holderIdentity)) }],
  ingressclasses: [{ key: 'controller', header: 'Controller', render: (o) => dash(str(spec(o).controller)) }],
  networkpolicies: [
    {
      key: 'ptypes',
      header: 'Policy Types',
      render: (o) => dash(((spec(o).policyTypes as unknown[]) ?? []).map(String).join(', ')),
    },
  ],
  endpointslices: [
    { key: 'atype', header: 'Address Type', render: (o) => dash(str(o.addressType)) },
    { key: 'eps', header: 'Endpoints', render: (o) => String(((o.endpoints as unknown[]) ?? []).length) },
  ],
  mutatingwebhookconfigurations: [
    { key: 'wh', header: 'Webhooks', render: (o) => String(((o.webhooks as unknown[]) ?? []).length) },
  ],
  validatingwebhookconfigurations: [
    { key: 'wh', header: 'Webhooks', render: (o) => String(((o.webhooks as unknown[]) ?? []).length) },
  ],
  customresourcedefinitions: [
    { key: 'resource', header: 'Resource', render: (o) => dash(str((spec(o).names as Any)?.kind)) },
    { key: 'group', header: 'Group', render: (o) => dash(str(spec(o).group)) },
    {
      key: 'version',
      header: 'Version',
      render: (o) => {
        const vs = (spec(o).versions as Any[]) ?? [];
        const storage = vs.find((v) => (v as Any).storage) ?? vs[0];
        return dash(str((storage as Any)?.name));
      },
    },
    { key: 'scope', header: 'Scope', render: (o) => dash(str(spec(o).scope)) },
    {
      key: 'short',
      header: 'Short Names',
      render: (o) => dash((((spec(o).names as Any)?.shortNames as unknown[]) ?? []).map(String).join(', ')),
    },
  ],
  events: [
    { key: 'type', header: 'Type', render: (o) => dash(str(o.type)) },
    { key: 'reason', header: 'Reason', render: (o) => dash(str(o.reason)) },
    { key: 'object', header: 'Object', render: involvedObject },
    { key: 'message', header: 'Message', render: (o) => dash(str(o.message)) },
    { key: 'count', header: 'Count', render: (o) => str(o.count) || '—', numeric: true, width: 90 },
  ],
};

export function columnsFor(resourceId: string): ColumnDef[] {
  return COLUMNS[resourceId] ?? [];
}

function getDotted(o: unknown, path: string): unknown {
  if (!path) {
    return o;
  }
  let cur: unknown = o;
  for (const part of path.split('.')) {
    if (cur === null || typeof cur !== 'object') {
      return undefined;
    }
    cur = (cur as Record<string, unknown>)[part];
  }
  return cur;
}

// Resolve a jsonPath into an object. Supports simple dotted paths (".status.phase") and the
// very common single equality filter (e.g. ".status.conditions[?(@.type == \"Ready\")].status");
// other complex JSONPath yields undefined.
function resolvePath(o: KubeObject, jsonPath: string): unknown {
  const p = (jsonPath || '').replace(/^\./, '');
  if (!p) {
    return undefined;
  }
  const m = p.match(/^(.*?)\[\?\(@\.([\w.]+)\s*==\s*["']([^"']+)["']\)\]\.?(.*)$/);
  if (m) {
    const [, prefix, field, val, rest] = m;
    const arr = getDotted(o, prefix);
    if (!Array.isArray(arr)) {
      return undefined;
    }
    const item = arr.find((e) => getDotted(e, field) === val);
    return item === undefined ? undefined : getDotted(item, rest);
  }
  return getDotted(o, p);
}

// Build ColumnDefs from a CRD's additionalPrinterColumns.
export function printerColumnDefs(cols: PrinterColumn[]): ColumnDef[] {
  return cols.map((c) => ({
    key: c.jsonPath || c.name,
    header: c.name,
    render: (o: KubeObject) => {
      const v = resolvePath(o, c.jsonPath);
      if (v === undefined || v === null) {
        return '—';
      }
      if (c.type === 'date') {
        return age(String(v));
      }
      if (typeof v === 'boolean') {
        return v ? 'True' : 'False';
      }
      return String(v);
    },
  }));
}

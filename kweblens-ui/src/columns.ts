import type { KubeObject } from './types';

// A kind-specific column: the "middle" columns between Name/Namespace and Age
// (those three are rendered by the table framework). render() returns display text.
export interface ColumnDef {
  key: string;
  header: string;
  render: (o: KubeObject) => string;
}

// ---- small accessors (defensive: cluster objects vary) ----
type Any = Record<string, unknown>;
const spec = (o: KubeObject): Any => (o.spec as Any) ?? {};
const status = (o: KubeObject): Any => (o.status as Any) ?? {};
const num = (v: unknown): number => (typeof v === 'number' ? v : 0);
const str = (v: unknown): string => (v === undefined || v === null ? '' : String(v));
const dash = (s: string): string => (s === '' ? '—' : s);

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
function nodeInternalIp(o: KubeObject): string {
  const addrs = (status(o).addresses as Any[]) ?? [];
  const ip = addrs.find((a) => (a as Any).type === 'InternalIP');
  return ip ? str((ip as Any).address) : '—';
}
function keys(o: KubeObject): string {
  const data = (o.data as Any) ?? {};
  return String(Object.keys(data).length);
}
function involvedObject(o: KubeObject): string {
  const io = (o.involvedObject as Any) ?? {};
  return dash([str(io.kind), str(io.name)].filter(Boolean).join('/'));
}

// resourceId -> the kind-specific middle columns
export const COLUMNS: Record<string, ColumnDef[]> = {
  pods: [
    { key: 'ready', header: 'Ready', render: podReady },
    { key: 'status', header: 'Status', render: (o) => dash(str(status(o).phase)) },
    { key: 'restarts', header: 'Restarts', render: podRestarts },
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
    { key: 'completions', header: 'Completions', render: (o) => `${num(status(o).succeeded)}/${num(spec(o).completions)}` },
    { key: 'status', header: 'Status', render: (o) => (num(status(o).succeeded) > 0 ? 'Complete' : dash(str(status(o).active ? 'Running' : ''))) },
  ],
  cronjobs: [
    { key: 'schedule', header: 'Schedule', render: (o) => dash(str(spec(o).schedule)) },
    { key: 'suspend', header: 'Suspend', render: (o) => (spec(o).suspend ? 'Yes' : 'No') },
    { key: 'active', header: 'Active', render: (o) => String(((status(o).active as Any[]) ?? []).length) },
    { key: 'last', header: 'Last schedule', render: (o) => age(str(status(o).lastScheduleTime) || undefined) },
  ],
  nodes: [
    { key: 'status', header: 'Status', render: nodeReady },
    { key: 'roles', header: 'Roles', render: nodeRoles },
    { key: 'version', header: 'Version', render: (o) => dash(str((status(o).nodeInfo as Any)?.kubeletVersion)) },
    { key: 'ip', header: 'Internal IP', render: nodeInternalIp },
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
      render: (o) => dash(((spec(o).rules as Any[]) ?? []).map((r) => str((r as Any).host)).filter(Boolean).join(', ')),
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
  events: [
    { key: 'type', header: 'Type', render: (o) => dash(str(o.type)) },
    { key: 'reason', header: 'Reason', render: (o) => dash(str(o.reason)) },
    { key: 'object', header: 'Object', render: involvedObject },
    { key: 'message', header: 'Message', render: (o) => dash(str(o.message)) },
    { key: 'count', header: 'Count', render: (o) => str(o.count) || '—' },
  ],
};

export function columnsFor(resourceId: string): ColumnDef[] {
  return COLUMNS[resourceId] ?? [];
}

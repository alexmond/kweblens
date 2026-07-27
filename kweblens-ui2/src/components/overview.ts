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

// ---- Field descriptors (a key/value row's rendered value) ----
type OvValue =
  | { kind: 'text'; text: string }
  | { kind: 'nav'; text: string; navKind: string; navNs?: string }
  | { kind: 'helm'; rel: string; rns: string }
  | { kind: 'owners'; owners: { kind: string; name: string; ns?: string }[] };

// A summary key/value row. `get` returns null to hide the row.
export interface OverviewField {
  label: string;
  mono?: boolean;
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
  { label: 'Secret Type', mono: true, get: (o) => ((o.type as string) ? text(o.type as string) : null) },
  {
    label: 'Created',
    get: (o) => {
      const t = ovMeta(o).creationTimestamp;
      return text(age(t) + (t ? ` · ${t}` : ''));
    },
  },
  {
    label: 'Managed By',
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

type OvBody =
  | { type: 'chips'; map: Record<string, string> }
  | { type: 'annotations'; map: Record<string, string> }
  | { type: 'secret'; data: Record<string, string> }
  | { type: 'kv'; pairs: [string, string][] }
  | { type: 'table'; headers: string[]; rows: OvCell[][]; tls?: string[] };

// A collapsible section. `applies` decides visibility; `body` renders its content.
export interface OverviewSection {
  title: string;
  defaultOpen?: boolean;
  count?: (o: KubeObject) => number;
  applies: (o: KubeObject) => boolean;
  body: (o: KubeObject) => OvBody;
}

const plain = (s: string): OvCell => ({ text: s });
const mono = (s: string): OvCell => ({ text: s, mono: true });

// The service/workload selector (Service is flat; workloads nest under matchLabels).
function ovSelectorEntries(o: KubeObject): [string, string][] {
  const raw = ovSpec(o).selector as Record<string, unknown> | undefined;
  const sel =
    (raw?.matchLabels as Record<string, string> | undefined) ?? (raw as Record<string, string> | undefined) ?? {};
  return Object.entries(sel).filter(([, v]) => typeof v === 'string') as [string, string][];
}

function containersBody(o: KubeObject): OvBody {
  const containers = ovArr(ovSpec(o).containers);
  const cs = ovArr(ovStatus(o).containerStatuses);
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
    return [
      plain(cn),
      mono(String(cc.image ?? '')),
      plain(ports(cc) || '—'),
      plain(resources(cc)),
      plain(st?.ready ? 'Yes' : 'No'),
      plain(String(Number(st?.restartCount ?? 0))),
    ];
  });
  return { type: 'table', headers: ['Name', 'Image', 'Ports', 'Requests', 'Ready', 'Restarts'], rows };
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

function configMapDataBody(o: KubeObject): OvBody {
  const rows = Object.entries(ovMap(o.data)).map(([k, v]) => [
    mono(k),
    mono(v.length > 200 ? v.slice(0, 200) + '…' : v),
  ]);
  return { type: 'table', headers: ['Key', 'Value'], rows };
}

function tolerationsBody(o: KubeObject): OvBody {
  const rows = ovArr(ovSpec(o).tolerations).map((t) => [
    mono(String(t.key ?? '*')),
    plain(String(t.operator ?? '')),
    plain(String(t.value ?? '—')),
    plain(String(t.effect ?? 'All')),
  ]);
  return { type: 'table', headers: ['Key', 'Operator', 'Value', 'Effect'], rows };
}

function volumesBody(o: KubeObject): OvBody {
  const rows = ovArr(ovSpec(o).volumes).map((v) => {
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

export const OVERVIEW_SECTIONS: OverviewSection[] = [
  {
    title: 'Labels',
    defaultOpen: false,
    applies: (o) => Object.keys(ovMeta(o).labels ?? {}).length > 0,
    count: (o) => Object.keys(ovMeta(o).labels ?? {}).length,
    body: (o) => ({ type: 'chips', map: ovMeta(o).labels ?? {} }),
  },
  {
    title: 'Annotations',
    defaultOpen: false,
    applies: (o) => Object.keys(ovMeta(o).annotations ?? {}).length > 0,
    count: (o) => Object.keys(ovMeta(o).annotations ?? {}).length,
    body: (o) => ({ type: 'annotations', map: ovMeta(o).annotations ?? {} }),
  },
  {
    title: 'Containers',
    applies: (o) => ovArr(ovSpec(o).containers).length > 0,
    count: (o) => ovArr(ovSpec(o).containers).length,
    body: containersBody,
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
    applies: (o) => o.kind === 'ConfigMap' && Object.keys(ovMap(o.data)).length > 0,
    count: (o) => Object.keys(ovMap(o.data)).length,
    body: configMapDataBody,
  },
  {
    title: 'Data',
    applies: (o) => o.kind === 'Secret' && Object.keys(ovMap(o.data)).length > 0,
    count: (o) => Object.keys(ovMap(o.data)).length,
    body: (o) => ({ type: 'secret', data: ovMap(o.data) }),
  },
  {
    title: 'Node Selector',
    defaultOpen: false,
    applies: (o) => Object.keys(ovMap(ovSpec(o).nodeSelector)).length > 0,
    count: (o) => Object.keys(ovMap(ovSpec(o).nodeSelector)).length,
    body: (o) => ({ type: 'chips', map: ovMap(ovSpec(o).nodeSelector) }),
  },
  {
    title: 'Tolerations',
    defaultOpen: false,
    applies: (o) => ovArr(ovSpec(o).tolerations).length > 0,
    count: (o) => ovArr(ovSpec(o).tolerations).length,
    body: tolerationsBody,
  },
  {
    title: 'Volumes',
    defaultOpen: false,
    applies: (o) => ovArr(ovSpec(o).volumes).length > 0,
    count: (o) => ovArr(ovSpec(o).volumes).length,
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

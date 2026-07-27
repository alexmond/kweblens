import type { ReactNode } from 'react';
import { Fragment, useEffect, useRef, useState } from 'react';

import { ApiError, api } from './api';
import { age } from './columns';
import { useEscapeKey, useTableSort } from './hooks';
import { ageToSeconds, objName, objNs, objSpec, objStatus, stripManagedFields } from './kube';
import { Accordion, MetricChart, SecretData, SortTh, YamlView, chipsOf } from './ui';
import type { EventSummary, KubeObject } from './types';

/** The drawer's YAML tab: lazy-loads the manifest, view/edit + server-side apply. */
function YamlTab(props: {
  cluster: string;
  resourceId: string;
  name: string;
  ns: string;
  initialEdit: boolean;
  authed: boolean;
  onAuthExpired: () => void;
}) {
  const { cluster, resourceId, name, ns, initialEdit, authed, onAuthExpired } = props;
  const [yaml, setYaml] = useState<string | null>(null);
  const [yamlError, setYamlError] = useState<string | null>(null);
  const [hideManaged, setHideManaged] = useState(true);
  const [copied, setCopied] = useState(false);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [msgErr, setMsgErr] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api
      .yaml(cluster, resourceId, name, ns || undefined)
      .then((t) => !cancelled && setYaml(t))
      .catch((e) => !cancelled && setYamlError(String(e)));
    return () => {
      cancelled = true;
    };
  }, [cluster, resourceId, name, ns]);

  const displayYaml = yaml === null ? null : hideManaged ? stripManagedFields(yaml) : yaml;

  // Opened via the row "Edit" action → drop straight into edit once the manifest loads.
  const autoEditDone = useRef(false);
  useEffect(() => {
    if (initialEdit && !autoEditDone.current && yaml !== null && !yamlError) {
      autoEditDone.current = true;
      setDraft(displayYaml ?? '');
      setEditing(true);
    }
  }, [initialEdit, yaml, yamlError, displayYaml]);

  const copy = () => {
    if (displayYaml) {
      navigator.clipboard?.writeText(displayYaml).then(
        () => {
          setCopied(true);
          window.setTimeout(() => setCopied(false), 1200);
        },
        () => undefined,
      );
    }
  };

  const applyYaml = async () => {
    setBusy(true);
    setMsg(null);
    setMsgErr(false);
    try {
      const r = await api.apply(cluster, draft);
      setYaml(draft);
      setEditing(false);
      setMsg(`applied ${r.kind}/${r.name}`);
    } catch (e) {
      setMsgErr(true);
      if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
        onAuthExpired();
        setMsg('Authentication failed — sign in again.');
      } else {
        setMsg(String(e));
      }
    } finally {
      setBusy(false);
    }
  };

  const toolbar = editing ? (
    <>
      <button className="btn primary" disabled={busy} onClick={applyYaml}>
        Apply
      </button>
      <button className="btn" disabled={busy} onClick={() => setEditing(false)}>
        Cancel
      </button>
    </>
  ) : (
    <>
      <button className="btn" onClick={copy} disabled={!yaml}>
        {copied ? 'Copied' : 'Copy'}
      </button>
      {authed && (
        <button
          className="btn"
          disabled={!yaml}
          onClick={() => {
            setDraft(displayYaml ?? '');
            setEditing(true);
          }}
        >
          Edit
        </button>
      )}
      <label className="yaml-toggle" title="Hide the verbose metadata.managedFields block">
        <input type="checkbox" checked={hideManaged} onChange={(e) => setHideManaged(e.target.checked)} />
        <span className="switch" />
        Hide Managed Fields
      </label>
    </>
  );

  return (
    <div className="yaml-pane">
      <div className="yaml-toolbar">{toolbar}</div>
      {yamlError && <div className="error">{yamlError}</div>}
      {!yamlError && yaml === null && <div className="empty">Loading…</div>}
      {displayYaml !== null && !editing && <YamlView text={displayYaml} />}
      {editing && (
        <textarea className="yaml-edit" value={draft} spellCheck={false} onChange={(e) => setDraft(e.target.value)} />
      )}
      {msg && <div className={'act-msg' + (msgErr ? ' err' : '')}>{msg}</div>}
    </div>
  );
}

export function Detail(props: {
  cluster: string;
  resourceId: string;
  obj: KubeObject;
  initialEdit: boolean;
  authed: boolean;
  onAuthExpired: () => void;
  onNavigate: (kind: string, ns?: string) => void;
  onHelmRelease: (namespace: string, name: string) => void;
  onClose: () => void;
}) {
  const { cluster, resourceId, obj, initialEdit, authed, onAuthExpired, onNavigate, onHelmRelease, onClose } = props;
  const [tab, setTab] = useState<'overview' | 'yaml' | 'events' | 'metrics'>(initialEdit ? 'yaml' : 'overview');
  const [events, setEvents] = useState<EventSummary[] | null>(null);
  const [eventsError, setEventsError] = useState<string | null>(null);

  const kind = obj.kind ?? '';
  const name = objName(obj);
  const ns = objNs(obj) ?? '';

  useEscapeKey(onClose);

  useEffect(() => {
    if (tab !== 'events' || events !== null || eventsError !== null) {
      return;
    }
    let cancelled = false;
    api
      .objectEvents(cluster, kind, name, ns || undefined)
      .then((e) => {
        if (!cancelled) {
          setEvents(e);
        }
      })
      .catch((e) => {
        if (!cancelled) {
          setEventsError(String(e));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [tab, events, eventsError, cluster, kind, name, ns]);

  return (
    <div className="drawer" role="dialog" aria-label={`${kind} ${name}`}>
      <div className="drawer-head">
        <div className="drawer-title">
          <span className="drawer-kind">{kind}</span>
          <span className="drawer-name">{name}</span>
        </div>
        <button className="drawer-close" title="Close (Esc)" onClick={onClose}>
          ×
        </button>
      </div>
      <div className="tabs">
        <button className={'tab' + (tab === 'overview' ? ' active' : '')} onClick={() => setTab('overview')}>
          Overview
        </button>
        <button className={'tab' + (tab === 'yaml' ? ' active' : '')} onClick={() => setTab('yaml')}>
          YAML
        </button>
        <button className={'tab' + (tab === 'events' ? ' active' : '')} onClick={() => setTab('events')}>
          Events
        </button>
        {kind === 'Pod' && (
          <button className={'tab' + (tab === 'metrics' ? ' active' : '')} onClick={() => setTab('metrics')}>
            Metrics
          </button>
        )}
      </div>
      <div className="drawer-body">
        {tab === 'overview' && <Overview obj={obj} onNavigate={onNavigate} onHelmRelease={onHelmRelease} />}
        {tab === 'events' && <EventsPane events={events} error={eventsError} />}
        {tab === 'metrics' && (
          <div className="charts vertical">
            <MetricChart cluster={cluster} target="pod-cpu" namespace={ns} name={name} label="CPU (cores)" />
            <MetricChart cluster={cluster} target="pod-mem" namespace={ns} name={name} label="Memory" />
          </div>
        )}
        {tab === 'yaml' && (
          <YamlTab
            cluster={cluster}
            resourceId={resourceId}
            name={name}
            ns={ns}
            initialEdit={initialEdit}
            authed={authed}
            onAuthExpired={onAuthExpired}
          />
        )}
      </div>
    </div>
  );
}

// ---- Detail drawer Overview: declarative field + section registries ----
// Adding a summary row or a whole section is one entry below; Overview just maps over
// them. Rows/sections are presence-driven — a `get` that returns null (or an `applies`
// that returns false) simply omits it, so a new kind's data shows up automatically.
type OverviewCtx = {
  obj: KubeObject;
  onNavigate: (kind: string, ns?: string) => void;
  onHelmRelease: (namespace: string, name: string) => void;
};

const ovMeta = (o: KubeObject): NonNullable<KubeObject['metadata']> => o.metadata ?? {};
const ovSpec = objSpec;
const ovStatus = objStatus;
const ovArr = (v: unknown): Record<string, unknown>[] => (Array.isArray(v) ? (v as Record<string, unknown>[]) : []);
const ovMap = (v: unknown): Record<string, string> => (v && typeof v === 'object' ? (v as Record<string, string>) : {});

// A summary key/value row. `get` returns null/'' to hide the row.
type OverviewField = { label: string; get: (c: OverviewCtx) => ReactNode | null; mono?: boolean };

const OVERVIEW_FIELDS: OverviewField[] = [
  { label: 'Kind', get: (c) => c.obj.kind ?? '—' },
  { label: 'Namespace', get: (c) => ovMeta(c.obj).namespace ?? null },
  { label: 'Name', get: (c) => ovMeta(c.obj).name ?? '—' },
  {
    label: 'Status',
    get: (c) => (typeof ovStatus(c.obj).phase === 'string' ? (ovStatus(c.obj).phase as string) : null),
  },
  {
    label: 'Node',
    get: (c) => {
      const n = ovSpec(c.obj).nodeName as string | undefined;
      return n ? (
        <button className="cell-link" onClick={() => c.onNavigate('Node')}>
          {n}
        </button>
      ) : null;
    },
  },
  { label: 'Pod IP', mono: true, get: (c) => (ovStatus(c.obj).podIP as string) || null },
  { label: 'Host IP', mono: true, get: (c) => (ovStatus(c.obj).hostIP as string) || null },
  { label: 'QoS Class', get: (c) => (ovStatus(c.obj).qosClass as string) || null },
  { label: 'Type', get: (c) => (ovSpec(c.obj).type as string) || null },
  { label: 'Cluster IP', mono: true, get: (c) => (ovSpec(c.obj).clusterIP as string) || null },
  {
    label: 'Internal IP',
    mono: true,
    get: (c) => {
      const addrs = (ovStatus(c.obj).addresses as { type: string; address: string }[] | undefined) ?? [];
      return addrs.find((a) => a.type === 'InternalIP')?.address || null;
    },
  },
  {
    label: 'Kubelet',
    get: (c) => (ovStatus(c.obj).nodeInfo as Record<string, string> | undefined)?.kubeletVersion || null,
  },
  { label: 'Secret Type', mono: true, get: (c) => (c.obj.type as string) || null },
  {
    label: 'Created',
    get: (c) => {
      const t = ovMeta(c.obj).creationTimestamp;
      return (
        <>
          {age(t)}
          {t ? ` · ${t}` : ''}
        </>
      );
    },
  },
  {
    label: 'Managed By',
    get: (c) => {
      const a = ovMeta(c.obj).annotations ?? {};
      const rel = a['meta.helm.sh/release-name'];
      if (!rel) {
        return null;
      }
      const rns = a['meta.helm.sh/release-namespace'] ?? ovMeta(c.obj).namespace ?? '';
      return (
        <>
          <span className="helm-badge">Helm</span>{' '}
          <button className="cell-link" title="Open this release's resources" onClick={() => c.onHelmRelease(rns, rel)}>
            {rel}
            {rns ? ` (${rns})` : ''}
          </button>
        </>
      );
    },
  },
  {
    label: 'Controlled By',
    get: (c) => {
      const owners = ovMeta(c.obj).ownerReferences ?? [];
      if (owners.length === 0) {
        return null;
      }
      return owners.map((o, i) => (
        <span key={o.kind + '/' + o.name}>
          {i > 0 ? ', ' : ''}
          <button className="cell-link" onClick={() => c.onNavigate(o.kind, ovMeta(c.obj).namespace)}>
            {o.kind}/{o.name}
          </button>
        </span>
      ));
    },
  },
];

// A collapsible section. `applies` decides visibility; `body` renders its content.
type OverviewSection = {
  title: string;
  defaultOpen?: boolean;
  count?: (o: KubeObject) => number;
  applies: (o: KubeObject) => boolean;
  body: (c: OverviewCtx) => ReactNode;
};

// The service/workload selector (Service is flat; workloads nest under matchLabels).
function ovSelectorEntries(o: KubeObject): [string, string][] {
  const raw = ovSpec(o).selector as Record<string, unknown> | undefined;
  const sel =
    (raw?.matchLabels as Record<string, string> | undefined) ?? (raw as Record<string, string> | undefined) ?? {};
  return Object.entries(sel).filter(([, v]) => typeof v === 'string') as [string, string][];
}

const OVERVIEW_SECTIONS: OverviewSection[] = [
  {
    title: 'Labels',
    defaultOpen: false,
    applies: (o) => Object.keys(ovMeta(o).labels ?? {}).length > 0,
    count: (o) => Object.keys(ovMeta(o).labels ?? {}).length,
    body: (c) => chipsOf(ovMeta(c.obj).labels ?? {}),
  },
  {
    title: 'Annotations',
    defaultOpen: false,
    applies: (o) => Object.keys(ovMeta(o).annotations ?? {}).length > 0,
    count: (o) => Object.keys(ovMeta(o).annotations ?? {}).length,
    body: (c) => (
      <div className="chips">
        {Object.entries(ovMeta(c.obj).annotations ?? {}).map(([k, v]) => (
          <span className="chip subtle" key={k} title={`${k}=${v}`}>
            {k}={v.length > 48 ? v.slice(0, 48) + '…' : v}
          </span>
        ))}
      </div>
    ),
  },
  {
    title: 'Containers',
    applies: (o) => ovArr(ovSpec(o).containers).length > 0,
    count: (o) => ovArr(ovSpec(o).containers).length,
    body: (c) => {
      const containers = ovArr(ovSpec(c.obj).containers);
      const cs = ovArr(ovStatus(c.obj).containerStatuses);
      const statusFor = (n: string) => cs.find((s) => s.name === n);
      const ports = (cc: Record<string, unknown>) =>
        ovArr(cc.ports)
          .map((p) => `${p.containerPort}${p.protocol && p.protocol !== 'TCP' ? '/' + p.protocol : ''}`)
          .join(', ');
      const resources = (cc: Record<string, unknown>) => {
        const req = (cc.resources as Record<string, unknown> | undefined)?.requests as
          Record<string, string> | undefined;
        if (!req) {
          return '—';
        }
        return [req.cpu && `cpu ${req.cpu}`, req.memory && `mem ${req.memory}`].filter(Boolean).join(', ') || '—';
      };
      return (
        <table className="mini">
          <thead>
            <tr>
              <th>Name</th>
              <th>Image</th>
              <th>Ports</th>
              <th>Requests</th>
              <th>Ready</th>
              <th>Restarts</th>
            </tr>
          </thead>
          <tbody>
            {containers.map((cc) => {
              const cn = String(cc.name ?? '');
              return (
                <tr key={cn}>
                  <td>{cn}</td>
                  <td className="mono">{String(cc.image ?? '')}</td>
                  <td>{ports(cc) || '—'}</td>
                  <td>{resources(cc)}</td>
                  <td>{statusFor(cn)?.ready ? 'Yes' : 'No'}</td>
                  <td>{Number(statusFor(cn)?.restartCount ?? 0)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      );
    },
  },
  {
    title: 'Ports',
    applies: (o) => ovArr(ovSpec(o).ports).length > 0,
    count: (o) => ovArr(ovSpec(o).ports).length,
    body: (c) => (
      <table className="mini">
        <thead>
          <tr>
            <th>Name</th>
            <th>Port</th>
            <th>Target</th>
            <th>Protocol</th>
            <th>Node Port</th>
          </tr>
        </thead>
        <tbody>
          {ovArr(ovSpec(c.obj).ports).map((p, i) => (
            <tr key={String(p.name ?? i)}>
              <td>{String(p.name ?? '—')}</td>
              <td>{String(p.port ?? '')}</td>
              <td>{String(p.targetPort ?? '')}</td>
              <td>{String(p.protocol ?? 'TCP')}</td>
              <td>{String(p.nodePort ?? '—')}</td>
            </tr>
          ))}
        </tbody>
      </table>
    ),
  },
  {
    title: 'Selector',
    applies: (o) => ovSelectorEntries(o).length > 0,
    count: (o) => ovSelectorEntries(o).length,
    body: (c) => chipsOf(Object.fromEntries(ovSelectorEntries(c.obj))),
  },
  {
    title: 'Rules',
    applies: (o) => ovArr(ovSpec(o).rules).length > 0,
    count: (o) => ovArr(ovSpec(o).rules).length,
    body: (c) => {
      const rules = ovArr(ovSpec(c.obj).rules);
      const tls = ovArr(ovSpec(c.obj).tls);
      return (
        <>
          <table className="mini">
            <thead>
              <tr>
                <th>Host</th>
                <th>Path</th>
                <th>Backend</th>
              </tr>
            </thead>
            <tbody>
              {rules.flatMap((r, ri) => {
                const host = String(r.host ?? '*');
                const paths = ovArr((r.http as Record<string, unknown>)?.paths);
                if (paths.length === 0) {
                  return [
                    <tr key={ri}>
                      <td>{host}</td>
                      <td>—</td>
                      <td>—</td>
                    </tr>,
                  ];
                }
                return paths.map((p, pi) => {
                  const svc = (p.backend as Record<string, unknown>)?.service as Record<string, unknown> | undefined;
                  const port = (svc?.port as Record<string, unknown> | undefined)?.number;
                  return (
                    <tr key={ri + '-' + pi}>
                      <td>{host}</td>
                      <td className="mono">{String(p.path ?? '/')}</td>
                      <td className="mono">{svc ? `${svc.name}${port ? ':' + port : ''}` : '—'}</td>
                    </tr>
                  );
                });
              })}
            </tbody>
          </table>
          {tls.length > 0 && (
            <div className="chips" style={{ marginTop: 8 }}>
              {tls.flatMap((t, i) =>
                ((t.hosts as string[] | undefined) ?? []).map((h) => (
                  <span className="chip" key={i + h}>
                    TLS: {h}
                  </span>
                )),
              )}
            </div>
          )}
        </>
      );
    },
  },
  {
    title: 'Data',
    applies: (o) => o.kind === 'ConfigMap' && Object.keys(ovMap(o.data)).length > 0,
    count: (o) => Object.keys(ovMap(o.data)).length,
    body: (c) => (
      <table className="mini">
        <thead>
          <tr>
            <th>Key</th>
            <th>Value</th>
          </tr>
        </thead>
        <tbody>
          {Object.entries(ovMap(c.obj.data)).map(([k, v]) => (
            <tr key={k}>
              <td className="mono">{k}</td>
              <td className="mono">{v.length > 200 ? v.slice(0, 200) + '…' : v}</td>
            </tr>
          ))}
        </tbody>
      </table>
    ),
  },
  {
    title: 'Data',
    applies: (o) => o.kind === 'Secret' && Object.keys(ovMap(o.data)).length > 0,
    count: (o) => Object.keys(ovMap(o.data)).length,
    body: (c) => <SecretData data={ovMap(c.obj.data)} />,
  },
  {
    title: 'Node Selector',
    defaultOpen: false,
    applies: (o) => Object.keys(ovMap(ovSpec(o).nodeSelector)).length > 0,
    count: (o) => Object.keys(ovMap(ovSpec(o).nodeSelector)).length,
    body: (c) => chipsOf(ovMap(ovSpec(c.obj).nodeSelector)),
  },
  {
    title: 'Tolerations',
    defaultOpen: false,
    applies: (o) => ovArr(ovSpec(o).tolerations).length > 0,
    count: (o) => ovArr(ovSpec(o).tolerations).length,
    body: (c) => (
      <table className="mini">
        <thead>
          <tr>
            <th>Key</th>
            <th>Operator</th>
            <th>Value</th>
            <th>Effect</th>
          </tr>
        </thead>
        <tbody>
          {ovArr(ovSpec(c.obj).tolerations).map((t, i) => (
            <tr key={String(t.key ?? i)}>
              <td className="mono">{String(t.key ?? '*')}</td>
              <td>{String(t.operator ?? '')}</td>
              <td>{String(t.value ?? '—')}</td>
              <td>{String(t.effect ?? 'All')}</td>
            </tr>
          ))}
        </tbody>
      </table>
    ),
  },
  {
    title: 'Volumes',
    defaultOpen: false,
    applies: (o) => ovArr(ovSpec(o).volumes).length > 0,
    count: (o) => ovArr(ovSpec(o).volumes).length,
    body: (c) => (
      <table className="mini">
        <thead>
          <tr>
            <th>Name</th>
            <th>Type</th>
          </tr>
        </thead>
        <tbody>
          {ovArr(ovSpec(c.obj).volumes).map((v, i) => {
            const type = Object.keys(v).find((key) => key !== 'name') ?? '—';
            return (
              <tr key={String(v.name ?? i)}>
                <td>{String(v.name ?? '')}</td>
                <td className="mono">{type}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    ),
  },
  {
    title: 'Taints',
    applies: (o) => ovArr(ovSpec(o).taints).length > 0,
    count: (o) => ovArr(ovSpec(o).taints).length,
    body: (c) => (
      <table className="mini">
        <thead>
          <tr>
            <th>Key</th>
            <th>Value</th>
            <th>Effect</th>
          </tr>
        </thead>
        <tbody>
          {ovArr(ovSpec(c.obj).taints).map((t, i) => (
            <tr key={String(t.key ?? i)}>
              <td className="mono">{String(t.key ?? '')}</td>
              <td>{String(t.value ?? '—')}</td>
              <td>{String(t.effect ?? '')}</td>
            </tr>
          ))}
        </tbody>
      </table>
    ),
  },
  {
    title: 'Node Info',
    defaultOpen: false,
    applies: (o) => !!ovStatus(o).nodeInfo,
    body: (c) => {
      const nodeInfo = (ovStatus(c.obj).nodeInfo as Record<string, string> | undefined) ?? {};
      return (
        <dl className="kv">
          {(
            [
              ['OS Image', nodeInfo.osImage],
              ['Architecture', nodeInfo.architecture],
              ['Kernel', nodeInfo.kernelVersion],
              ['Container Runtime', nodeInfo.containerRuntimeVersion],
              ['Kube-Proxy', nodeInfo.kubeProxyVersion],
            ] as [string, string | undefined][]
          )
            .filter(([, v]) => v)
            .map(([k, v]) => (
              <Fragment key={k}>
                <dt>{k}</dt>
                <dd>{v}</dd>
              </Fragment>
            ))}
        </dl>
      );
    },
  },
  {
    title: 'Capacity',
    defaultOpen: false,
    applies: (o) => !!ovStatus(o).capacity || !!ovStatus(o).allocatable,
    body: (c) => {
      const capacity = ovMap(ovStatus(c.obj).capacity);
      const allocatable = ovMap(ovStatus(c.obj).allocatable);
      return (
        <table className="mini">
          <thead>
            <tr>
              <th>Resource</th>
              <th>Capacity</th>
              <th>Allocatable</th>
            </tr>
          </thead>
          <tbody>
            {Array.from(new Set([...Object.keys(capacity), ...Object.keys(allocatable)])).map((r) => (
              <tr key={r}>
                <td className="mono">{r}</td>
                <td>{capacity[r] ?? '—'}</td>
                <td>{allocatable[r] ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      );
    },
  },
  {
    title: 'Conditions',
    applies: (o) => ovArr(ovStatus(o).conditions).length > 0,
    count: (o) => ovArr(ovStatus(o).conditions).length,
    body: (c) => (
      <table className="mini">
        <thead>
          <tr>
            <th>Type</th>
            <th>Status</th>
            <th>Reason</th>
          </tr>
        </thead>
        <tbody>
          {ovArr(ovStatus(c.obj).conditions).map((cond, i) => (
            <tr key={String(cond.type ?? i)}>
              <td>{String(cond.type ?? '')}</td>
              <td>{String(cond.status ?? '')}</td>
              <td>{String(cond.reason ?? '')}</td>
            </tr>
          ))}
        </tbody>
      </table>
    ),
  },
];

// Renders the detail drawer's Overview tab purely by mapping the field + section
// registries above — no per-kind markup lives here.
function Overview(props: {
  obj: KubeObject;
  onNavigate: (kind: string, ns?: string) => void;
  onHelmRelease: (namespace: string, name: string) => void;
}) {
  const { obj, onNavigate, onHelmRelease } = props;
  const ctx: OverviewCtx = { obj, onNavigate, onHelmRelease };

  return (
    <div className="ov">
      <dl className="kv">
        {OVERVIEW_FIELDS.map((f) => {
          const value = f.get(ctx);
          if (value === null) {
            return null;
          }
          return (
            <Fragment key={f.label}>
              <dt>{f.label}</dt>
              <dd className={f.mono ? 'mono' : undefined}>{value}</dd>
            </Fragment>
          );
        })}
      </dl>
      {OVERVIEW_SECTIONS.filter((s) => s.applies(obj)).map((s, i) => (
        <Accordion key={s.title + i} title={s.title} count={s.count?.(obj)} defaultOpen={s.defaultOpen}>
          {s.body(ctx)}
        </Accordion>
      ))}
    </div>
  );
}

export function EventsPane(props: { events: EventSummary[] | null; error: string | null }) {
  const { events, error } = props;
  const { sorted, sort, clickHeader } = useTableSort(events ?? [], 'age', (e, k) =>
    k === 'age' ? ageToSeconds(e.age) : ((e[k as keyof EventSummary] as string) ?? ''),
  );
  if (error) {
    return <div className="error">{error}</div>;
  }
  if (events === null) {
    return <div className="empty">Loading…</div>;
  }
  if (events.length === 0) {
    return <div className="empty">No events.</div>;
  }
  return (
    <table className="mini">
      <thead>
        <tr>
          <SortTh label="Type" colKey="type" sort={sort} onClick={clickHeader} />
          <SortTh label="Reason" colKey="reason" sort={sort} onClick={clickHeader} />
          <SortTh label="Message" colKey="message" sort={sort} onClick={clickHeader} />
          <SortTh label="Age" colKey="age" sort={sort} onClick={clickHeader} />
        </tr>
      </thead>
      <tbody>
        {sorted.map((e, i) => (
          <tr key={i} className={e.type === 'Warning' ? 'warn' : ''}>
            <td>{e.type}</td>
            <td>{e.reason}</td>
            <td>{e.message}</td>
            <td>{e.age}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

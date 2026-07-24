import { useEffect, useMemo, useState } from 'react';

import { ApiError, api } from './api';
import { auth } from './auth';
import type { ColumnDef } from './columns';
import { age, columnsFor, printerColumnDefs } from './columns';
import type { ClusterInfo, EventSummary, KubeObject, NavCategory, NavItem } from './types';

function initials(id: string): string {
  return (id.length >= 2 ? id.slice(0, 2) : id).toUpperCase();
}

const objName = (o: KubeObject): string => o.metadata?.name ?? '';
const objNs = (o: KubeObject): string | undefined => o.metadata?.namespace;
const objKey = (o: KubeObject): string => (objNs(o) ?? '') + '/' + objName(o);

// Synthetic nav items (dashboards, not resource kinds) use this id prefix.
const OVERVIEW_PREFIX = 'overview:';

// Inject a "Overview" dashboard item at the top of the Workloads category.
function withWorkloadsOverview(cats: NavCategory[]): NavCategory[] {
  return cats.map((c) =>
    c.label === 'Workloads'
      ? { ...c, items: [{ id: OVERVIEW_PREFIX + 'workloads', label: 'Overview', kind: '', namespaced: false }, ...c.items] }
      : c,
  );
}

export function App() {
  const [clusters, setClusters] = useState<ClusterInfo[]>([]);
  const [cluster, setCluster] = useState<string | null>(null);
  const [nav, setNav] = useState<NavCategory[]>([]);
  const [namespaces, setNamespaces] = useState<string[]>([]);
  const [namespace, setNamespace] = useState<string | null>(null);
  const [selected, setSelected] = useState<NavItem | null>(null);
  const [objects, setObjects] = useState<KubeObject[]>([]);
  const [loading, setLoading] = useState(false);
  const [live, setLive] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [detail, setDetail] = useState<{ resourceId: string; obj: KubeObject } | null>(null);
  const [cols, setCols] = useState<ColumnDef[]>([]);
  const [query, setQuery] = useState('');
  const [authUser, setAuthUser] = useState<string | null>(null);
  const [showLogin, setShowLogin] = useState(false);

  useEffect(() => {
    api
      .clusters()
      .then((cs) => {
        setClusters(cs);
        setCluster((prev) => prev ?? cs[0]?.id ?? null);
      })
      .catch((e) => setError(String(e)));
  }, []);

  useEffect(() => {
    if (!cluster) {
      return;
    }
    setNav([]);
    setNamespaces([]);
    setNamespace(null);
    setSelected(null);
    setObjects([]);
    setError(null);
    api
      .nav(cluster)
      .then((cats) => setNav(withWorkloadsOverview(cats)))
      .catch((e) => setError(String(e)));
    api
      .namespaces(cluster)
      .then((ns) => setNamespaces(ns.map((r) => r.name).sort()))
      .catch(() => setNamespaces([]));
  }, [cluster]);

  // Fetch the selected kind's raw objects on kind/namespace change.
  useEffect(() => {
    if (!cluster || !selected || selected.id.startsWith(OVERVIEW_PREFIX)) {
      setObjects([]);
      return;
    }
    const ns = selected.namespaced ? namespace ?? undefined : undefined;
    let cancelled = false;
    setDetail(null);
    setQuery('');
    setLoading(true);
    setError(null);
    api
      .objects(cluster, selected.id, ns)
      .then((r) => {
        if (!cancelled) {
          setObjects(r);
        }
      })
      .catch((e) => {
        if (!cancelled) {
          setError(String(e));
          setObjects([]);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [cluster, selected, namespace]);

  // Columns for the selected kind: built-in registry, or the CRD's printer columns for
  // custom kinds (their id is "group.plural"), falling back to Name/Namespace/Age.
  useEffect(() => {
    if (!cluster || !selected || selected.id.startsWith(OVERVIEW_PREFIX)) {
      setCols([]);
      return;
    }
    if (selected.id.includes('.')) {
      let cancelled = false;
      api
        .printerColumns(cluster, selected.id)
        .then((pc) => {
          if (!cancelled) {
            setCols(pc.length ? printerColumnDefs(pc) : []);
          }
        })
        .catch(() => setCols([]));
      return () => {
        cancelled = true;
      };
    }
    setCols(columnsFor(selected.id));
    return undefined;
  }, [cluster, selected]);

  // Live object stream: patch the table in place.
  useEffect(() => {
    if (!cluster || !selected || selected.id.startsWith(OVERVIEW_PREFIX)) {
      return;
    }
    const ns = selected.namespaced ? namespace ?? undefined : undefined;
    const url =
      `/api/v1/clusters/${encodeURIComponent(cluster)}/resources/${encodeURIComponent(selected.id)}/objects/watch` +
      (ns ? `?namespace=${encodeURIComponent(ns)}` : '');
    const es = new EventSource(url);
    const upsert = (e: MessageEvent) => {
      const obj = JSON.parse(e.data) as KubeObject;
      setObjects((prev) => {
        const key = objKey(obj);
        const idx = prev.findIndex((o) => objKey(o) === key);
        if (idx === -1) {
          return [...prev, obj];
        }
        const next = prev.slice();
        next[idx] = obj;
        return next;
      });
    };
    const remove = (e: MessageEvent) => {
      const obj = JSON.parse(e.data) as KubeObject;
      setObjects((prev) => prev.filter((o) => objKey(o) !== objKey(obj)));
    };
    es.addEventListener('ADDED', upsert as EventListener);
    es.addEventListener('MODIFIED', upsert as EventListener);
    es.addEventListener('DELETED', remove as EventListener);
    es.onopen = () => setLive(true);
    es.onerror = () => setLive(false);
    return () => {
      setLive(false);
      es.close();
    };
  }, [cluster, selected, namespace]);

  const activeCluster = useMemo(() => clusters.find((c) => c.id === cluster) ?? null, [clusters, cluster]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) {
      return objects;
    }
    return objects.filter(
      (o) =>
        objName(o).toLowerCase().includes(q) ||
        (objNs(o) ?? '').toLowerCase().includes(q) ||
        (o.kind ?? '').toLowerCase().includes(q),
    );
  }, [objects, query]);

  return (
    <div className="app">
      <header className="brandbar">
        <div className="brand">
          <span className="logo">◆</span> kweblens
          <span className="tag">web Kubernetes IDE · SPA</span>
        </div>
        <div className="bar-right">
          {authUser ? (
            <span className="authbox">
              <i className="user-dot" /> {authUser}
              <button
                className="linkbtn"
                onClick={() => {
                  auth.clear();
                  setAuthUser(null);
                }}
              >
                Sign out
              </button>
            </span>
          ) : (
            <button className="linkbtn" onClick={() => setShowLogin(true)}>
              Sign in
            </button>
          )}
          <a className="switch" href="/">
            Classic UI ↗
          </a>
        </div>
      </header>

      <div className="body">
        <nav className="rail" aria-label="Clusters">
          {clusters.map((c) => (
            <button
              key={c.id}
              className={'tile' + (c.id === cluster ? ' active' : '')}
              title={c.name}
              onClick={() => setCluster(c.id)}
            >
              {initials(c.id)}
            </button>
          ))}
        </nav>

        <aside className="nav">
          <div className="nav-title">{activeCluster?.name ?? cluster ?? '—'}</div>
          {cluster && <NavTree categories={nav} selected={selected?.id ?? null} onSelect={setSelected} />}
        </aside>

        <main className="content">
          {error && <div className="error">{error}</div>}
          {!selected && !error && cluster && (
            <ClusterOverview
              cluster={cluster}
              name={activeCluster?.name ?? cluster}
              masterUrl={activeCluster?.masterUrl}
              namespaceCount={namespaces.length}
            />
          )}
          {cluster && selected?.id === OVERVIEW_PREFIX + 'workloads' && <WorkloadsOverview cluster={cluster} />}
          {selected && !selected.id.startsWith(OVERVIEW_PREFIX) && (
            <>
              <div className="content-head">
                <h1>{selected.label}</h1>
                <span className="count">
                  {query ? `${filtered.length} of ${objects.length}` : `${objects.length} items`}
                </span>
                {live && (
                  <span className="live" title="Live-updating (SSE watch)">
                    <span className="dot" /> live
                  </span>
                )}
                <input
                  className="search"
                  type="search"
                  placeholder={`Search ${selected.label}…`}
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                />
                <div className="spacer" />
                {selected.namespaced ? (
                  <label className="ns-select">
                    <span>Namespace</span>
                    <select value={namespace ?? ''} onChange={(e) => setNamespace(e.target.value || null)}>
                      <option value="">All namespaces</option>
                      {namespaces.map((n) => (
                        <option key={n} value={n}>
                          {n}
                        </option>
                      ))}
                    </select>
                  </label>
                ) : (
                  <span className="ns-note">Cluster-scoped</span>
                )}
              </div>
              <ResourceTable
                objects={filtered}
                columns={cols}
                namespaced={selected.namespaced}
                loading={loading}
                selectedKey={detail ? objKey(detail.obj) : null}
                onOpen={(obj) => setDetail({ resourceId: selected.id, obj })}
              />
            </>
          )}
        </main>

        {cluster && detail && (
          <Detail
            cluster={cluster}
            resourceId={detail.resourceId}
            obj={detail.obj}
            authed={authUser !== null}
            onRequireAuth={() => setShowLogin(true)}
            onAuthExpired={() => {
              auth.clear();
              setAuthUser(null);
            }}
            onClose={() => setDetail(null)}
          />
        )}
      </div>

      {showLogin && (
        <LoginModal
          onCancel={() => setShowLogin(false)}
          onSubmit={(user, pass) => {
            auth.set(user, pass);
            setAuthUser(user);
            setShowLogin(false);
          }}
        />
      )}
    </div>
  );
}

function NavTree(props: {
  categories: NavCategory[];
  selected: string | null;
  onSelect: (item: NavItem) => void;
}) {
  const { categories, selected, onSelect } = props;
  const [open, setOpen] = useState<Set<string>>(new Set());

  useEffect(() => {
    const holder = categories.find((c) => c.items.some((i) => i.id === selected));
    if (holder) {
      setOpen((prev) => (prev.has(holder.label) ? prev : new Set(prev).add(holder.label)));
    }
  }, [categories, selected]);

  const toggle = (label: string, isOpen: boolean) =>
    setOpen((prev) => {
      const next = new Set(prev);
      if (isOpen) {
        next.add(label);
      } else {
        next.delete(label);
      }
      return next;
    });

  return (
    <div className="tree">
      {categories.map((cat) => (
        <details
          key={cat.label}
          className="group"
          open={open.has(cat.label)}
          onToggle={(e) => toggle(cat.label, (e.currentTarget as HTMLDetailsElement).open)}
        >
          <summary>
            <span className="chev">▶</span>
            <span className="cat-label">{cat.label}</span>
          </summary>
          <ul>
            {cat.items.map((it) => (
              <li key={it.id}>
                <button
                  className={'leaf' + (it.id === selected ? ' active' : '')}
                  onClick={() => onSelect(it)}
                >
                  {it.label}
                </button>
              </li>
            ))}
          </ul>
        </details>
      ))}
    </div>
  );
}

function ResourceTable(props: {
  objects: KubeObject[];
  columns: ColumnDef[];
  namespaced: boolean;
  loading: boolean;
  selectedKey: string | null;
  onOpen: (obj: KubeObject) => void;
}) {
  const { objects, columns: cols, namespaced, loading, selectedKey, onOpen } = props;
  const [sort, setSort] = useState<{ key: string; dir: number }>({ key: 'name', dir: 1 });
  if (loading) {
    return <div className="empty">Loading…</div>;
  }
  if (objects.length === 0) {
    return <div className="empty">No resources.</div>;
  }
  const showNs = namespaced && objects.some((o) => objNs(o));
  // Some CRD printer columns already include an Age column; don't render ours twice.
  const showAge = !cols.some((c) => c.header.toLowerCase() === 'age');

  const headerCols: { key: string; header: string }[] = [
    { key: 'name', header: 'Name' },
    ...(showNs ? [{ key: 'namespace', header: 'Namespace' }] : []),
    ...cols.map((c) => ({ key: c.key, header: c.header })),
    ...(showAge ? [{ key: 'age', header: 'Age' }] : []),
  ];
  const textValue = (o: KubeObject, key: string): string => {
    if (key === 'name') {
      return objName(o);
    }
    if (key === 'namespace') {
      return objNs(o) ?? '';
    }
    const c = cols.find((x) => x.key === key);
    return c ? c.render(o) : '';
  };
  const sorted = [...objects].sort((a, b) => {
    if (sort.key === 'age') {
      const ta = Date.parse(a.metadata?.creationTimestamp ?? '') || 0;
      const tb = Date.parse(b.metadata?.creationTimestamp ?? '') || 0;
      return (ta - tb) * sort.dir;
    }
    return textValue(a, sort.key).localeCompare(textValue(b, sort.key), undefined, { numeric: true }) * sort.dir;
  });
  const clickHeader = (key: string) =>
    setSort((prev) => (prev.key === key ? { key, dir: -prev.dir } : { key, dir: 1 }));

  return (
    <table className="grid clickable">
      <thead>
        <tr>
          {headerCols.map((h) => (
            <th key={h.key} className="sortable" onClick={() => clickHeader(h.key)}>
              {h.header}
              {sort.key === h.key && <span className="sort-ind">{sort.dir === 1 ? ' ▲' : ' ▼'}</span>}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {sorted.map((o) => (
          <tr key={objKey(o)} className={objKey(o) === selectedKey ? 'row-active' : ''} onClick={() => onOpen(o)}>
            <td className="name">{objName(o)}</td>
            {showNs && <td>{objNs(o) ?? '—'}</td>}
            {cols.map((c) => (
              <td key={c.key}>{c.render(o)}</td>
            ))}
            {showAge && <td>{age(o.metadata?.creationTimestamp)}</td>}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

const SCALABLE = ['Deployment', 'StatefulSet', 'ReplicaSet'];
const RESTARTABLE = ['Deployment', 'StatefulSet', 'DaemonSet'];

function Detail(props: {
  cluster: string;
  resourceId: string;
  obj: KubeObject;
  authed: boolean;
  onRequireAuth: () => void;
  onAuthExpired: () => void;
  onClose: () => void;
}) {
  const { cluster, resourceId, obj, authed, onRequireAuth, onAuthExpired, onClose } = props;
  const [tab, setTab] = useState<'overview' | 'yaml' | 'events'>('overview');
  const [yaml, setYaml] = useState<string | null>(null);
  const [yamlError, setYamlError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [events, setEvents] = useState<EventSummary[] | null>(null);
  const [eventsError, setEventsError] = useState<string | null>(null);
  const [replicas, setReplicas] = useState(1);
  const [busy, setBusy] = useState(false);
  const [actionMsg, setActionMsg] = useState<string | null>(null);
  const [actionErr, setActionErr] = useState(false);

  const kind = obj.kind ?? '';
  const name = objName(obj);
  const ns = objNs(obj) ?? '';
  const isNode = kind === 'Node';

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  useEffect(() => {
    if (tab !== 'yaml' || yaml !== null || yamlError !== null) {
      return;
    }
    let cancelled = false;
    api
      .yaml(cluster, resourceId, name, ns || undefined)
      .then((t) => {
        if (!cancelled) {
          setYaml(t);
        }
      })
      .catch((e) => {
        if (!cancelled) {
          setYamlError(String(e));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [tab, yaml, yamlError, cluster, resourceId, name, ns]);

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

  const copy = () => {
    if (yaml) {
      navigator.clipboard?.writeText(yaml).then(
        () => {
          setCopied(true);
          window.setTimeout(() => setCopied(false), 1200);
        },
        () => undefined,
      );
    }
  };

  const act = async (fn: () => Promise<{ result: string }>, opts?: { confirm?: string; closeOnDone?: boolean }) => {
    if (opts?.confirm && !window.confirm(opts.confirm)) {
      return;
    }
    setBusy(true);
    setActionMsg(null);
    setActionErr(false);
    try {
      const r = await fn();
      setActionMsg(r.result);
      if (opts?.closeOnDone) {
        onClose();
      }
    } catch (e) {
      setActionErr(true);
      if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
        onAuthExpired();
        setActionMsg('Authentication failed — sign in again.');
      } else {
        setActionMsg(String(e));
      }
    } finally {
      setBusy(false);
    }
  };

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
      </div>
      <div className="drawer-body">
        {tab === 'overview' && <Overview obj={obj} />}
        {tab === 'events' && <EventsPane events={events} error={eventsError} />}
        {tab === 'yaml' && (
          <div className="yaml-pane">
            <div className="yaml-toolbar">
              <button className="btn" onClick={copy} disabled={!yaml}>
                {copied ? 'Copied' : 'Copy'}
              </button>
            </div>
            {yamlError && <div className="error">{yamlError}</div>}
            {!yamlError && yaml === null && <div className="empty">Loading…</div>}
            {yaml !== null && <pre className="yaml">{yaml}</pre>}
          </div>
        )}
      </div>

      <div className="drawer-actions">
        {!authed && (
          <button className="linkbtn strong" onClick={onRequireAuth}>
            Sign in to run actions
          </button>
        )}
        {authed && SCALABLE.includes(kind) && (
          <span className="act">
            <input
              type="number"
              min={0}
              className="repl"
              value={replicas}
              disabled={busy}
              onChange={(e) => setReplicas(Math.max(0, Number.parseInt(e.target.value || '0', 10)))}
            />
            <button className="btn" disabled={busy} onClick={() => act(() => api.scale(cluster, resourceId, ns, name, replicas))}>
              Scale
            </button>
          </span>
        )}
        {authed && RESTARTABLE.includes(kind) && (
          <button
            className="btn"
            disabled={busy}
            onClick={() => act(() => api.restart(cluster, resourceId, ns, name), { confirm: `Rolling-restart ${name}?` })}
          >
            Restart
          </button>
        )}
        {authed && isNode && (
          <>
            <button className="btn" disabled={busy} onClick={() => act(() => api.cordon(cluster, name), { confirm: `Cordon ${name}?` })}>
              Cordon
            </button>
            <button className="btn" disabled={busy} onClick={() => act(() => api.uncordon(cluster, name))}>
              Uncordon
            </button>
          </>
        )}
        {authed && !isNode && ns && (
          <button
            className="btn danger"
            disabled={busy}
            onClick={() =>
              act(() => api.del(cluster, resourceId, ns, name), {
                confirm: `Delete ${kind} ${name}? This cannot be undone.`,
                closeOnDone: true,
              })
            }
          >
            Delete
          </button>
        )}
        {actionMsg && <span className={'act-msg' + (actionErr ? ' err' : '')}>{actionMsg}</span>}
      </div>
    </div>
  );
}

function Overview(props: { obj: KubeObject }) {
  const { obj } = props;
  const md = obj.metadata ?? {};
  const spec = (obj.spec as Record<string, unknown>) ?? {};
  const status = (obj.status as Record<string, unknown>) ?? {};
  const labels = md.labels ?? {};
  const annotations = md.annotations ?? {};
  const owners = md.ownerReferences ?? [];
  const conditions = (status.conditions as Record<string, unknown>[]) ?? [];
  const containers = (spec.containers as Record<string, unknown>[]) ?? [];
  const containerStatuses = (status.containerStatuses as Record<string, unknown>[]) ?? [];
  const restartsFor = (n: string) => {
    const cs = containerStatuses.find((c) => c.name === n);
    return cs ? Number(cs.restartCount ?? 0) : 0;
  };
  const readyFor = (n: string) => {
    const cs = containerStatuses.find((c) => c.name === n);
    return cs ? Boolean(cs.ready) : false;
  };

  return (
    <div className="ov">
      <dl className="kv">
        <dt>Kind</dt>
        <dd>{obj.kind ?? '—'}</dd>
        {md.namespace && (
          <>
            <dt>Namespace</dt>
            <dd>{md.namespace}</dd>
          </>
        )}
        <dt>Name</dt>
        <dd>{md.name ?? '—'}</dd>
        {typeof status.phase === 'string' && (
          <>
            <dt>Status</dt>
            <dd>{status.phase as string}</dd>
          </>
        )}
        <dt>Created</dt>
        <dd>
          {age(md.creationTimestamp)}
          {md.creationTimestamp ? ` · ${md.creationTimestamp}` : ''}
        </dd>
        {owners.length > 0 && (
          <>
            <dt>Controlled By</dt>
            <dd>{owners.map((o) => `${o.kind}/${o.name}`).join(', ')}</dd>
          </>
        )}
      </dl>

      {Object.keys(labels).length > 0 && (
        <section className="ov-sec">
          <h3>Labels</h3>
          <div className="chips">
            {Object.entries(labels).map(([k, v]) => (
              <span className="chip" key={k}>
                {k}={v}
              </span>
            ))}
          </div>
        </section>
      )}

      {Object.keys(annotations).length > 0 && (
        <section className="ov-sec">
          <h3>Annotations</h3>
          <div className="chips">
            {Object.entries(annotations).map(([k, v]) => (
              <span className="chip subtle" key={k} title={`${k}=${v}`}>
                {k}={v.length > 48 ? v.slice(0, 48) + '…' : v}
              </span>
            ))}
          </div>
        </section>
      )}

      {containers.length > 0 && (
        <section className="ov-sec">
          <h3>Containers</h3>
          <table className="mini">
            <thead>
              <tr>
                <th>Name</th>
                <th>Image</th>
                <th>Ready</th>
                <th>Restarts</th>
              </tr>
            </thead>
            <tbody>
              {containers.map((c) => {
                const cn = String(c.name ?? '');
                return (
                  <tr key={cn}>
                    <td>{cn}</td>
                    <td className="mono">{String(c.image ?? '')}</td>
                    <td>{readyFor(cn) ? 'Yes' : 'No'}</td>
                    <td>{restartsFor(cn)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </section>
      )}

      {conditions.length > 0 && (
        <section className="ov-sec">
          <h3>Conditions</h3>
          <table className="mini">
            <thead>
              <tr>
                <th>Type</th>
                <th>Status</th>
                <th>Reason</th>
              </tr>
            </thead>
            <tbody>
              {conditions.map((c, i) => (
                <tr key={String(c.type ?? i)}>
                  <td>{String(c.type ?? '')}</td>
                  <td>{String(c.status ?? '')}</td>
                  <td>{String(c.reason ?? '')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </div>
  );
}

function EventsPane(props: { events: EventSummary[] | null; error: string | null }) {
  const { events, error } = props;
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
          <th>Type</th>
          <th>Reason</th>
          <th>Message</th>
          <th>Age</th>
        </tr>
      </thead>
      <tbody>
        {events.map((e, i) => (
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

const WORKLOAD_KINDS = [
  { id: 'pods', label: 'Pods' },
  { id: 'deployments', label: 'Deployments' },
  { id: 'statefulsets', label: 'Stateful Sets' },
  { id: 'daemonsets', label: 'Daemon Sets' },
  { id: 'replicasets', label: 'Replica Sets' },
  { id: 'jobs', label: 'Jobs' },
  { id: 'cronjobs', label: 'Cron Jobs' },
];

function isHealthy(kindId: string, o: KubeObject): boolean {
  const s = (o.status as Record<string, unknown>) ?? {};
  const sp = (o.spec as Record<string, unknown>) ?? {};
  const n = (v: unknown) => (typeof v === 'number' ? v : 0);
  switch (kindId) {
    case 'pods':
      return s.phase === 'Running' || s.phase === 'Succeeded';
    case 'deployments':
    case 'statefulsets':
    case 'replicasets':
      // Scaled-to-zero counts as healthy (intentionally scaled down, not failing).
      return n(s.readyReplicas) === n(sp.replicas);
    case 'daemonsets':
      return n(s.numberReady) === n(s.desiredNumberScheduled);
    case 'jobs':
      return n(s.succeeded) > 0;
    default:
      return true;
  }
}

function WorkloadsOverview(props: { cluster: string }) {
  const { cluster } = props;
  const [counts, setCounts] = useState<Record<string, { total: number; ready: number }>>({});
  const [events, setEvents] = useState<EventSummary[] | null>(null);

  useEffect(() => {
    let cancelled = false;
    setCounts({});
    setEvents(null);
    WORKLOAD_KINDS.forEach((k) => {
      api
        .objects(cluster, k.id)
        .then((objs) => {
          if (cancelled) {
            return;
          }
          setCounts((prev) => ({
            ...prev,
            [k.id]: { total: objs.length, ready: objs.filter((o) => isHealthy(k.id, o)).length },
          }));
        })
        .catch(() => undefined);
    });
    api
      .events(cluster)
      .then((e) => !cancelled && setEvents(e))
      .catch(() => !cancelled && setEvents([]));
    return () => {
      cancelled = true;
    };
  }, [cluster]);

  return (
    <div className="overview">
      <h1 className="ov-title">Workloads</h1>
      <div className="ov-cards">
        {WORKLOAD_KINDS.map((k) => {
          const c = counts[k.id];
          const unhealthy = c ? c.total - c.ready : 0;
          return (
            <div className={'ov-card' + (unhealthy > 0 ? ' danger' : '')} key={k.id}>
              <div className="ov-num">{c ? c.total : '…'}</div>
              <div className="ov-lbl">
                {k.label}
                {c ? ` · ${c.ready} ready` : ''}
              </div>
            </div>
          );
        })}
      </div>
      <section className="ov-sec">
        <h3>Recent Events</h3>
        <EventsPane events={events ? events.slice(0, 25) : null} error={null} />
      </section>
    </div>
  );
}

function ClusterOverview(props: { cluster: string; name: string; masterUrl?: string; namespaceCount: number }) {
  const { cluster, name, masterUrl, namespaceCount } = props;
  const [nodes, setNodes] = useState<KubeObject[] | null>(null);
  const [warnings, setWarnings] = useState<EventSummary[] | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setNodes(null);
    setWarnings(null);
    setErr(null);
    api
      .objects(cluster, 'nodes')
      .then((n) => !cancelled && setNodes(n))
      .catch((e) => !cancelled && setErr(String(e)));
    api
      .events(cluster)
      .then((ev) => !cancelled && setWarnings(ev.filter((x) => x.type === 'Warning')))
      .catch(() => !cancelled && setWarnings([]));
    return () => {
      cancelled = true;
    };
  }, [cluster]);

  const nodeReady = (o: KubeObject): boolean => {
    const conds = ((o.status as Record<string, unknown>)?.conditions as Record<string, unknown>[]) ?? [];
    const r = conds.find((c) => c.type === 'Ready');
    return r ? r.status === 'True' : false;
  };
  const readyNodes = (nodes ?? []).filter(nodeReady).length;

  return (
    <div className="overview">
      <h1 className="ov-title">{name}</h1>
      <div className="ov-cards">
        <div className="ov-card">
          <div className="ov-num">{nodes ? nodes.length : '…'}</div>
          <div className="ov-lbl">Nodes{nodes ? ` · ${readyNodes} ready` : ''}</div>
        </div>
        <div className="ov-card">
          <div className="ov-num">{namespaceCount}</div>
          <div className="ov-lbl">Namespaces</div>
        </div>
        <div className={'ov-card' + (warnings && warnings.length > 0 ? ' danger' : '')}>
          <div className="ov-num">{warnings ? warnings.length : '…'}</div>
          <div className="ov-lbl">Warnings</div>
        </div>
      </div>
      {masterUrl && (
        <div className="ov-api">
          API server: <span className="mono">{masterUrl}</span>
        </div>
      )}
      {err && <div className="error">{err}</div>}
      <section className="ov-sec">
        <h3>Warnings</h3>
        {warnings === null ? (
          <div className="empty">Loading…</div>
        ) : warnings.length === 0 ? (
          <div className="empty">No warnings.</div>
        ) : (
          <table className="mini">
            <thead>
              <tr>
                <th>Reason</th>
                <th>Object</th>
                <th>Message</th>
                <th>Age</th>
              </tr>
            </thead>
            <tbody>
              {warnings.slice(0, 30).map((w, i) => (
                <tr key={i} className="warn">
                  <td>{w.reason}</td>
                  <td>{w.object}</td>
                  <td>{w.message}</td>
                  <td>{w.age}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}

function LoginModal(props: { onCancel: () => void; onSubmit: (user: string, pass: string) => void }) {
  const { onCancel, onSubmit } = props;
  const [user, setUser] = useState('admin');
  const [pass, setPass] = useState('');

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onCancel();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onCancel]);

  return (
    <div className="modal-backdrop" onClick={onCancel}>
      <form
        className="modal"
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => {
          e.preventDefault();
          onSubmit(user, pass);
        }}
      >
        <h2>Sign in</h2>
        <p className="modal-note">Credentials are kept in memory for this tab only and sent over HTTP Basic.</p>
        <label>
          <span>Username</span>
          <input value={user} onChange={(e) => setUser(e.target.value)} autoFocus />
        </label>
        <label>
          <span>Password</span>
          <input type="password" value={pass} onChange={(e) => setPass(e.target.value)} />
        </label>
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onCancel}>
            Cancel
          </button>
          <button type="submit" className="btn primary">
            Sign in
          </button>
        </div>
      </form>
    </div>
  );
}

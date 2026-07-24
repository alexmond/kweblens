import { useEffect, useMemo, useState } from 'react';

import { ApiError, api } from './api';
import { auth } from './auth';
import type { NavCategory, NavItem, ResourceRow } from './types';

function initials(id: string): string {
  return (id.length >= 2 ? id.slice(0, 2) : id).toUpperCase();
}

function rowKey(r: ResourceRow): string {
  return (r.namespace ?? '') + '/' + r.name;
}

export function App() {
  const [clusters, setClusters] = useState<{ id: string; name: string }[]>([]);
  const [cluster, setCluster] = useState<string | null>(null);
  const [nav, setNav] = useState<NavCategory[]>([]);
  const [namespaces, setNamespaces] = useState<string[]>([]);
  const [namespace, setNamespace] = useState<string | null>(null);
  const [selected, setSelected] = useState<NavItem | null>(null);
  const [rows, setRows] = useState<ResourceRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [live, setLive] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [detail, setDetail] = useState<{ resourceId: string; row: ResourceRow } | null>(null);
  const [authUser, setAuthUser] = useState<string | null>(null);
  const [showLogin, setShowLogin] = useState(false);

  // Load the cluster list once.
  useEffect(() => {
    api
      .clusters()
      .then((cs) => {
        setClusters(cs);
        setCluster((prev) => prev ?? cs[0]?.id ?? null);
      })
      .catch((e) => setError(String(e)));
  }, []);

  // On cluster change: load its Navigator + namespaces, reset the view.
  useEffect(() => {
    if (!cluster) {
      return;
    }
    setNav([]);
    setNamespaces([]);
    setNamespace(null);
    setSelected(null);
    setRows([]);
    setError(null);
    api.nav(cluster).then(setNav).catch((e) => setError(String(e)));
    api
      .namespaces(cluster)
      .then((ns) => setNamespaces(ns.map((r) => r.name).sort()))
      .catch(() => setNamespaces([]));
  }, [cluster]);

  // Fetch the selected kind whenever the kind or the namespace filter changes. The
  // namespace filter only applies to namespaced kinds; cluster-scoped kinds ignore it.
  useEffect(() => {
    if (!cluster || !selected) {
      return;
    }
    const ns = selected.namespaced ? namespace ?? undefined : undefined;
    let cancelled = false;
    setDetail(null);
    setLoading(true);
    setError(null);
    api
      .resources(cluster, selected.id, ns)
      .then((r) => {
        if (!cancelled) {
          setRows(r);
        }
      })
      .catch((e) => {
        if (!cancelled) {
          setError(String(e));
          setRows([]);
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

  // Live updates: stream ADDED/MODIFIED/DELETED over SSE and patch the table in place,
  // scoped to the same cluster/kind/namespace as the current view.
  useEffect(() => {
    if (!cluster || !selected) {
      return;
    }
    const ns = selected.namespaced ? namespace ?? undefined : undefined;
    const url =
      `/api/v1/clusters/${encodeURIComponent(cluster)}/resources/${encodeURIComponent(selected.id)}/watch` +
      (ns ? `?namespace=${encodeURIComponent(ns)}` : '');
    const es = new EventSource(url);

    const upsert = (e: MessageEvent) => {
      const row = JSON.parse(e.data) as ResourceRow;
      setRows((prev) => {
        const key = rowKey(row);
        const idx = prev.findIndex((r) => rowKey(r) === key);
        if (idx === -1) {
          return [...prev, row];
        }
        const next = prev.slice();
        next[idx] = row;
        return next;
      });
    };
    const remove = (e: MessageEvent) => {
      const row = JSON.parse(e.data) as ResourceRow;
      setRows((prev) => prev.filter((r) => rowKey(r) !== rowKey(row)));
    };

    es.addEventListener('ADDED', upsert as EventListener);
    es.addEventListener('MODIFIED', upsert as EventListener);
    es.addEventListener('DELETED', remove as EventListener);
    es.onopen = () => setLive(true);
    // EventSource reconnects on its own; just reflect that we are not currently streaming.
    es.onerror = () => setLive(false);

    return () => {
      setLive(false);
      es.close();
    };
  }, [cluster, selected, namespace]);

  const activeCluster = useMemo(() => clusters.find((c) => c.id === cluster) ?? null, [clusters, cluster]);

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
          {!selected && !error && <div className="empty">Pick a resource kind from the Navigator.</div>}
          {selected && (
            <>
              <div className="content-head">
                <h1>{selected.label}</h1>
                <span className="count">{rows.length} items</span>
                {live && (
                  <span className="live" title="Live-updating (SSE watch)">
                    <span className="dot" /> live
                  </span>
                )}
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
                rows={rows}
                loading={loading}
                selectedName={detail?.row.name ?? null}
                onOpen={(row) => setDetail({ resourceId: selected.id, row })}
              />
            </>
          )}
        </main>

        {cluster && detail && (
          <Detail
            cluster={cluster}
            resourceId={detail.resourceId}
            row={detail.row}
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

function NavTree(props: {
  categories: NavCategory[];
  selected: string | null;
  onSelect: (item: NavItem) => void;
}) {
  const { categories, selected, onSelect } = props;
  const [open, setOpen] = useState<Set<string>>(new Set());

  // Auto-expand the group holding the current selection.
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
  rows: ResourceRow[];
  loading: boolean;
  selectedName: string | null;
  onOpen: (row: ResourceRow) => void;
}) {
  const { rows, loading, selectedName, onOpen } = props;
  if (loading) {
    return <div className="empty">Loading…</div>;
  }
  if (rows.length === 0) {
    return <div className="empty">No resources.</div>;
  }
  const namespaced = rows.some((r) => r.namespace);
  const sorted = [...rows].sort(
    (a, b) => (a.namespace ?? '').localeCompare(b.namespace ?? '') || a.name.localeCompare(b.name),
  );
  return (
    <table className="grid clickable">
      <thead>
        <tr>
          {namespaced && <th>Namespace</th>}
          <th>Name</th>
          <th>Status</th>
          <th>Age</th>
        </tr>
      </thead>
      <tbody>
        {sorted.map((r) => (
          <tr
            key={rowKey(r)}
            className={r.name === selectedName ? 'row-active' : ''}
            onClick={() => onOpen(r)}
          >
            {namespaced && <td>{r.namespace}</td>}
            <td className="name">{r.name}</td>
            <td>{r.status ?? '—'}</td>
            <td>{r.age}</td>
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
  row: ResourceRow;
  authed: boolean;
  onRequireAuth: () => void;
  onAuthExpired: () => void;
  onClose: () => void;
}) {
  const { cluster, resourceId, row, authed, onRequireAuth, onAuthExpired, onClose } = props;
  const [tab, setTab] = useState<'overview' | 'yaml'>('overview');
  const [yaml, setYaml] = useState<string | null>(null);
  const [yamlError, setYamlError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [replicas, setReplicas] = useState(1);
  const [busy, setBusy] = useState(false);
  const [actionMsg, setActionMsg] = useState<string | null>(null);
  const [actionErr, setActionErr] = useState(false);

  const isNode = row.kind === 'Node';
  const ns = row.namespace ?? '';

  const act = async (
    fn: () => Promise<{ result: string }>,
    opts?: { confirm?: string; closeOnDone?: boolean },
  ) => {
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

  // Close on Escape.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  // Lazily fetch YAML the first time the YAML tab is opened.
  useEffect(() => {
    if (tab !== 'yaml' || yaml !== null || yamlError !== null) {
      return;
    }
    let cancelled = false;
    api
      .yaml(cluster, resourceId, row.name, row.namespace ?? undefined)
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
  }, [tab, yaml, yamlError, cluster, resourceId, row.name, row.namespace]);

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

  return (
    <div className="drawer" role="dialog" aria-label={`${row.kind} ${row.name}`}>
      <div className="drawer-head">
        <div className="drawer-title">
          <span className="drawer-kind">{row.kind}</span>
          <span className="drawer-name">{row.name}</span>
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
      </div>
      <div className="drawer-body">
        {tab === 'overview' && (
          <dl className="kv">
            <dt>Kind</dt>
            <dd>{row.kind}</dd>
            {row.namespace && (
              <>
                <dt>Namespace</dt>
                <dd>{row.namespace}</dd>
              </>
            )}
            <dt>Name</dt>
            <dd>{row.name}</dd>
            <dt>Status</dt>
            <dd>{row.status ?? '—'}</dd>
            <dt>Age</dt>
            <dd>{row.age}</dd>
          </dl>
        )}
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
        {authed && SCALABLE.includes(row.kind) && (
          <span className="act">
            <input
              type="number"
              min={0}
              className="repl"
              value={replicas}
              disabled={busy}
              onChange={(e) => setReplicas(Math.max(0, Number.parseInt(e.target.value || '0', 10)))}
            />
            <button
              className="btn"
              disabled={busy}
              onClick={() => act(() => api.scale(cluster, resourceId, ns, row.name, replicas))}
            >
              Scale
            </button>
          </span>
        )}
        {authed && RESTARTABLE.includes(row.kind) && (
          <button
            className="btn"
            disabled={busy}
            onClick={() =>
              act(() => api.restart(cluster, resourceId, ns, row.name), {
                confirm: `Rolling-restart ${row.name}?`,
              })
            }
          >
            Restart
          </button>
        )}
        {authed && isNode && (
          <>
            <button
              className="btn"
              disabled={busy}
              onClick={() => act(() => api.cordon(cluster, row.name), { confirm: `Cordon ${row.name}?` })}
            >
              Cordon
            </button>
            <button className="btn" disabled={busy} onClick={() => act(() => api.uncordon(cluster, row.name))}>
              Uncordon
            </button>
          </>
        )}
        {authed && !isNode && row.namespace && (
          <button
            className="btn danger"
            disabled={busy}
            onClick={() =>
              act(() => api.del(cluster, resourceId, ns, row.name), {
                confirm: `Delete ${row.kind} ${row.name}? This cannot be undone.`,
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

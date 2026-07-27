import type { ReactNode } from 'react';
import { useEffect, useState } from 'react';

import { ApiError, api } from './api';
import { age } from './columns';
import { useDialog } from './dialog';
import { useEscapeKey, useTableSort } from './hooks';
import { KebabMenu } from './rowMenu';
import { SortTh, StatusBadge } from './ui';
import type { HelmChart, HelmMutationResult, HelmRelease, HelmResourceRef } from './types';

type HelmAction =
  | { mode: 'install'; repository: string; chart: string; version: string }
  | {
      mode: 'upgrade';
      namespace: string;
      name: string;
      chart: string;
      chartVersion: string;
      repository?: string;
      version?: string;
    }
  | { mode: 'rollback'; namespace: string; name: string; revision: number };

export function HelmView(props: {
  cluster: string;
  view: 'charts' | 'releases' | 'repositories';
  authed: boolean;
  onNavigate: (kind: string, ns?: string) => void;
  openResources?: { namespace: string; name: string } | null;
  onResourcesConsumed?: () => void;
  onRequireAuth: () => void;
  onAuthExpired: () => void;
}) {
  const { cluster, view, authed, onNavigate, openResources, onResourcesConsumed, onRequireAuth, onAuthExpired } = props;
  const [action, setAction] = useState<HelmAction | null>(null);
  const [resourcesFor, setResourcesFor] = useState<{ namespace: string; name: string } | null>(null);
  const [valuesFor, setValuesFor] = useState<{ namespace: string; name: string } | null>(null);
  const [historyFor, setHistoryFor] = useState<{ namespace: string; name: string } | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);

  const onAction = (a: HelmAction) => (authed ? setAction(a) : onRequireAuth());

  // Deep-linked from a resource's "Managed By: Helm" — open that release's resources.
  useEffect(() => {
    if (openResources) {
      setResourcesFor(openResources);
      onResourcesConsumed?.();
    }
  }, [openResources, onResourcesConsumed]);

  const title =
    view === 'charts' ? 'Helm · Charts' : view === 'repositories' ? 'Helm · Repositories' : 'Helm · Releases';
  return (
    <div className="overview">
      <div className="content-head">
        <h1>{title}</h1>
      </div>
      {view === 'charts' ? (
        <HelmCharts cluster={cluster} onAction={onAction} />
      ) : view === 'repositories' ? (
        <HelmRepos authed={authed} onRequireAuth={onRequireAuth} onAuthExpired={onAuthExpired} />
      ) : (
        <HelmReleases
          cluster={cluster}
          authed={authed}
          onAction={onAction}
          onResources={(namespace, name) => setResourcesFor({ namespace, name })}
          onValues={(namespace, name) => setValuesFor({ namespace, name })}
          onHistory={(namespace, name) => setHistoryFor({ namespace, name })}
          onRequireAuth={onRequireAuth}
          refreshKey={refreshKey}
        />
      )}
      {action && (
        <HelmActionModal
          cluster={cluster}
          action={action}
          onClose={() => setAction(null)}
          onApplied={() => {
            setAction(null);
            setRefreshKey((k) => k + 1);
          }}
          onAuthExpired={onAuthExpired}
        />
      )}
      {resourcesFor && (
        <HelmResourcesModal
          cluster={cluster}
          namespace={resourcesFor.namespace}
          name={resourcesFor.name}
          onClose={() => setResourcesFor(null)}
          onOpen={(kind, ns) => {
            setResourcesFor(null);
            onNavigate(kind, ns);
          }}
        />
      )}
      {valuesFor && (
        <HelmValuesModal
          cluster={cluster}
          namespace={valuesFor.namespace}
          name={valuesFor.name}
          onClose={() => setValuesFor(null)}
        />
      )}
      {historyFor && (
        <HelmHistoryModal
          cluster={cluster}
          namespace={historyFor.namespace}
          name={historyFor.name}
          onClose={() => setHistoryFor(null)}
        />
      )}
    </div>
  );
}

function HelmCharts(props: { cluster: string; onAction: (a: HelmAction) => void }) {
  const { cluster, onAction } = props;
  const [charts, setCharts] = useState<HelmChart[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState('');

  useEffect(() => {
    let cancelled = false;
    setCharts(null);
    setError(null);
    api
      .helmCharts(cluster)
      .then((c) => !cancelled && setCharts(c))
      .catch((e) => !cancelled && setError(String(e)));
    return () => {
      cancelled = true;
    };
  }, [cluster]);

  const q = query.trim().toLowerCase();
  const filtered = (charts ?? []).filter(
    (c) => !q || c.name.toLowerCase().includes(q) || (c.description ?? '').toLowerCase().includes(q),
  );
  const { sorted, sort, clickHeader } = useTableSort(
    filtered,
    'name',
    (c, k) => (c[k as keyof HelmChart] as string) ?? '',
  );

  return (
    <>
      <div className="content-head">
        <span className="count">{charts ? `${filtered.length} charts` : ''}</span>
        <div className="spacer" />
        <input
          className="search"
          type="search"
          placeholder="Search charts…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>
      {error && <div className="error">{error}</div>}
      {charts === null ? (
        <div className="empty">Loading…</div>
      ) : filtered.length === 0 ? (
        <div className="empty">No charts. Configure repositories under kweblens.helm.repositories.</div>
      ) : (
        <table className="grid">
          <thead>
            <tr>
              <SortTh label="Name" colKey="name" sort={sort} onClick={clickHeader} />
              <SortTh label="Description" colKey="description" sort={sort} onClick={clickHeader} />
              <SortTh label="Version" colKey="version" sort={sort} onClick={clickHeader} />
              <SortTh label="App Version" colKey="appVersion" sort={sort} onClick={clickHeader} />
              <SortTh label="Repository" colKey="repository" sort={sort} onClick={clickHeader} />
              <th />
            </tr>
          </thead>
          <tbody>
            {sorted.map((c) => (
              <tr key={c.repository + '/' + c.name}>
                <td className="name">{c.name}</td>
                <td className="muted">{c.description ?? '—'}</td>
                <td>{c.version}</td>
                <td>{c.appVersion ?? '—'}</td>
                <td>{c.repository}</td>
                <td className="row-actions">
                  <KebabMenu
                    items={[
                      {
                        label: 'Install',
                        onClick: () =>
                          onAction({ mode: 'install', repository: c.repository, chart: c.name, version: c.version }),
                      },
                    ]}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}

function HelmReleases(props: {
  cluster: string;
  authed: boolean;
  onAction: (a: HelmAction) => void;
  onResources: (namespace: string, name: string) => void;
  onValues: (namespace: string, name: string) => void;
  onHistory: (namespace: string, name: string) => void;
  onRequireAuth: () => void;
  refreshKey: number;
}) {
  const { cluster, authed, onAction, onResources, onValues, onHistory, onRequireAuth, refreshKey } = props;
  const dialog = useDialog();
  const [releases, setReleases] = useState<HelmRelease[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [localKey, setLocalKey] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setReleases(null);
    setError(null);
    api
      .helmReleases(cluster)
      .then((r) => !cancelled && setReleases(r))
      .catch((e) => !cancelled && setError(String(e)));
    return () => {
      cancelled = true;
    };
  }, [cluster, refreshKey, localKey]);

  const uninstall = (r: HelmRelease) => {
    if (!authed) {
      onRequireAuth();
      return;
    }
    dialog
      .confirm({
        title: 'Uninstall release',
        message: `Uninstall release "${r.name}" in ${r.namespace}? This removes its resources and history.`,
        confirmLabel: 'Uninstall',
        danger: true,
      })
      .then((ok) => {
        if (!ok) {
          return;
        }
        api
          .helmUninstall(cluster, r.namespace, r.name)
          .then(() => setLocalKey((k) => k + 1))
          .catch((e) => setError(String(e)));
      });
  };

  const { sorted, sort, clickHeader } = useTableSort(releases ?? [], 'name', (r, k) => {
    if (k === 'revision') {
      return r.revision;
    }
    if (k === 'updated') {
      return Date.parse(r.updated ?? '') || 0;
    }
    return (r[k as keyof HelmRelease] as string) ?? '';
  });

  return (
    <>
      <div className="content-head">
        <span className="count">{releases ? `${releases.length} releases` : ''}</span>
      </div>
      {error && <div className="error">{error}</div>}
      {releases === null ? (
        <div className="empty">Loading…</div>
      ) : releases.length === 0 ? (
        <div className="empty">No releases.</div>
      ) : (
        <table className="grid">
          <thead>
            <tr>
              <SortTh label="Name" colKey="name" sort={sort} onClick={clickHeader} />
              <SortTh label="Namespace" colKey="namespace" sort={sort} onClick={clickHeader} />
              <SortTh label="Chart" colKey="chart" sort={sort} onClick={clickHeader} />
              <SortTh label="Source" colKey="managedByKweblens" sort={sort} onClick={clickHeader} />
              <SortTh label="Revision" colKey="revision" sort={sort} onClick={clickHeader} />
              <SortTh label="Version" colKey="chartVersion" sort={sort} onClick={clickHeader} />
              <SortTh label="App Version" colKey="appVersion" sort={sort} onClick={clickHeader} />
              <SortTh label="Status" colKey="status" sort={sort} onClick={clickHeader} />
              <SortTh label="Updated" colKey="updated" sort={sort} onClick={clickHeader} />
              <th />
            </tr>
          </thead>
          <tbody>
            {sorted.map((r) => (
              <tr key={r.namespace + '/' + r.name}>
                <td className="name">{r.name}</td>
                <td>{r.namespace}</td>
                <td>{r.chart}</td>
                <td>
                  {r.managedByKweblens ? (
                    <span className="chip">kweblens</span>
                  ) : (
                    <span className="chip subtle">external</span>
                  )}
                </td>
                <td>{r.revision}</td>
                <td>
                  {r.chartVersion}
                  {r.updateAvailable && (
                    <span className="chip update-chip" title={`Latest ${r.latestVersion} in ${r.latestRepository}`}>
                      ↑ {r.latestVersion}
                    </span>
                  )}
                </td>
                <td>{r.appVersion}</td>
                <td>
                  <StatusBadge text={r.status} />
                </td>
                <td>{r.updated ? age(r.updated) : '—'}</td>
                <td className="row-actions">
                  <KebabMenu
                    items={[
                      { label: 'Resources', onClick: () => onResources(r.namespace, r.name) },
                      { label: 'Values', onClick: () => onValues(r.namespace, r.name) },
                      { label: 'History', onClick: () => onHistory(r.namespace, r.name) },
                      ...(r.updateAvailable
                        ? [
                            {
                              label: `Update → ${r.latestVersion}`,
                              onClick: () =>
                                onAction({
                                  mode: 'upgrade',
                                  namespace: r.namespace,
                                  name: r.name,
                                  chart: r.chart,
                                  chartVersion: r.chartVersion,
                                  repository: r.latestRepository ?? undefined,
                                  version: r.latestVersion ?? undefined,
                                }),
                            },
                          ]
                        : []),
                      {
                        label: 'Upgrade',
                        onClick: () =>
                          onAction({
                            mode: 'upgrade',
                            namespace: r.namespace,
                            name: r.name,
                            chart: r.chart,
                            chartVersion: r.chartVersion,
                          }),
                      },
                      {
                        label: 'Rollback',
                        disabled: r.revision <= 1,
                        onClick: () =>
                          onAction({
                            mode: 'rollback',
                            namespace: r.namespace,
                            name: r.name,
                            revision: r.revision - 1,
                          }),
                      },
                      { label: 'Uninstall', danger: true, onClick: () => uninstall(r) },
                    ]}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}

function HelmRepos(props: { authed: boolean; onRequireAuth: () => void; onAuthExpired: () => void }) {
  const { authed, onRequireAuth, onAuthExpired } = props;
  const dialog = useDialog();
  const [repos, setRepos] = useState<{ name: string; url: string }[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [busy, setBusy] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setRepos(null);
    setError(null);
    api
      .helmRepos()
      .then((r) => !cancelled && setRepos(r))
      .catch((e) => !cancelled && setError(String(e)));
    return () => {
      cancelled = true;
    };
  }, [refreshKey]);

  const fail = (e: unknown) => {
    if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
      onAuthExpired();
    }
    setError(String(e));
  };

  const add = () => {
    if (!authed) {
      onRequireAuth();
      return;
    }
    if (!name.trim() || !url.trim()) {
      return;
    }
    setBusy(true);
    setError(null);
    api
      .helmAddRepo(name.trim(), url.trim())
      .then(() => {
        setName('');
        setUrl('');
        setRefreshKey((k) => k + 1);
      })
      .catch(fail)
      .finally(() => setBusy(false));
  };

  const remove = (repo: string) => {
    if (!authed) {
      onRequireAuth();
      return;
    }
    dialog
      .confirm({
        title: 'Remove repository',
        message: `Remove repository ${repo}?`,
        confirmLabel: 'Remove',
        danger: true,
      })
      .then((ok) => {
        if (!ok) {
          return;
        }
        setBusy(true);
        api
          .helmRemoveRepo(repo)
          .then(() => setRefreshKey((k) => k + 1))
          .catch(fail)
          .finally(() => setBusy(false));
      });
  };

  const edit = (repo: string, currentUrl: string) => {
    if (!authed) {
      onRequireAuth();
      return;
    }
    dialog
      .prompt({
        title: 'Edit repository',
        message: `New URL for repository "${repo}":`,
        label: 'URL',
        initial: currentUrl,
        placeholder: 'https://charts.example.com',
        confirmLabel: 'Save',
      })
      .then((next) => {
        if (!next || !next.trim() || next.trim() === currentUrl) {
          return;
        }
        setBusy(true);
        api
          .helmRemoveRepo(repo)
          .then(() => api.helmAddRepo(repo, next.trim()))
          .then(() => setRefreshKey((k) => k + 1))
          .catch(fail)
          .finally(() => setBusy(false));
      });
  };

  const refresh = (repo: string) => {
    if (!authed) {
      onRequireAuth();
      return;
    }
    setBusy(true);
    api
      .helmRefreshRepo(repo)
      .then(() => setRefreshKey((k) => k + 1))
      .catch(fail)
      .finally(() => setBusy(false));
  };

  return (
    <>
      <div className="content-head">
        <span className="count">{repos ? `${repos.length} repositories` : ''}</span>
      </div>
      {error && <div className="error">{error}</div>}
      {authed && (
        <div className="repo-add">
          <input placeholder="name" value={name} disabled={busy} onChange={(e) => setName(e.target.value)} />
          <input
            placeholder="https://charts.example.com"
            value={url}
            disabled={busy}
            className="repo-url"
            onChange={(e) => setUrl(e.target.value)}
          />
          <button className="btn primary" disabled={busy || !name.trim() || !url.trim()} onClick={add}>
            Add repository
          </button>
        </div>
      )}
      {repos === null ? (
        <div className="empty">Loading…</div>
      ) : repos.length === 0 ? (
        <div className="empty">No repositories. Add one above.</div>
      ) : (
        <table className="grid">
          <thead>
            <tr>
              <th>Name</th>
              <th>URL</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {repos.map((r) => (
              <tr key={r.name}>
                <td className="name">{r.name}</td>
                <td className="mono">{r.url}</td>
                <td className="row-actions">
                  <KebabMenu
                    items={[
                      { label: 'Edit', onClick: () => edit(r.name, r.url) },
                      { label: 'Refresh', onClick: () => refresh(r.name) },
                      { label: 'Remove', danger: true, onClick: () => remove(r.name) },
                    ]}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}

function HelmResourcesModal(props: {
  cluster: string;
  namespace: string;
  name: string;
  onClose: () => void;
  onOpen: (kind: string, namespace: string) => void;
}) {
  const { cluster, namespace, name, onClose, onOpen } = props;
  const [resources, setResources] = useState<HelmResourceRef[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .helmReleaseResources(cluster, namespace, name)
      .then((r) => !cancelled && setResources(r))
      .catch((e) => !cancelled && setError(String(e)));
    return () => {
      cancelled = true;
    };
  }, [cluster, namespace, name]);

  useEscapeKey(onClose);

  const { sorted, sort, clickHeader } = useTableSort(
    resources ?? [],
    'kind',
    (r, k) => (r[k as keyof HelmResourceRef] as string) ?? '',
  );

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal wide" onClick={(e) => e.stopPropagation()}>
        <h2>Resources</h2>
        <p className="modal-note">
          Objects managed by release <strong>{name}</strong> in <strong>{namespace}</strong> (from its manifest). Click
          a name to open it.
        </p>
        {error && <div className="error">{error}</div>}
        {resources === null ? (
          <div className="empty">Loading…</div>
        ) : resources.length === 0 ? (
          <div className="empty">No resources in this release's manifest.</div>
        ) : (
          <table className="grid">
            <thead>
              <tr>
                <SortTh label="Kind" colKey="kind" sort={sort} onClick={clickHeader} />
                <SortTh label="Namespace" colKey="namespace" sort={sort} onClick={clickHeader} />
                <SortTh label="Name" colKey="name" sort={sort} onClick={clickHeader} />
              </tr>
            </thead>
            <tbody>
              {sorted.map((r) => (
                <tr key={r.kind + '/' + r.namespace + '/' + r.name}>
                  <td>{r.kind}</td>
                  <td>{r.namespace}</td>
                  <td className="name">
                    <button className="cell-link" onClick={() => onOpen(r.kind, r.namespace)}>
                      {r.name}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}

function HelmValuesModal(props: { cluster: string; namespace: string; name: string; onClose: () => void }) {
  const { cluster, namespace, name, onClose } = props;
  const [values, setValues] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .helmReleaseValues(cluster, namespace, name)
      .then((y) => !cancelled && setValues(y))
      .catch((e) => !cancelled && setError(String(e)));
    return () => {
      cancelled = true;
    };
  }, [cluster, namespace, name]);

  useEscapeKey(onClose);

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal wide" onClick={(e) => e.stopPropagation()}>
        <h2>Values</h2>
        <p className="modal-note">
          Stored configuration for release <strong>{name}</strong> in <strong>{namespace}</strong> (helm get values).
        </p>
        {error && <div className="error">{error}</div>}
        {values === null ? (
          <div className="empty">Loading…</div>
        ) : values.trim() === '' ? (
          <div className="empty">No user-supplied values (chart defaults only).</div>
        ) : (
          <pre className="yaml-view">{values}</pre>
        )}
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}

function HelmHistoryModal(props: { cluster: string; namespace: string; name: string; onClose: () => void }) {
  const { cluster, namespace, name, onClose } = props;
  const [history, setHistory] = useState<HelmRelease[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .helmHistory(cluster, namespace, name)
      .then((h) => !cancelled && setHistory(h))
      .catch((e) => !cancelled && setError(String(e)));
    return () => {
      cancelled = true;
    };
  }, [cluster, namespace, name]);

  useEscapeKey(onClose);

  const rows = [...(history ?? [])].sort((a, b) => b.revision - a.revision);

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal wide" onClick={(e) => e.stopPropagation()}>
        <h2>History</h2>
        <p className="modal-note">
          Revision history of release <strong>{name}</strong> in <strong>{namespace}</strong> (helm history).
        </p>
        {error && <div className="error">{error}</div>}
        {history === null ? (
          <div className="empty">Loading…</div>
        ) : rows.length === 0 ? (
          <div className="empty">No history for this release.</div>
        ) : (
          <table className="grid">
            <thead>
              <tr>
                <th>Revision</th>
                <th>Chart</th>
                <th>Version</th>
                <th>App Version</th>
                <th>Status</th>
                <th>Updated</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.revision}>
                  <td>{r.revision}</td>
                  <td>{r.chart}</td>
                  <td>{r.chartVersion}</td>
                  <td>{r.appVersion}</td>
                  <td>
                    <StatusBadge text={r.status} />
                  </td>
                  <td>{r.updated ? age(r.updated) : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}

const labelled = (label: string, node: ReactNode) => (
  <label>
    <span>{label}</span>
    {node}
  </label>
);

/** The values-YAML editor: load a saved set / a release's current values, edit, save. */
function HelmValuesEditor(props: {
  cluster: string;
  action: HelmAction;
  valuesYaml: string;
  setValuesYaml: (v: string) => void;
  savedValues: string[];
  setSavedValues: (v: string[]) => void;
}) {
  const { cluster, action, valuesYaml, setValuesYaml, savedValues, setSavedValues } = props;
  const [pickValues, setPickValues] = useState('');
  const [saveName, setSaveName] = useState('');
  const [valuesMsg, setValuesMsg] = useState<string | null>(null);
  return labelled(
    'Values (YAML, optional)',
    <>
      <div className="values-toolbar">
        <select value={pickValues} onChange={(e) => setPickValues(e.target.value)}>
          <option value="">— saved values —</option>
          {savedValues.map((n) => (
            <option key={n} value={n}>
              {n}
            </option>
          ))}
        </select>
        <button
          type="button"
          className="btn"
          disabled={!pickValues}
          onClick={() =>
            api
              .helmValuesGet(pickValues)
              .then((y) => {
                setValuesYaml(y);
                setValuesMsg(`loaded "${pickValues}"`);
              })
              .catch((e) => setValuesMsg(String(e)))
          }
        >
          Load
        </button>
        {action.mode === 'upgrade' && (
          <button
            type="button"
            className="btn"
            onClick={() =>
              api
                .helmReleaseValues(cluster, action.namespace, action.name)
                .then((y) => {
                  setValuesYaml(y);
                  setValuesMsg('loaded current release values');
                })
                .catch((e) => setValuesMsg(String(e)))
            }
          >
            Load current values
          </button>
        )}
        <span className="tb-spacer" />
        <input
          className="save-name"
          placeholder="save as…"
          value={saveName}
          onChange={(e) => setSaveName(e.target.value)}
        />
        <button
          type="button"
          className="btn"
          disabled={!saveName.trim()}
          onClick={() =>
            api
              .helmValuesSave(saveName.trim(), valuesYaml)
              .then(() => {
                setValuesMsg(`saved "${saveName.trim()}"`);
                setSaveName('');
                return api.helmValuesList().then(setSavedValues);
              })
              .catch((e) => setValuesMsg(String(e)))
          }
        >
          Save
        </button>
      </div>
      {valuesMsg && <div className="values-msg">{valuesMsg}</div>}
      <textarea
        className="values"
        rows={5}
        value={valuesYaml}
        placeholder="key: value"
        onChange={(e) => setValuesYaml(e.target.value)}
      />
    </>,
  );
}

/** Install/upgrade advanced options (jhelm InstallOptions / UpgradeOptions). */
function HelmAdvancedOptions(props: {
  isUpgrade: boolean;
  noHooks: boolean;
  setNoHooks: (v: boolean) => void;
  force: boolean;
  setForce: (v: boolean) => void;
  valueStrategy: string;
  setValueStrategy: (v: string) => void;
  maxHistory: string;
  setMaxHistory: (v: string) => void;
  description: string;
  setDescription: (v: string) => void;
}) {
  const { isUpgrade, noHooks, setNoHooks, force, setForce, valueStrategy, setValueStrategy } = props;
  const { maxHistory, setMaxHistory, description, setDescription } = props;
  const [showAdvanced, setShowAdvanced] = useState(false);
  return (
    <div className="adv-options">
      <button
        type="button"
        className="adv-toggle"
        onClick={() => setShowAdvanced((v) => !v)}
        aria-expanded={showAdvanced}
      >
        {showAdvanced ? '▾' : '▸'} Advanced options
      </button>
      {showAdvanced && (
        <div className="adv-body">
          <label className="check">
            <input type="checkbox" checked={noHooks} onChange={(e) => setNoHooks(e.target.checked)} />
            <span>Skip hooks (--no-hooks)</span>
          </label>
          {isUpgrade && (
            <>
              <label className="check">
                <input type="checkbox" checked={force} onChange={(e) => setForce(e.target.checked)} />
                <span>Force resource updates (--force)</span>
              </label>
              {labelled(
                'Values strategy',
                <select value={valueStrategy} onChange={(e) => setValueStrategy(e.target.value)}>
                  <option value="">Default</option>
                  <option value="REUSE">Reuse previous values</option>
                  <option value="RESET">Reset to chart defaults</option>
                  <option value="RESET_THEN_REUSE">Reset, then reuse</option>
                </select>,
              )}
              {labelled(
                'Max history (0 = keep default)',
                <input
                  type="number"
                  min={0}
                  value={maxHistory}
                  placeholder="0"
                  onChange={(e) => setMaxHistory(e.target.value)}
                />,
              )}
            </>
          )}
          {labelled(
            'Description (optional)',
            <input
              value={description}
              placeholder="release description"
              onChange={(e) => setDescription(e.target.value)}
            />,
          )}
        </div>
      )}
    </div>
  );
}

/** Seed the action-modal form fields from the action (chart/version/repo per mode). */
function initialFormState(action: HelmAction) {
  if (action.mode === 'install') {
    return {
      releaseName: action.chart,
      repository: action.repository,
      chart: action.chart,
      version: action.version,
      revision: 1,
    };
  }
  if (action.mode === 'upgrade') {
    return {
      releaseName: '',
      repository: action.repository ?? '',
      chart: action.chart,
      version: action.version ?? action.chartVersion,
      revision: 1,
    };
  }
  return { releaseName: '', repository: '', chart: '', version: '', revision: action.revision };
}

function HelmActionModal(props: {
  cluster: string;
  action: HelmAction;
  onClose: () => void;
  onApplied: () => void;
  onAuthExpired: () => void;
}) {
  const { cluster, action, onClose, onApplied, onAuthExpired } = props;
  const init = initialFormState(action);
  const [releaseName, setReleaseName] = useState(init.releaseName);
  const [namespace, setNamespace] = useState('default');
  const [repository, setRepository] = useState(init.repository);
  const [chart, setChart] = useState(init.chart);
  const [version, setVersion] = useState(init.version);
  const [valuesYaml, setValuesYaml] = useState('');
  const [savedValues, setSavedValues] = useState<string[]>([]);
  const [revision, setRevision] = useState(init.revision);

  useEffect(() => {
    api
      .helmValuesList()
      .then(setSavedValues)
      .catch(() => setSavedValues([]));
  }, []);
  const [createNamespace, setCreateNamespace] = useState(false);
  // Advanced options (map to jhelm InstallOptions / UpgradeOptions).
  const [noHooks, setNoHooks] = useState(false);
  const [description, setDescription] = useState('');
  const [force, setForce] = useState(false);
  const [valueStrategy, setValueStrategy] = useState('');
  const [maxHistory, setMaxHistory] = useState('');
  const [preview, setPreview] = useState<HelmMutationResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEscapeKey(onClose);

  const title =
    action.mode === 'install' ? 'Install chart' : action.mode === 'upgrade' ? 'Upgrade release' : 'Rollback release';

  const run = (dryRun: boolean) => {
    setBusy(true);
    setError(null);
    let p: Promise<HelmMutationResult>;
    const maxHist = Number.parseInt(maxHistory, 10);
    if (action.mode === 'install') {
      p = api.helmInstall(cluster, {
        namespace,
        releaseName,
        repository,
        chart,
        version,
        valuesYaml,
        dryRun,
        createNamespace,
        noHooks,
        description: description.trim() || undefined,
      });
    } else if (action.mode === 'upgrade') {
      p = api.helmUpgrade(cluster, action.namespace, action.name, {
        repository,
        chart,
        version,
        valuesYaml,
        dryRun,
        noHooks,
        force,
        valueStrategy: valueStrategy || undefined,
        maxHistory: Number.isNaN(maxHist) ? undefined : maxHist,
        description: description.trim() || undefined,
      });
    } else {
      p = api.helmRollback(cluster, action.namespace, action.name, { revision, dryRun });
    }
    p.then((res) => (dryRun ? setPreview(res) : onApplied()))
      .catch((err) => {
        if (err instanceof ApiError && err.status === 401) {
          onAuthExpired();
        }
        setError(String(err));
      })
      .finally(() => setBusy(false));
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <form className="modal wide" onClick={(e) => e.stopPropagation()} onSubmit={(e) => e.preventDefault()}>
        <h2>{title}</h2>
        <p className="modal-note">Preview a dry-run first; Apply is enabled once the render succeeds.</p>
        {error && <div className="error">{error}</div>}

        {action.mode === 'install' && (
          <>
            {labelled('Chart', <input value={`${repository}/${chart}`} readOnly />)}
            {labelled('Version', <input value={version} onChange={(e) => setVersion(e.target.value)} />)}
            {labelled('Release name', <input value={releaseName} onChange={(e) => setReleaseName(e.target.value)} />)}
            {labelled('Namespace', <input value={namespace} onChange={(e) => setNamespace(e.target.value)} />)}
            <label className="check">
              <input type="checkbox" checked={createNamespace} onChange={(e) => setCreateNamespace(e.target.checked)} />
              <span>Create namespace if missing</span>
            </label>
          </>
        )}
        {action.mode === 'upgrade' && (
          <>
            {labelled('Release', <input value={`${action.namespace}/${action.name}`} readOnly />)}
            {labelled(
              'Repository',
              <input value={repository} placeholder="repo name" onChange={(e) => setRepository(e.target.value)} />,
            )}
            {labelled('Chart', <input value={chart} onChange={(e) => setChart(e.target.value)} />)}
            {labelled('Version', <input value={version} onChange={(e) => setVersion(e.target.value)} />)}
          </>
        )}
        {action.mode === 'rollback' && (
          <>
            {labelled('Release', <input value={`${action.namespace}/${action.name}`} readOnly />)}
            {labelled(
              'Roll back to revision',
              <input
                type="number"
                min={1}
                value={revision}
                onChange={(e) => setRevision(Math.max(1, Number.parseInt(e.target.value || '1', 10)))}
              />,
            )}
          </>
        )}
        {action.mode !== 'rollback' && (
          <HelmValuesEditor
            cluster={cluster}
            action={action}
            valuesYaml={valuesYaml}
            setValuesYaml={setValuesYaml}
            savedValues={savedValues}
            setSavedValues={setSavedValues}
          />
        )}

        {action.mode !== 'rollback' && (
          <HelmAdvancedOptions
            isUpgrade={action.mode === 'upgrade'}
            noHooks={noHooks}
            setNoHooks={setNoHooks}
            force={force}
            setForce={setForce}
            valueStrategy={valueStrategy}
            setValueStrategy={setValueStrategy}
            maxHistory={maxHistory}
            setMaxHistory={setMaxHistory}
            description={description}
            setDescription={setDescription}
          />
        )}

        {preview && (
          <div className="helm-preview">
            <div className="preview-head">
              Rendered manifest (dry-run) — {preview.manifest ? '' : 'no manifest returned'}
            </div>
            {preview.manifest && <pre>{preview.manifest}</pre>}
          </div>
        )}

        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button type="button" className="btn" onClick={() => run(true)} disabled={busy}>
            {busy ? 'Rendering…' : 'Preview (dry-run)'}
          </button>
          <button type="button" className="btn primary" onClick={() => run(false)} disabled={busy || !preview}>
            {action.mode === 'install' ? 'Install' : action.mode === 'upgrade' ? 'Upgrade' : 'Rollback'}
          </button>
        </div>
      </form>
    </div>
  );
}

import { auth } from './auth';
import type { DiagnoseResult } from './diagnosis';
import { PodFileError } from './podFiles';
import type {
  AboutInfo,
  KindHealth,
  ObjectDetail,
  ClusterConfigView,
  ClusterDefinition,
  ClusterDiagnostics,
  ClusterInfo,
  EventSummary,
  HelmChart,
  HelmMutationResult,
  HelmRelease,
  HelmResourceRef,
  KubeObject,
  MetricSeries,
  NodeDiskUsage,
  NavCategory,
  PodDirectoryListing,
  PodFileContent,
  PortForward,
  PrinterColumn,
  ResourceRow,
  UsageSummary,
} from './types';

export class ApiError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

/** Result payload the mutating endpoints return. */
export interface ActionResult {
  result: string;
}

async function postJson<T>(url: string): Promise<T> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { ...auth.header(), Accept: 'application/json' },
  });
  if (!res.ok) {
    throw new ApiError(res.status, `${res.status} ${res.statusText}`);
  }
  return (await res.json()) as T;
}

async function postBody<T>(url: string, body: string, contentType: string): Promise<T> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { ...auth.header(), Accept: 'application/json', 'Content-Type': contentType },
    body,
  });
  if (!res.ok) {
    throw new ApiError(res.status, `${res.status} ${res.statusText}`);
  }
  return (await res.json()) as T;
}

async function postNoContent(url: string, body?: string, contentType?: string): Promise<void> {
  const headers: Record<string, string> = { ...auth.header() };
  if (contentType) {
    headers['Content-Type'] = contentType;
  }
  const res = await fetch(url, { method: 'POST', headers, body });
  if (!res.ok) {
    throw new ApiError(res.status, `${res.status} ${res.statusText}`);
  }
}

async function deleteReq(url: string): Promise<void> {
  const res = await fetch(url, { method: 'DELETE', headers: { ...auth.header() } });
  if (!res.ok) {
    throw new ApiError(res.status, `${res.status} ${res.statusText}`);
  }
}

async function putJson<T>(url: string, body: string): Promise<T> {
  const res = await fetch(url, {
    method: 'PUT',
    headers: { ...auth.header(), Accept: 'application/json', 'Content-Type': 'application/json' },
    body,
  });
  if (!res.ok) {
    throw new ApiError(res.status, `${res.status} ${res.statusText}`);
  }
  return (await res.json()) as T;
}

async function putText(url: string, body: string): Promise<void> {
  const res = await fetch(url, {
    method: 'PUT',
    headers: { ...auth.header(), 'Content-Type': 'application/yaml' },
    body,
  });
  if (!res.ok) {
    throw new ApiError(res.status, `${res.status} ${res.statusText}`);
  }
}

/** fetch with a hard timeout so a stalled request surfaces an error instead of a
 *  never-ending "Loading…" (e.g. after a pod restart drops the request mid-flight). */
async function fetchWithTimeout(url: string, init: RequestInit, ms = 20000): Promise<Response> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), ms);
  try {
    return await fetch(url, { ...init, signal: ctrl.signal });
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      throw new Error(`Request timed out after ${Math.round(ms / 1000)}s — ${url}`, { cause: e });
    }
    throw e;
  } finally {
    clearTimeout(timer);
  }
}

async function getJson<T>(url: string): Promise<T> {
  const res = await fetchWithTimeout(url, { headers: { Accept: 'application/json' } });
  if (!res.ok) {
    throw new Error(`${res.status} ${res.statusText} — ${url}`);
  }
  return (await res.json()) as T;
}

async function getText(url: string): Promise<string> {
  const res = await fetchWithTimeout(url, { headers: { Accept: 'application/yaml, text/plain' } });
  if (!res.ok) {
    throw new Error(`${res.status} ${res.statusText} — ${url}`);
  }
  return res.text();
}

/** Root of the per-cluster API; every cluster-scoped path builds on {@link clusterBase}. */
const CLUSTERS = '/api/v1/clusters';

/** `/api/v1/clusters/<cluster>` — the base every cluster-scoped endpoint shares. Exported
 *  so raw EventSource/WebSocket/SSE URLs in the app use the same source of truth. */
export const clusterBase = (cluster: string): string => `${CLUSTERS}/${encodeURIComponent(cluster)}`;

/** The optional `?namespace=…` query suffix (empty when no namespace). */
const nsQuery = (namespace?: string): string => (namespace ? `?namespace=${encodeURIComponent(namespace)}` : '');

/** `/api/v1/clusters/<cluster>/helm/releases/<namespace>/<name>` — a single release. */
const helmReleaseUrl = (cluster: string, namespace: string, name: string): string =>
  `${clusterBase(cluster)}/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}`;

/**
 * A request against the pod file browser (GH#140).
 *
 * <p>Three things differ from the helpers above, and all three matter. It sends the Basic
 * header on GETs — the file endpoints are authenticated even in open-mode, where every
 * other GET is public. It parses the `ProblemDetail` body, because the whole point of that
 * slice is that each failure carries a distinct `code` the UI reacts to. And it allows a
 * longer timeout than the default, since each call is a command run inside somebody's
 * container (the server's own budget is `kweblens.files.command-timeout`, 20s by default).
 */
async function filesFetch(url: string, init: RequestInit = {}): Promise<Response> {
  const res = await fetchWithTimeout(
    url,
    { ...init, headers: { ...auth.header(), Accept: 'application/json', ...(init.headers ?? {}) } },
    30000,
  );
  if (!res.ok) {
    throw await podFileError(res);
  }
  return res;
}

/** Read a failed file-API response into a {@link PodFileError}, code included. */
async function podFileError(res: Response): Promise<PodFileError> {
  let code = '';
  let detail = `${res.status} ${res.statusText}`;
  try {
    const body = (await res.json()) as { code?: string; detail?: string };
    code = body.code ?? '';
    detail = body.detail ?? detail;
  } catch {
    // A 401 from the Basic entry point has no JSON body at all; the status is the message.
  }
  return new PodFileError(res.status, code, detail);
}

/** `…/pods/<ns>/<pod>/files` plus the container/path query every call carries. */
function filesUrl(cluster: string, ns: string, pod: string, container: string, path: string, suffix = ''): string {
  const params = new URLSearchParams({ path });
  if (container) {
    params.set('container', container);
  }
  const base = `${clusterBase(cluster)}/pods/${encodeURIComponent(ns)}/${encodeURIComponent(pod)}/files`;
  return `${base}${suffix}?${params.toString()}`;
}

/** PUT one file's contents; the body carries either `text` or `base64`, never both. */
async function putContent(
  cluster: string,
  ns: string,
  pod: string,
  container: string,
  path: string,
  body: { text: string } | { base64: string },
): Promise<void> {
  await filesFetch(filesUrl(cluster, ns, pod, container, path, '/content'), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

/** The pod file browser. Every call may throw a {@link PodFileError}. */
export const podFiles = {
  list: async (cluster: string, ns: string, pod: string, container: string, path: string) =>
    (await (await filesFetch(filesUrl(cluster, ns, pod, container, path))).json()) as PodDirectoryListing,
  read: async (cluster: string, ns: string, pod: string, container: string, path: string) =>
    (await (await filesFetch(filesUrl(cluster, ns, pod, container, path, '/content'))).json()) as PodFileContent,
  // Fetched rather than linked so the Basic credentials travel with it (a plain <a> would
  // rely on the session cookie alone) and so a refusal surfaces as its ProblemDetail
  // instead of the browser saving an error page under the file's name.
  download: async (cluster: string, ns: string, pod: string, container: string, path: string) =>
    (await filesFetch(filesUrl(cluster, ns, pod, container, path, '/download'), { headers: { Accept: '*/*' } })).blob(),
  writeText: async (cluster: string, ns: string, pod: string, container: string, path: string, text: string) =>
    putContent(cluster, ns, pod, container, path, { text }),
  // An upload goes as base64, never as text: a picked file may be anything, and
  // round-tripping arbitrary bytes through a string is how a binary gets corrupted. The
  // server takes exactly one of the two fields for the same reason.
  writeBase64: async (cluster: string, ns: string, pod: string, container: string, path: string, base64: string) =>
    putContent(cluster, ns, pod, container, path, { base64 }),
  remove: async (cluster: string, ns: string, pod: string, container: string, path: string) => {
    await filesFetch(filesUrl(cluster, ns, pod, container, path), { method: 'DELETE' });
  },
};

export const api = {
  // Validates HTTP Basic creds and establishes the session cookie the exec WebSocket rides.
  verifySession: () => postJson<{ user: string }>('/api/v1/auth/session'),
  clusters: () => getJson<ClusterInfo[]>(CLUSTERS),
  // Runtime cluster management (#198). Every one of these is a non-GET, so it requires the
  // admin login in both security modes. The kubeconfig travels one way only: it is sent on
  // add/update and never comes back — clusterConfig reports `kubeconfigStored` as a
  // boolean, so a stored credential can be shown as present without being exposed.
  addCluster: (definition: ClusterDefinition) =>
    postBody<ClusterInfo>(CLUSTERS, JSON.stringify(definition), 'application/json'),
  updateCluster: (id: string, definition: ClusterDefinition) =>
    putJson<ClusterInfo>(`${CLUSTERS}/${encodeURIComponent(id)}`, JSON.stringify(definition)),
  removeCluster: (id: string) => deleteReq(`${CLUSTERS}/${encodeURIComponent(id)}`),
  clusterConfig: (id: string) => getJson<ClusterConfigView>(`${CLUSTERS}/${encodeURIComponent(id)}/config`),
  /** The contexts in a kubeconfig the user is about to submit. Stores nothing. */
  kubeconfigContexts: (kubeconfig: string) =>
    postBody<string[]>(`${CLUSTERS}/contexts`, JSON.stringify({ kubeconfig }), 'application/json'),
  // Read-only diagnostics (#27): what a cluster supports, and how this instance is configured.
  clusterDiagnostics: (cluster: string) => getJson<ClusterDiagnostics>(`${clusterBase(cluster)}/diagnostics`),
  about: () => getJson<AboutInfo>('/api/v1/about'),
  /**
   * Findings with reasons and fixes, plus any summary already cached for exactly those
   * findings (#218/#251). Deterministic and free — this never starts an inference call, so
   * it is safe to re-run on every mount and namespace change.
   */
  diagnose: (cluster: string, namespace?: string) =>
    getJson<DiagnoseResult>(`${clusterBase(cluster)}/diagnose` + nsQuery(namespace)),
  /**
   * Spend an inference call on this scope and cache the result (#251). A POST because it is
   * write-shaped — it costs money and ships cluster state to a third party — which also puts
   * it behind the admin login in both security modes. The cache it fills is in-memory and
   * per-process: a server restart loses it, and that costs another call, never a wrong answer.
   */
  analyseDiagnosis: (cluster: string, namespace?: string) =>
    postJson<DiagnoseResult>(`${clusterBase(cluster)}/diagnose/summary` + nsQuery(namespace)),
  // Per-kind workload health, computed server-side (GH#153/#155): tallies plus the NAMED
  // objects needing attention, so the browser no longer fetches whole collections to count.
  /** A category dashboard: per-kind tallies plus the named objects needing attention. */
  overview: (cluster: string, category: string, namespace?: string) =>
    getJson<KindHealth[]>(`${clusterBase(cluster)}/overview/${category}` + nsQuery(namespace)),
  // One object plus its resolved relations (GH#136) — one request rather than N per drawer.
  detail: (cluster: string, resourceId: string, namespace: string, name: string) =>
    getJson<ObjectDetail>(
      `${clusterBase(cluster)}/detail/${encodeURIComponent(resourceId)}/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}`,
    ),
  // Build/version metadata from Actuator (public). build.version + build.time when present.
  info: () =>
    getJson<{ build?: { version?: string; time?: string; name?: string }; git?: { commit?: { id?: string } } }>(
      '/actuator/info',
    ),
  nav: (cluster: string) => getJson<NavCategory[]>(`${clusterBase(cluster)}/nav`),
  counts: (cluster: string, namespace?: string) =>
    getJson<Record<string, number>>(`${clusterBase(cluster)}/counts` + nsQuery(namespace)),
  namespaces: (cluster: string) => getJson<ResourceRow[]>(`${clusterBase(cluster)}/namespaces`),
  objects: (cluster: string, resourceId: string, namespace?: string) =>
    getJson<KubeObject[]>(
      `${clusterBase(cluster)}/resources/${encodeURIComponent(resourceId)}/objects` + nsQuery(namespace),
    ),
  // Pods scheduled on a node (field-selected server-side) — backs the node detail Pods tab.
  nodePods: (cluster: string, node: string) =>
    getJson<KubeObject[]>(`${clusterBase(cluster)}/nodes/${encodeURIComponent(node)}/pods`),
  printerColumns: (cluster: string, resourceId: string) =>
    getJson<PrinterColumn[]>(`${clusterBase(cluster)}/resources/${encodeURIComponent(resourceId)}/columns`),
  events: (cluster: string, namespace?: string) =>
    getJson<EventSummary[]>(`${clusterBase(cluster)}/events` + nsQuery(namespace)),
  metricGraph: (cluster: string, target: string, opts?: { namespace?: string; name?: string; minutes?: number }) => {
    const p = new URLSearchParams({ target });
    if (opts?.namespace) {
      p.set('namespace', opts.namespace);
    }
    if (opts?.name) {
      p.set('name', opts.name);
    }
    if (opts?.minutes) {
      p.set('minutes', String(opts.minutes));
    }
    return getJson<MetricSeries>(`${clusterBase(cluster)}/metrics/graph?${p.toString()}`);
  },
  nodeMetrics: (cluster: string) => getJson<UsageSummary[]>(`${clusterBase(cluster)}/metrics/nodes`),
  nodeDisk: (cluster: string) => getJson<NodeDiskUsage[]>(`${clusterBase(cluster)}/metrics/nodes/disk`),
  podMetrics: (cluster: string, namespace?: string) =>
    getJson<UsageSummary[]>(`${clusterBase(cluster)}/metrics/pods` + nsQuery(namespace)),
  helmCharts: (cluster: string, query?: string) =>
    getJson<HelmChart[]>(`${clusterBase(cluster)}/helm/charts` + (query ? `?query=${encodeURIComponent(query)}` : '')),
  helmInstall: (
    cluster: string,
    body: {
      namespace: string;
      releaseName: string;
      repository: string;
      chart: string;
      version: string;
      valuesYaml?: string;
      dryRun: boolean;
      createNamespace?: boolean;
      noHooks?: boolean;
      description?: string;
    },
  ) => postBody<HelmMutationResult>(`${clusterBase(cluster)}/helm/releases`, JSON.stringify(body), 'application/json'),
  helmUpgrade: (
    cluster: string,
    namespace: string,
    name: string,
    body: {
      repository: string;
      chart: string;
      version: string;
      valuesYaml?: string;
      dryRun: boolean;
      noHooks?: boolean;
      force?: boolean;
      valueStrategy?: string;
      maxHistory?: number;
      description?: string;
    },
  ) =>
    postBody<HelmMutationResult>(
      `${helmReleaseUrl(cluster, namespace, name)}/upgrade`,
      JSON.stringify(body),
      'application/json',
    ),
  helmUninstall: (cluster: string, namespace: string, name: string) =>
    postJson<{ result: string }>(`${helmReleaseUrl(cluster, namespace, name)}/uninstall`),
  helmRollback: (cluster: string, namespace: string, name: string, body: { revision: number; dryRun: boolean }) =>
    postBody<HelmMutationResult>(
      `${helmReleaseUrl(cluster, namespace, name)}/rollback`,
      JSON.stringify(body),
      'application/json',
    ),
  helmReleaseResources: (cluster: string, namespace: string, name: string) =>
    getJson<HelmResourceRef[]>(`${helmReleaseUrl(cluster, namespace, name)}/resources`),
  // --- Helm repositories (cluster-agnostic, served by jhelm-rest) ---
  helmRepos: () => getJson<{ name: string; url: string }[]>('/api/v1/helm/repos'),
  helmAddRepo: (name: string, url: string) =>
    postNoContent('/api/v1/helm/repos', JSON.stringify({ name, url }), 'application/json'),
  helmRemoveRepo: (name: string) => deleteReq(`/api/v1/helm/repos/${encodeURIComponent(name)}`),
  helmRefreshRepo: (name: string) => postNoContent(`/api/v1/helm/repos/${encodeURIComponent(name)}/refresh`),
  // --- Reusable values-file library (cluster-agnostic) + a release's stored values ---
  helmValuesList: () => getJson<string[]>('/api/v1/helm/values'),
  helmValuesGet: (name: string) => getText(`/api/v1/helm/values/${encodeURIComponent(name)}`),
  helmValuesSave: (name: string, yaml: string) => putText(`/api/v1/helm/values/${encodeURIComponent(name)}`, yaml),
  helmValuesDelete: (name: string) => deleteReq(`/api/v1/helm/values/${encodeURIComponent(name)}`),
  helmReleaseValues: (cluster: string, namespace: string, name: string) =>
    getText(`${helmReleaseUrl(cluster, namespace, name)}/values`),
  helmHistory: (cluster: string, namespace: string, name: string) =>
    getJson<HelmRelease[]>(`${helmReleaseUrl(cluster, namespace, name)}/history`),
  helmReleases: (cluster: string, namespace?: string) =>
    getJson<HelmRelease[]>(`${clusterBase(cluster)}/helm/releases` + nsQuery(namespace)),
  objectEvents: (cluster: string, kind: string, name: string, namespace?: string) => {
    const p = new URLSearchParams({ kind, name });
    if (namespace) {
      p.set('namespace', namespace);
    }
    return getJson<EventSummary[]>(`${clusterBase(cluster)}/events?${p.toString()}`);
  },
  resources: (cluster: string, resourceId: string, namespace?: string) =>
    getJson<ResourceRow[]>(`${clusterBase(cluster)}/resources/${encodeURIComponent(resourceId)}` + nsQuery(namespace)),
  yaml: (cluster: string, resourceId: string, name: string, namespace?: string) =>
    getText(
      `${clusterBase(cluster)}/yaml` +
        `?resource=${encodeURIComponent(resourceId)}&name=${encodeURIComponent(name)}` +
        (namespace ? `&namespace=${encodeURIComponent(namespace)}` : ''),
    ),
  // JSON Schema for a kind (from the cluster's OpenAPI), for editor completion/lint/hover.
  // Best-effort: returns null when the cluster has no schema for the kind (404) or on error.
  schema: (cluster: string, resourceId: string): Promise<Record<string, unknown> | null> =>
    getJson<Record<string, unknown>>(`${clusterBase(cluster)}/schema?resource=${encodeURIComponent(resourceId)}`).catch(
      () => null,
    ),

  // --- Mutating actions (HTTP Basic auth required) ---
  apply: (cluster: string, manifest: string) =>
    postBody<ResourceRow>(`${clusterBase(cluster)}/apply`, manifest, 'application/yaml'),
  del: (cluster: string, resourceId: string, namespace: string, name: string, force = false) =>
    postJson<ActionResult>(actionUrl(cluster, resourceId, namespace, name, 'delete') + (force ? '?force=true' : '')),
  scale: (cluster: string, resourceId: string, namespace: string, name: string, replicas: number) =>
    postJson<ActionResult>(`${actionUrl(cluster, resourceId, namespace, name, 'scale')}?replicas=${replicas}`),
  restart: (cluster: string, resourceId: string, namespace: string, name: string) =>
    postJson<ActionResult>(actionUrl(cluster, resourceId, namespace, name, 'restart')),
  suspend: (cluster: string, resourceId: string, namespace: string, name: string, suspend: boolean) =>
    postJson<ActionResult>(`${actionUrl(cluster, resourceId, namespace, name, 'suspend')}?suspend=${suspend}`),
  trigger: (cluster: string, resourceId: string, namespace: string, name: string) =>
    postJson<ActionResult>(actionUrl(cluster, resourceId, namespace, name, 'trigger')),
  rollback: (cluster: string, resourceId: string, namespace: string, name: string) =>
    postJson<ActionResult>(actionUrl(cluster, resourceId, namespace, name, 'rollback')),
  drain: (cluster: string, name: string) =>
    postJson<ActionResult>(`${clusterBase(cluster)}/nodes/${encodeURIComponent(name)}/drain`),
  portForwards: (cluster: string) => getJson<PortForward[]>(`${clusterBase(cluster)}/port-forwards`),
  startPortForward: (
    cluster: string,
    body: { kind: string; namespace: string; name: string; remotePort: number; localPort?: number },
  ) => postBody<PortForward>(`${clusterBase(cluster)}/port-forwards`, JSON.stringify(body), 'application/json'),
  stopPortForward: (cluster: string, id: string) =>
    postJson<ActionResult>(`${clusterBase(cluster)}/port-forwards/${encodeURIComponent(id)}/stop`),
  cordon: (cluster: string, name: string) =>
    postJson<ActionResult>(`${clusterBase(cluster)}/nodes/${encodeURIComponent(name)}/cordon`),
  uncordon: (cluster: string, name: string) =>
    postJson<ActionResult>(`${clusterBase(cluster)}/nodes/${encodeURIComponent(name)}/uncordon`),
};

function actionUrl(cluster: string, resourceId: string, namespace: string, name: string, action: string): string {
  return (
    `${clusterBase(cluster)}/resources/${encodeURIComponent(resourceId)}` +
    `/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}/${action}`
  );
}

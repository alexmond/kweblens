import { auth } from './auth';
import type {
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
      throw new Error(`Request timed out after ${Math.round(ms / 1000)}s — ${url}`);
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

export const api = {
  // Validates HTTP Basic creds and establishes the session cookie the exec WebSocket rides.
  verifySession: () => postJson<{ user: string }>('/api/v1/auth/session'),
  clusters: () => getJson<ClusterInfo[]>('/api/v1/clusters'),
  // Build/version metadata from Actuator (public). build.version + build.time when present.
  info: () =>
    getJson<{ build?: { version?: string; time?: string; name?: string }; git?: { commit?: { id?: string } } }>(
      '/actuator/info',
    ),
  nav: (cluster: string) => getJson<NavCategory[]>(`/api/v1/clusters/${cluster}/nav`),
  counts: (cluster: string, namespace?: string) =>
    getJson<Record<string, number>>(
      `/api/v1/clusters/${encodeURIComponent(cluster)}/counts` +
        (namespace ? `?namespace=${encodeURIComponent(namespace)}` : ''),
    ),
  namespaces: (cluster: string) =>
    getJson<ResourceRow[]>(`/api/v1/clusters/${encodeURIComponent(cluster)}/namespaces`),
  objects: (cluster: string, resourceId: string, namespace?: string) =>
    getJson<KubeObject[]>(
      `/api/v1/clusters/${encodeURIComponent(cluster)}/resources/${encodeURIComponent(resourceId)}/objects` +
        (namespace ? `?namespace=${encodeURIComponent(namespace)}` : ''),
    ),
  printerColumns: (cluster: string, resourceId: string) =>
    getJson<PrinterColumn[]>(
      `/api/v1/clusters/${encodeURIComponent(cluster)}/resources/${encodeURIComponent(resourceId)}/columns`,
    ),
  events: (cluster: string, namespace?: string) =>
    getJson<EventSummary[]>(
      `/api/v1/clusters/${encodeURIComponent(cluster)}/events` +
        (namespace ? `?namespace=${encodeURIComponent(namespace)}` : ''),
    ),
  metricGraph: (
    cluster: string,
    target: string,
    opts?: { namespace?: string; name?: string; minutes?: number },
  ) => {
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
    return getJson<MetricSeries>(`/api/v1/clusters/${encodeURIComponent(cluster)}/metrics/graph?${p.toString()}`);
  },
  nodeMetrics: (cluster: string) =>
    getJson<UsageSummary[]>(`/api/v1/clusters/${encodeURIComponent(cluster)}/metrics/nodes`),
  nodeDisk: (cluster: string) =>
    getJson<NodeDiskUsage[]>(`/api/v1/clusters/${encodeURIComponent(cluster)}/metrics/nodes/disk`),
  podMetrics: (cluster: string, namespace?: string) =>
    getJson<UsageSummary[]>(
      `/api/v1/clusters/${encodeURIComponent(cluster)}/metrics/pods` +
        (namespace ? `?namespace=${encodeURIComponent(namespace)}` : ''),
    ),
  helmCharts: (cluster: string, query?: string) =>
    getJson<HelmChart[]>(
      `/api/v1/clusters/${encodeURIComponent(cluster)}/helm/charts` +
        (query ? `?query=${encodeURIComponent(query)}` : ''),
    ),
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
    },
  ) =>
    postBody<HelmMutationResult>(
      `/api/v1/clusters/${encodeURIComponent(cluster)}/helm/releases`,
      JSON.stringify(body),
      'application/json',
    ),
  helmUpgrade: (
    cluster: string,
    namespace: string,
    name: string,
    body: { repository: string; chart: string; version: string; valuesYaml?: string; dryRun: boolean },
  ) =>
    postBody<HelmMutationResult>(
      `/api/v1/clusters/${encodeURIComponent(cluster)}/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}/upgrade`,
      JSON.stringify(body),
      'application/json',
    ),
  helmRollback: (cluster: string, namespace: string, name: string, body: { revision: number; dryRun: boolean }) =>
    postBody<HelmMutationResult>(
      `/api/v1/clusters/${encodeURIComponent(cluster)}/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}/rollback`,
      JSON.stringify(body),
      'application/json',
    ),
  helmReleaseResources: (cluster: string, namespace: string, name: string) =>
    getJson<HelmResourceRef[]>(
      `/api/v1/clusters/${encodeURIComponent(cluster)}/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}/resources`,
    ),
  // --- Helm repositories (cluster-agnostic, served by jhelm-rest) ---
  helmRepos: () => getJson<{ name: string; url: string }[]>('/api/v1/helm/repos'),
  helmAddRepo: (name: string, url: string) =>
    postNoContent('/api/v1/helm/repos', JSON.stringify({ name, url }), 'application/json'),
  helmRemoveRepo: (name: string) => deleteReq(`/api/v1/helm/repos/${encodeURIComponent(name)}`),
  // --- Reusable values-file library (cluster-agnostic) + a release's stored values ---
  helmValuesList: () => getJson<string[]>('/api/v1/helm/values'),
  helmValuesGet: (name: string) => getText(`/api/v1/helm/values/${encodeURIComponent(name)}`),
  helmValuesSave: (name: string, yaml: string) => putText(`/api/v1/helm/values/${encodeURIComponent(name)}`, yaml),
  helmValuesDelete: (name: string) => deleteReq(`/api/v1/helm/values/${encodeURIComponent(name)}`),
  helmReleaseValues: (cluster: string, namespace: string, name: string) =>
    getText(
      `/api/v1/clusters/${encodeURIComponent(cluster)}/helm/releases/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}/values`,
    ),
  helmReleases: (cluster: string, namespace?: string) =>
    getJson<HelmRelease[]>(
      `/api/v1/clusters/${encodeURIComponent(cluster)}/helm/releases` +
        (namespace ? `?namespace=${encodeURIComponent(namespace)}` : ''),
    ),
  objectEvents: (cluster: string, kind: string, name: string, namespace?: string) => {
    const p = new URLSearchParams({ kind, name });
    if (namespace) {
      p.set('namespace', namespace);
    }
    return getJson<EventSummary[]>(`/api/v1/clusters/${encodeURIComponent(cluster)}/events?${p.toString()}`);
  },
  resources: (cluster: string, resourceId: string, namespace?: string) =>
    getJson<ResourceRow[]>(
      `/api/v1/clusters/${encodeURIComponent(cluster)}/resources/${encodeURIComponent(resourceId)}` +
        (namespace ? `?namespace=${encodeURIComponent(namespace)}` : ''),
    ),
  yaml: (cluster: string, resourceId: string, name: string, namespace?: string) =>
    getText(
      `/api/v1/clusters/${encodeURIComponent(cluster)}/yaml` +
        `?resource=${encodeURIComponent(resourceId)}&name=${encodeURIComponent(name)}` +
        (namespace ? `&namespace=${encodeURIComponent(namespace)}` : ''),
    ),

  // --- Mutating actions (HTTP Basic auth required) ---
  apply: (cluster: string, manifest: string) =>
    postBody<ResourceRow>(`/api/v1/clusters/${encodeURIComponent(cluster)}/apply`, manifest, 'application/yaml'),
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
    postJson<ActionResult>(`/api/v1/clusters/${encodeURIComponent(cluster)}/nodes/${encodeURIComponent(name)}/drain`),
  portForwards: (cluster: string) =>
    getJson<PortForward[]>(`/api/v1/clusters/${encodeURIComponent(cluster)}/port-forwards`),
  startPortForward: (
    cluster: string,
    body: { kind: string; namespace: string; name: string; remotePort: number; localPort?: number },
  ) =>
    postBody<PortForward>(
      `/api/v1/clusters/${encodeURIComponent(cluster)}/port-forwards`,
      JSON.stringify(body),
      'application/json',
    ),
  stopPortForward: (cluster: string, id: string) =>
    postJson<ActionResult>(
      `/api/v1/clusters/${encodeURIComponent(cluster)}/port-forwards/${encodeURIComponent(id)}/stop`,
    ),
  cordon: (cluster: string, name: string) =>
    postJson<ActionResult>(`/api/v1/clusters/${encodeURIComponent(cluster)}/nodes/${encodeURIComponent(name)}/cordon`),
  uncordon: (cluster: string, name: string) =>
    postJson<ActionResult>(
      `/api/v1/clusters/${encodeURIComponent(cluster)}/nodes/${encodeURIComponent(name)}/uncordon`,
    ),
};

function actionUrl(cluster: string, resourceId: string, namespace: string, name: string, action: string): string {
  return (
    `/api/v1/clusters/${encodeURIComponent(cluster)}/resources/${encodeURIComponent(resourceId)}` +
    `/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}/${action}`
  );
}

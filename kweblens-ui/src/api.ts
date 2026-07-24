import { auth } from './auth';
import type { ClusterInfo, EventSummary, KubeObject, NavCategory, PrinterColumn, ResourceRow } from './types';

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

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!res.ok) {
    throw new Error(`${res.status} ${res.statusText} — ${url}`);
  }
  return (await res.json()) as T;
}

async function getText(url: string): Promise<string> {
  const res = await fetch(url, { headers: { Accept: 'application/yaml, text/plain' } });
  if (!res.ok) {
    throw new Error(`${res.status} ${res.statusText} — ${url}`);
  }
  return res.text();
}

export const api = {
  clusters: () => getJson<ClusterInfo[]>('/api/v1/clusters'),
  nav: (cluster: string) => getJson<NavCategory[]>(`/api/v1/clusters/${cluster}/nav`),
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
  del: (cluster: string, resourceId: string, namespace: string, name: string) =>
    postJson<ActionResult>(actionUrl(cluster, resourceId, namespace, name, 'delete')),
  scale: (cluster: string, resourceId: string, namespace: string, name: string, replicas: number) =>
    postJson<ActionResult>(`${actionUrl(cluster, resourceId, namespace, name, 'scale')}?replicas=${replicas}`),
  restart: (cluster: string, resourceId: string, namespace: string, name: string) =>
    postJson<ActionResult>(actionUrl(cluster, resourceId, namespace, name, 'restart')),
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

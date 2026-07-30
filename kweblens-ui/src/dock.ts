import { clusterBase } from './api';
import type { DockKind } from './types';

/**
 * A single terminal or log session owned by the dock. Ported verbatim from the React
 * `DockSession` type (kweblens-ui/src/dock.tsx).
 */
/**
 * What a log session follows.
 *
 * - `container` — one container (the classic single-stream view).
 * - `pod` — every container of the pod, interleaved.
 * - `workload` — every container of every pod behind a Deployment/StatefulSet/DaemonSet/Job,
 *   which is the `stern` / `kubectl logs -f deployment/x --all-containers` behaviour.
 */
export type LogScope = 'container' | 'pod' | 'workload';

export type DockSession = {
  id: string;
  kind: DockKind;
  namespace: string;
  pod: string;
  containers: string[];
  /** Popped out of the dock into a floating window. */
  floating?: boolean;
  /** Floating window geometry (viewport px). */
  rect?: { x: number; y: number; w: number; h: number };
  /** Terminal only: attach to the running process (kubectl attach) instead of a new shell. */
  attach?: boolean;
  /** Logs only: how many streams to follow. Defaults to `container`. */
  logScope?: LogScope;
  /** Logs only, `workload` scope: the workload whose pods to follow. */
  workload?: { resourceId: string; name: string };
};

/** Tab / title label for a session kind: logs stay `logs`, terminals show `sh`. */
export function sessionLabel(kind: DockKind): 'logs' | 'sh' {
  return kind === 'logs' ? 'logs' : 'sh';
}

/**
 * Exec WebSocket URL — identical protocol/query to the React source: same host, `/ws/exec`
 * with `cluster`/`namespace`/`pod`/`container`, `ws`↔`wss` from the page protocol, and the
 * optional `&mode=attach` for attach sessions. No WebSocket subprotocol is used (matches React).
 */
export function execSocketUrl(cluster: string, session: DockSession, container: string): string {
  const enc = encodeURIComponent;
  const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
  const mode = session.attach ? '&mode=attach' : '';
  return `${proto}://${window.location.host}/ws/exec?cluster=${enc(cluster)}&namespace=${enc(session.namespace)}&pod=${enc(session.pod)}&container=${enc(container)}${mode}`;
}

// The single-stream helpers (`logBaseUrl` / `containerQuery`) were removed with the switch to
// the multiplexed endpoint below — one connection now serves every scope, including a single
// container. The per-pod `…/log` and `…/log/stream` REST endpoints still exist server-side for
// scripting and the MCP tool surface; the SPA just no longer calls them.

/**
 * The multiplexed log stream URL — one connection carrying every source, tagged per line.
 *
 * Used for all log scopes, including a single container: the server does the tail snapshot
 * and the follow in one response, so there is one code path (and one thing to cancel)
 * rather than a separate fetch + EventSource per stream. A browser would also run out of
 * concurrent connections long before a 20-replica Deployment finished opening them.
 */
export function multiLogStreamUrl(cluster: string, session: DockSession, container: string, tailLines = 200): string {
  const params = new URLSearchParams({ namespace: session.namespace, tailLines: String(tailLines) });
  if (session.logScope === 'workload' && session.workload) {
    params.set('resourceId', session.workload.resourceId);
    params.set('name', session.workload.name);
  } else {
    params.set('pod', session.pod);
  }
  // `pod` scope means every container, so no container filter is sent; `container` scope
  // pins the one the dropdown selected.
  if (session.logScope !== 'pod' && session.logScope !== 'workload' && container) {
    params.set('container', container);
  }
  return `${clusterBase(cluster)}/logs/stream?${params.toString()}`;
}

/**
 * Gutter labels for a whole set of source ids (`ns/pod/container`), computed together so
 * they are actually distinguishable.
 *
 * Replica pod names share a long generated prefix and differ only in the SUFFIX
 * (`podinfo-6fb65cb78-6gn6z` vs `…-t9mht`). A fixed-width gutter truncates the end, so the
 * naive `pod/container` label renders identically for every replica and colour becomes the
 * only way to tell them apart — which it must never be. So in workload scope the shared
 * prefix is stripped, leaving the part that carries the information.
 *
 * The full id is still shown on hover, and the prefix is only stripped when doing so leaves
 * every source with a distinct, non-trivial label.
 */
export function sourceLabels(ids: string[], scope: LogScope | undefined): string[] {
  const parts = ids.map((id) => id.split('/'));
  if (scope !== 'workload') {
    return parts.map((p, i) => p[2] ?? ids[i]);
  }
  const pods = parts.map((p) => p[1] ?? '');
  const shared = commonPrefix(pods);
  const trimmed = pods.map((p) => p.slice(shared.length).replace(/^-+/, ''));
  const usable = trimmed.every((t) => t.length >= 3) && new Set(trimmed).size === pods.length;
  return parts.map((p, i) => `${usable ? trimmed[i] : pods[i]}/${p[2]}`);
}

/** The longest string every input starts with (empty when they share nothing). */
function commonPrefix(values: string[]): string {
  if (values.length < 2) {
    return '';
  }
  let prefix = values[0];
  for (const value of values.slice(1)) {
    let i = 0;
    while (i < prefix.length && i < value.length && prefix[i] === value[i]) {
      i += 1;
    }
    prefix = prefix.slice(0, i);
    if (prefix === '') {
      break;
    }
  }
  return prefix;
}

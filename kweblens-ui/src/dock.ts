import { clusterBase } from './api';
import type { DockKind } from './types';

/**
 * A single terminal or log session owned by the dock. Ported verbatim from the React
 * `DockSession` type (kweblens-ui/src/dock.tsx).
 */
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

/** Base URL for a pod's logs (`…/pods/<ns>/<pod>/log`); tail and `/stream` build on it. */
export function logBaseUrl(cluster: string, session: DockSession): string {
  const enc = encodeURIComponent;
  return `${clusterBase(cluster)}/pods/${enc(session.namespace)}/${enc(session.pod)}/log`;
}

/** `container=<c>&` query fragment (empty when no container is selected). */
export function containerQuery(container: string): string {
  return container ? `container=${encodeURIComponent(container)}&` : '';
}

// ---- Reading a failed request: whose "no" was it? ----
//
// kweblens runs as one shared credential (ADR-001), so on any write the interesting question
// is never "are you signed in" — it is "did the CLUSTER refuse this". The two arrive as
// almost the same thing over the wire, and the client used to collapse them: every modal
// tested `status === 401 || status === 403`, cleared the stored password and said
// "Authentication failed — sign in again." A 403 on apply, though, is nearly always the
// service account lacking the verb — `ApiExceptionHandler.kubernetesRefused` passes the
// API server's own status through precisely so "a 403 from RBAC stays a 403". Signing in
// again changes nothing, because nothing was wrong with the login.
//
// The rule this file encodes is the one `serverPreview.ts` already states for the dry run —
// 400/403/409/422 are ANSWERS, 401 is not — and the one `podFiles.isAuthFailure` already
// applies via the ProblemDetail `code`. Reads and writes now share it.

import { ApiError } from './api';
import { isRefusal } from './serverPreview';

/**
 * What a failed request actually was.
 *
 * <ul>
 * <li><b>session</b> — kweblens does not know us. Only this one warrants clearing the login.
 * <li><b>refused</b> — the cluster (or Helm, or the cluster-config validator) considered the
 * request and said no. A verdict, not a malfunction; the message is the payload.
 * <li><b>unreachable</b> — nothing got a verdict. fabric8's connection failures are mapped to
 * 502 by the server, so this is "the cluster is down or misconfigured", not "you may not".
 * <li><b>failed</b> — everything else: a 500, a timeout, a network error, a thrown non-Error.
 * </ul>
 */
type FailureKind = 'session' | 'refused' | 'unreachable' | 'failed';

/** The ProblemDetail code `ApiExceptionHandler.kubernetesRefused` tags the API server's own verdict with. */
const CLUSTER_REFUSED = 'cluster-refused';

export interface FailureSummary {
  kind: FailureKind;
  /** The HTTP status, or null when the request never produced a response (timeout, offline). */
  status: number | null;
  /** The server's ProblemDetail `code`, or `''`. `cluster-refused` is the API server's own verdict. */
  code: string;
  /** The server's sentence when it sent one, else whatever was thrown. */
  message: string;
}

/**
 * Statuses that mean the request never reached a cluster that could answer it.
 *
 * <p>Which statuses mean "considered, and refused" is NOT restated here: `isRefusal`
 * (serverPreview.ts) already names them and says why 401 is not one of them. Two copies of
 * that list would be one copy that goes stale silently.
 */
const UNREACHABLE_STATUSES = new Set([502, 503, 504]);

/**
 * Classify a thrown value.
 *
 * <p>The load-bearing line is the 403 one. kweblens's own security never sends a 403 —
 * `SecurityConfig` uses `HttpStatusEntryPoint(UNAUTHORIZED)` for both entry points and there
 * is exactly one in-memory admin, so an authenticated principal is never denied by role.
 * A 403 that carries a `code` therefore came from the cluster, and only a bare, bodyless
 * 403 (an intermediary, a future entry point) is worth reading as "sign in again".
 */
export function classifyFailure(e: unknown): FailureSummary {
  if (!(e instanceof ApiError)) {
    // `e.message`, not `String(e)`: the latter prepends "Error: " to a sentence written to
    // be read ("Request timed out after 20s — /api/…"), and the reader does not need to be
    // told that an error is an error.
    return { kind: 'failed', status: null, code: '', message: e instanceof Error ? e.message : String(e) };
  }
  const base = { status: e.status, code: e.code, message: e.message };
  if (e.status === 401 || (e.status === 403 && !e.code)) {
    return { ...base, kind: 'session' };
  }
  if (UNREACHABLE_STATUSES.has(e.status)) {
    return { ...base, kind: 'unreachable' };
  }
  if (isRefusal(e.status)) {
    return { ...base, kind: 'refused' };
  }
  return { ...base, kind: 'failed' };
}

/** Whether this failure is the one that should clear the stored login and prompt again. */
export function isSessionExpiry(e: unknown): boolean {
  return classifyFailure(e).kind === 'session';
}

/**
 * The line to put in front of the operator.
 *
 * <p>Only the API server's own verdict — `code: "cluster-refused"` — is attributed out loud,
 * because that is the one the reader would otherwise mistake for kweblens malfunctioning.
 * Everything else already speaks for itself ("That kubeconfig could not be parsed as YAML",
 * "Service ns/name has no port 8080") and prefixing it would name the wrong culprit: a
 * malformed cluster definition is kweblens refusing, not the cluster.
 */
export function failureNotice(e: unknown): string {
  const f = classifyFailure(e);
  if (f.kind === 'session') {
    return 'Authentication failed — sign in again.';
  }
  if (f.code !== CLUSTER_REFUSED) {
    return f.message;
  }
  return f.kind === 'unreachable'
    ? `Could not reach the cluster: ${f.message}`
    : `The cluster refused this: ${f.message}`;
}

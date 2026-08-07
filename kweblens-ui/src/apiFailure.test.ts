import { describe, expect, it } from 'vitest';

// The real ApiError, not a stub: the classifier branches on `instanceof`, and a stubbed
// error would make every case pass for the wrong reason.
import { ApiError } from './api';
import { classifyFailure, failureNotice, isSessionExpiry } from './apiFailure';

describe('classifyFailure', () => {
  it('reads a bare 401 as the session, since that is the only thing kweblens itself sends', () => {
    expect(classifyFailure(new ApiError(401, '401 Unauthorized')).kind).toBe('session');
  });

  it('reads a CODED 403 as the cluster refusing, not as an expired login', () => {
    // The whole point: ApiExceptionHandler.kubernetesRefused keeps the API server's own 403
    // and tags it. Clearing the login here sends the operator to fix something that works.
    const e = new ApiError(
      403,
      'pods is forbidden: User "system:serviceaccount:kweblens:kweblens" cannot list resource "pods"',
      'cluster-refused',
    );
    expect(classifyFailure(e).kind).toBe('refused');
    expect(isSessionExpiry(e)).toBe(false);
  });

  it('still reads an UNCODED 403 as the session — no body means it did not come from the cluster', () => {
    expect(isSessionExpiry(new ApiError(403, '403 Forbidden'))).toBe(true);
  });

  it('reads 400/409/422 as refusals — a webhook, a conflict and a bad object are all answers', () => {
    for (const status of [400, 409, 422]) {
      expect(classifyFailure(new ApiError(status, 'no', 'cluster-refused')).kind).toBe('refused');
    }
  });

  it('reads 502/503/504 as unreachable — fabric8 connection failures map to 502 server-side', () => {
    expect(classifyFailure(new ApiError(502, 'connect timed out', 'cluster-refused')).kind).toBe('unreachable');
    expect(classifyFailure(new ApiError(504, 'gateway timeout')).kind).toBe('unreachable');
  });

  it('reads a 500 as a plain failure — kweblens broke, nobody refused anything', () => {
    expect(classifyFailure(new ApiError(500, 'boom')).kind).toBe('failed');
  });

  it('keeps the server’s sentence and code on the summary', () => {
    const f = classifyFailure(new ApiError(422, 'admission webhook denied the request', 'cluster-refused'));
    expect(f.message).toBe('admission webhook denied the request');
    expect(f.code).toBe('cluster-refused');
    expect(f.status).toBe(422);
  });

  it('handles a thrown non-ApiError (a timeout, an offline fetch) without pretending to know a status', () => {
    const f = classifyFailure(new Error('Request timed out after 20s — /api/v1/clusters/default/nav'));
    expect(f.kind).toBe('failed');
    expect(f.status).toBeNull();
    expect(f.message).toContain('timed out');
  });
});

describe('failureNotice', () => {
  it('labels the API server’s own verdict as one, and keeps its words', () => {
    const e = new ApiError(422, 'admission webhook denied the request: no owner label', 'cluster-refused');
    expect(failureNotice(e)).toBe('The cluster refused this: admission webhook denied the request: no owner label');
  });

  it('does NOT blame the cluster for kweblens’s own refusals', () => {
    // `invalid-cluster` is the cluster-definition validator, not the API server. Prefixing it
    // with "the cluster refused" would name a cluster that was never contacted.
    const e = new ApiError(400, 'That kubeconfig could not be parsed as YAML', 'invalid-cluster');
    expect(failureNotice(e)).toBe('That kubeconfig could not be parsed as YAML');
  });

  it('says the cluster is unreachable rather than that the request was refused', () => {
    expect(failureNotice(new ApiError(502, 'Connection refused', 'cluster-refused'))).toBe(
      'Could not reach the cluster: Connection refused',
    );
  });

  it('asks for a sign-in only for a real session failure', () => {
    expect(failureNotice(new ApiError(401, '401 Unauthorized'))).toBe('Authentication failed — sign in again.');
  });

  it('passes anything else through unadorned', () => {
    expect(failureNotice(new ApiError(500, 'boom'))).toBe('boom');
    expect(failureNotice(new Error('Request timed out after 20s'))).toBe('Request timed out after 20s');
    // A thrown non-Error still has to render as something.
    expect(failureNotice('just a string')).toBe('just a string');
  });
});

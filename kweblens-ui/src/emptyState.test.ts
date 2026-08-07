import { describe, expect, it } from 'vitest';

import { clusterListEmpty, noMatchEmpty } from './emptyState';

const base = { loaded: true, failed: false, count: 0, canWrite: true };

describe('clusterListEmpty', () => {
  it('says nothing before the first response', () => {
    // Zero clusters is not a fact until the fetch answers. Claiming it mid-flight is a wrong
    // answer that corrects itself, which reads worse than a blank moment.
    expect(clusterListEmpty({ ...base, loaded: false })).toBeNull();
  });

  it('says nothing when the fetch failed', () => {
    // A failed fetch leaves the list empty too, and "none are registered" is then false —
    // the error notice is the message.
    expect(clusterListEmpty({ ...base, failed: true })).toBeNull();
  });

  it('says nothing once there is a cluster', () => {
    expect(clusterListEmpty({ ...base, count: 1 })).toBeNull();
  });

  it('names the two ways a cluster gets registered, as the server does', () => {
    // The server already logs "No clusters registered — set kweblens.clusters[*] or provide
    // a kubeconfig." (ClusterBootstrap). GH#298 was that the browser said none of it.
    const copy = clusterListEmpty(base);
    expect(copy?.title).toBe('No clusters registered');
    expect(copy?.body).toContain('kweblens.clusters[*]');
    expect(copy?.body).toContain('kubeconfig');
  });

  it('offers Add cluster to a writer', () => {
    expect(clusterListEmpty(base)?.action).toEqual({ label: 'Add cluster', kind: 'add-cluster' });
  });

  it('offers the login instead when the reader cannot write', () => {
    // Adding a cluster is a POST, and every non-GET needs the admin login — offering the
    // button to a signed-out reader would be an action that 403s.
    const copy = clusterListEmpty({ ...base, canWrite: false });
    expect(copy?.action).toEqual({ label: 'Sign in', kind: 'sign-in' });
    expect(copy?.body).toContain('admin login');
  });
});

describe('noMatchEmpty', () => {
  it('quotes the query so the reader can see what was filtered on', () => {
    expect(noMatchEmpty('  prod ', 'cluster').title).toBe('No cluster matches “prod”');
  });

  it('offers no action — clearing the filter is the exit, and it is already on screen', () => {
    expect(noMatchEmpty('prod', 'cluster').action).toBeNull();
  });
});
